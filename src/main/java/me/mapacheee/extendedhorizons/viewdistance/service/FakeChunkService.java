package me.mapacheee.extendedhorizons.viewdistance.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.integration.packetevents.PacketChunkCacheService;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftChunk;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.*;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import me.mapacheee.extendedhorizons.shared.utils.ChunkUtils;

/*
 *   Manages fake chunks (chunks beyond server view-distance)
 *   Optimized loading strategy:
 *   1. PacketEvents cache (fastest) - chunks cached from intercepted packets
 *   2. Memory cache (fast) - chunks already loaded in server memory
 *   3. Disk loading (fast) - loads existing chunks from disk without generating
 *   4. Generation (slowest) - generates new chunks only as last resort
 */
@Service
public class FakeChunkService {

    private static final Logger logger = LoggerFactory.getLogger(FakeChunkService.class);
    private final ConfigService configService;
    private final me.mapacheee.extendedhorizons.integration.packetevents.PacketChunkCacheService columnCache;
    private final Map<UUID, Set<Long>> playerFakeChunks = new ConcurrentHashMap<>();
    private final Set<Long> generatingChunks = ConcurrentHashMap.newKeySet();

    // Performance counters
    private final java.util.concurrent.atomic.AtomicInteger chunksGeneratedThisTick = new java.util.concurrent.atomic.AtomicInteger(
            0);
    private final java.util.Map<UUID, Long> playerBytesThisTick = new ConcurrentHashMap<>();

    // Config values (cached for performance)
    private int maxGenerationsPerTick = 1;
    private long maxBytesPerTick = 25000; // 500KB/s / 20 ticks

    /**
     * Queue of chunks pending to be loaded per player
     * Used for progressive loading instead of loading all chunks at once
     */
    private final Map<UUID, Queue<Long>> playerChunkQueues = new ConcurrentHashMap<>();

    /**
     * Tracks how many chunks have been processed this tick per player
     * Used for throttling
     */
    private final Map<UUID, Integer> playerChunksProcessedThisTick = new ConcurrentHashMap<>();

    /**
     * Tracks last chunk position per player to detect teleports
     */
    private final Map<UUID, Long> lastChunkPosition = new ConcurrentHashMap<>();

    /**
     * Tracks when players joined/teleported to implement warm-up period
     */
    private final Map<UUID, Long> warmupStartTimes = new ConcurrentHashMap<>();

    /**
     * Queue of packets waiting to be sent to players (Batched)
     */
    private final Map<UUID, Queue<net.minecraft.network.protocol.Packet<?>>> pendingPackets = new ConcurrentHashMap<>();

    /**
     * Tracks bytes sent per player in the current second (for bandwidth limiting)
     */
    private final Map<UUID, Long> playerBytesThisSecond = new ConcurrentHashMap<>();

    /**
     * Tracks the timestamp when the byte counter was last reset (per player)
     */
    private final Map<UUID, Long> playerByteResetTime = new ConcurrentHashMap<>();

    /**
     * Caches player average ping for adaptive rate limiting
     */
    private final Map<UUID, Integer> playerAvgPing = new ConcurrentHashMap<>();

    /**
     * Tracks actual bytes sent (measured from real packets)
     */
    private final Map<UUID, Long> playerActualBytesSent = new ConcurrentHashMap<>();

    /**
     * Rolling average of packet sizes per player
     */
    private final Map<UUID, Double> playerAvgPacketSize = new ConcurrentHashMap<>();

    private static final boolean DEBUG = false;
    private static final int TELEPORT_DETECTION_THRESHOLD = 3;
    private static final int QUEUE_CLEAR_DISTANCE_THRESHOLD = 8;
    private static final int QUEUE_CLEAR_FAR_DISTANCE = 15;

    @Inject
    public FakeChunkService(ConfigService configService, PacketChunkCacheService columnCache) {
        this.configService = configService;
        this.columnCache = columnCache;

        int maxCacheSize = configService.get().performance().fakeChunks().maxMemoryCacheSize();
        this.chunkMemoryCache = Collections.synchronizedMap(
                new LinkedHashMap<Long, LevelChunk>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Long, LevelChunk> eldest) {
                        return size() > maxCacheSize;
                    }
                });

        int configuredThreads = configService.get().performance().chunkProcessorThreads();
        int threadCount = configuredThreads > 0
                ? configuredThreads
                : Math.max(4, Runtime.getRuntime().availableProcessors());

        this.chunkProcessor = Executors.newFixedThreadPool(
                threadCount,
                r -> {
                    Thread t = new Thread(r, "EH-ChunkProcessor");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                });

    }

    public void onPlayerJoin(Player player) {
        warmupStartTimes.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /**
     * Cleans up player data when they quit or change worlds
     */
    public void cleanupPlayer(Player player, boolean sendPackets) {
        clearPlayerFakeChunks(player, sendPackets);
    }

    public void cleanupPlayer(Player player) {
        cleanupPlayer(player, true);
    }

    /**
     * Starts a task that progressively loads chunks in batches
     */
    @com.thewinterframework.service.annotation.lifecycle.OnEnable
    public void onEnable() {
        startProgressiveLoadingTask();
        startPacketSenderTask();
    }

    /**
     * Periodic task to load fake chunks for players with a limit
     */
    private void startProgressiveLoadingTask() {
        Bukkit.getAsyncScheduler()
                .runAtFixedRate(me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.getInstance(), (task) -> {
                    chunksGeneratedThisTick.set(0);
                    playerBytesThisTick.clear();

                    maxGenerationsPerTick = configService.get().performance().maxGenerationsPerTick();
                    if (maxGenerationsPerTick <= 0)
                        maxGenerationsPerTick = 1;

                    long bandwidthPerPlayer = configService.get().bandwidthSaver().maxBandwidthPerPlayer(); // KB/s
                    if (bandwidthPerPlayer <= 0)
                        bandwidthPerPlayer = 10000;

                    maxBytesPerTick = (bandwidthPerPlayer * 1024) / 20;

                    try {
                        double mspt = Bukkit.getAverageTickTime();
                        double maxMspt = configService.get().performance().maxMsptForLoading();
                        if (maxMspt > 0 && mspt > maxMspt) {
                            if (DEBUG) {
                                logger.warn("[EH] High MSPT ({}ms > {}ms), skipping chunk loading",
                                        String.format("%.2f", mspt), maxMspt);
                            }
                            return;
                        }
                    } catch (UnsupportedOperationException ignored) {
                    }

                    int activeTasks = ((java.util.concurrent.ThreadPoolExecutor) chunkProcessor).getActiveCount();
                    int queueSize = ((java.util.concurrent.ThreadPoolExecutor) chunkProcessor).getQueue().size();
                    int maxTasks = configService.get().performance().maxAsyncLoadTasks();
                    int maxQueue = configService.get().performance().maxAsyncLoadQueue();

                    if (maxTasks <= 0)
                        maxTasks = 4;
                    if (maxQueue <= 0)
                        maxQueue = 10;

                    if (activeTasks > maxTasks || queueSize > maxQueue) {
                        if (DEBUG) {
                            logger.warn("[EH] High async load ({} active, {} queued), skipping batch", activeTasks,
                                    queueSize);
                        }
                        return;
                    }

                    playerChunksProcessedThisTick.clear();

                    List<Map.Entry<UUID, Queue<Long>>> entries = new ArrayList<>(playerChunkQueues.entrySet());

                    for (Map.Entry<UUID, Queue<Long>> entry : entries) {
                        UUID playerId = entry.getKey();
                        Queue<Long> queue = entry.getValue();

                        if (queue == null || queue.isEmpty()) {
                            continue;
                        }

                        Player player = Bukkit.getPlayer(playerId);
                        if (player == null || !player.isOnline()) {
                            playerChunkQueues.remove(playerId);
                            continue;
                        }

                        Long startTime = warmupStartTimes.get(playerId);
                        long warmupDelay = configService.get().performance().teleportWarmupDelay();
                        if (startTime != null && System.currentTimeMillis() - startTime < warmupDelay) {
                            continue;
                        }

                        processChunkQueue(player, queue);
                    }

                }, 50L, Math.max(50L, configService.get().performance().chunkProcessInterval() * 50L),
                        TimeUnit.MILLISECONDS);
    }

    /**
     * Starts a task that flushes pending packets to players
     */
    private void startPacketSenderTask() {
        Bukkit.getAsyncScheduler()
                .runAtFixedRate(me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.getInstance(), (task) -> {
                    if (pendingPackets.isEmpty())
                        return;

                    for (Iterator<Map.Entry<UUID, Queue<net.minecraft.network.protocol.Packet<?>>>> it = pendingPackets
                            .entrySet().iterator(); it.hasNext();) {
                        Map.Entry<UUID, Queue<net.minecraft.network.protocol.Packet<?>>> entry = it.next();
                        UUID uuid = entry.getKey();
                        Queue<net.minecraft.network.protocol.Packet<?>> queue = entry.getValue();

                        if (queue == null || queue.isEmpty()) {
                            it.remove();
                            continue;
                        }

                        Player player = Bukkit.getPlayer(uuid);
                        if (player == null || !player.isOnline()) {
                            it.remove();
                            continue;
                        }

                        try {
                            org.bukkit.craftbukkit.entity.CraftPlayer craftPlayer = (org.bukkit.craftbukkit.entity.CraftPlayer) player;
                            net.minecraft.server.level.ServerPlayer nmsPlayer = craftPlayer.getHandle();
                            net.minecraft.network.Connection connection = nmsPlayer.connection.connection;

                            int ping = player.getPing();
                            playerAvgPing.put(uuid, ping);

                            int maxPacketsThisTick = calculateMaxPackets(ping);

                            long now = System.currentTimeMillis();
                            if (!playerByteResetTime.containsKey(uuid)) {
                                playerByteResetTime.put(uuid, now);
                                playerBytesThisSecond.put(uuid, 0L);
                                playerActualBytesSent.put(uuid, 0L);
                            }

                            long lastReset = playerByteResetTime.get(uuid);
                            if (now - lastReset >= 1000) {
                                playerBytesThisSecond.put(uuid, 0L);
                                playerActualBytesSent.put(uuid, 0L);
                                playerByteResetTime.put(uuid, now);
                            }

                            long bytesSent = playerBytesThisSecond.get(uuid);
                            int maxBandwidth = configService.get().bandwidthSaver().maxBandwidthPerPlayer();
                            boolean bandwidthExceeded = maxBandwidth > 0 && bytesSent >= (maxBandwidth * 1024L);

                            int count = 0;
                            while (!queue.isEmpty() && count < maxPacketsThisTick && !bandwidthExceeded) {
                                net.minecraft.network.protocol.Packet<?> packet = queue.poll();
                                if (packet == null)
                                    continue;

                                connection.send(packet);
                                count++;

                                long packetSize = estimatePacketSize(packet);
                                bytesSent += packetSize;
                                playerBytesThisSecond.put(uuid, bytesSent);
                                playerActualBytesSent.put(uuid,
                                        playerActualBytesSent.getOrDefault(uuid, 0L) + packetSize);

                                updateAvgPacketSize(uuid, packetSize);

                                if (maxBandwidth > 0 && bytesSent >= (maxBandwidth * 1024L)) {
                                    bandwidthExceeded = true;
                                }
                            }
                        } catch (Exception e) {
                            it.remove();
                        }
                    }
                }, 50L, 50L, TimeUnit.MILLISECONDS);
    }

    /**
     * Calculates the maximum number of packets to send based on player ping
     * and adaptive rate limiting configuration
     */
    private int calculateMaxPackets(int ping) {
        if (!configService.get().bandwidthSaver().adaptiveRateLimiting()) {
            return 50;
        }

        if (ping < 50) {
            return 50;
        } else if (ping < 150) {
            return 25;
        } else {
            return 10;
        }
    }

    /**
     * Estimates the size of a packet in bytes
     */
    private long estimatePacketSize(net.minecraft.network.protocol.Packet<?> packet) {
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket) {
            return configService.get().bandwidthSaver().estimatedPacketSize();
        }
        return 512;
    }

    /**
     * Updates the rolling average packet size for a player
     */
    private void updateAvgPacketSize(UUID uuid, long size) {
        double current = playerAvgPacketSize.getOrDefault(uuid,
                (double) configService.get().bandwidthSaver().estimatedPacketSize());
        double newAvg = (current * 0.9) + (size * 0.1);
        playerAvgPacketSize.put(uuid, newAvg);
    }

    /**
     * Gets or creates light masks for a given section count
     * Returns [skyLight, blockLight] BitSets
     */
    private java.util.BitSet[] getLightMasks(int sectionCount) {
        return lightMaskCache.computeIfAbsent(sectionCount, count -> {
            java.util.BitSet skyLight = new java.util.BitSet();
            java.util.BitSet blockLight = new java.util.BitSet();

            for (int i = 0; i < count; i++) {
                skyLight.set(i);
                blockLight.set(i);
            }

            return new java.util.BitSet[] { skyLight, blockLight };
        });
    }

    /**
     * Processes chunk queue for a player with rate limiting
     *
     * @param player The player
     * @param queue  The queue of chunks to process
     */
    private void processChunkQueue(Player player, Queue<Long> queue) {
        if (!player.isOnline() || queue.isEmpty()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        Set<Long> sentTracker = playerFakeChunks.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());

        int maxChunks = configService.get().bandwidthSaver().maxFakeChunksPerTick();
        List<Long> batch = new ArrayList<>();
        while (!queue.isEmpty() && batch.size() < maxChunks) {
            Long key = queue.poll();
            if (key != null && !generatingChunks.contains(key)) {
                batch.add(key);
            }
        }

        if (!batch.isEmpty()) {
            processChunkBatch(player, batch, sentTracker);

            if (DEBUG) {
                logger.info("[EH] Processed {} chunks for {} ({} remaining in queue)",
                        batch.size(), player.getName(), queue.size());
            }
        }
    }

    /**
     * Processes a batch of chunks using a three-tiered loading strategy:
     * 1. Memory cache (fastest) - checks servers internal chunk cache
     * 2. Disk NBT (fast) - loads chunk data directly from disk
     * 3. Generation (slowest) - generates new chunk if not found
     * 
     * @param player      The player to send chunks to
     * @param batch       The list of chunk keys to process
     * @param sentTracker Set tracking which chunks have already been sent to this
     *                    player
     */
    private void processChunkBatch(Player player, List<Long> batch, Set<Long> sentTracker) {
        World world = player.getWorld();

        for (long key : batch) {
            if (!player.isOnline())
                break;

            generatingChunks.add(key);

            UUID uuid = player.getUniqueId();
            long bytesUsed = playerBytesThisTick.getOrDefault(uuid, 0L);
            if (bytesUsed >= maxBytesPerTick) {
                generatingChunks.remove(key);
                playerChunkQueues.computeIfAbsent(uuid, k -> new java.util.concurrent.ConcurrentLinkedQueue<>())
                        .add(key);
                continue;
            }

            int chunkX = ChunkUtils.unpackX(key);
            int chunkZ = ChunkUtils.unpackZ(key);

            chunkProcessor.execute(() -> {
                try {
                    // Strategy 1: Try to get chunk from PacketEvents cache
                    Column cachedColumn = columnCache.get(chunkX, chunkZ);
                    if (cachedColumn != null) {
                        if (DEBUG) {
                            logger.info("[EH] Loaded chunk {},{} from PacketEvents cache", chunkX, chunkZ);
                        }
                        if (sendColumnToPlayer(player, cachedColumn)) {
                            sentTracker.add(key);
                            generatingChunks.remove(key);
                        } else {
                            generatingChunks.remove(key);
                        }
                        return;
                    }

                    // Strategy 2: Try to get chunk from servers memory cache
                    LevelChunk memoryChunk = getChunkFromMemoryCache(world, chunkX, chunkZ);
                    if (memoryChunk != null) {
                        if (DEBUG) {
                            logger.info("[EH] Loaded chunk {},{} from memory cache", chunkX, chunkZ);
                        }
                        sendChunkPacket(player, memoryChunk, key, sentTracker);
                        return;
                    }

                    // Strategy 3: Try to load chunk from disk
                    loadChunkFromDiskAndSend(player, world, chunkX, chunkZ, key, sentTracker);

                } catch (Exception e) {
                    generatingChunks.remove(key);
                    logger.warn("[EH] Error loading chunk {},{}: {}", chunkX, chunkZ, e.getMessage());
                    if (DEBUG) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    /**
     * Attempts to load chunk from disk without generating
     */
    private void loadChunkFromDiskAndSend(Player player, World world, int chunkX, int chunkZ,
            long key, Set<Long> sentTracker) {
        world.getChunkAtAsync(chunkX, chunkZ, false).thenAcceptAsync(chunk -> {
            if (!player.isOnline()) {
                generatingChunks.remove(key);
                return;
            }

            if (chunk == null || !chunk.isLoaded()) {
                if (chunksGeneratedThisTick.get() >= maxGenerationsPerTick) {
                    if (DEBUG)
                        logger.debug("[EH] Generation limit hit, deferring chunk {},{}", chunkX, chunkZ);
                    generatingChunks.remove(key);
                    playerChunkQueues
                            .computeIfAbsent(player.getUniqueId(),
                                    k -> new java.util.concurrent.ConcurrentLinkedQueue<>())
                            .add(key);
                    return;
                }

                chunksGeneratedThisTick.incrementAndGet();

                if (DEBUG) {
                    logger.info("[EH] Chunk {},{} not found on disk, generating", chunkX, chunkZ);
                }
                generateChunkAndSend(player, world, chunkX, chunkZ, key, sentTracker);
            } else {
                if (DEBUG) {
                    logger.info("[EH] Chunk {},{} loaded from disk", chunkX, chunkZ);
                }
                sendChunkPacket(player, (LevelChunk) ((CraftChunk) chunk).getHandle(ChunkStatus.FULL), key,
                        sentTracker);
            }
        }, chunkProcessor).exceptionally(throwable -> {
            if (chunksGeneratedThisTick.get() >= maxGenerationsPerTick) {
                generatingChunks.remove(key);
                playerChunkQueues
                        .computeIfAbsent(player.getUniqueId(), k -> new java.util.concurrent.ConcurrentLinkedQueue<>())
                        .add(key);
                return null;
            }
            chunksGeneratedThisTick.incrementAndGet();

            if (DEBUG) {
                logger.warn("[EH] Failed to process disk chunk {},{}, falling back to generation: {}",
                        chunkX, chunkZ, throwable.getMessage());
            }
            generateChunkAndSend(player, world, chunkX, chunkZ, key, sentTracker);
            return null;
        });
    }

    /**
     * Gets the servers actual view distance from server.properties
     */
    public int getServerViewDistance() {
        return Bukkit.getViewDistance();
    }

    /**
     * Sends fake chunks to a player
     * Chunks are prioritized by distance (closer chunks first)
     * Uses progressive loading to avoid overwhelming the server
     */
    public CompletableFuture<Integer> sendFakeChunks(Player player, Set<Long> chunkKeys, double borderCenterX,
            double borderCenterZ, double borderSize) {
        if (!configService.get().performance().fakeChunks().enabled()) {
            return CompletableFuture.completedFuture(0);
        }

        if (chunkKeys.isEmpty()) {
            if (DEBUG) {
                logger.info("[EH] No fake chunks to send for {}", player.getName());
            }
            return CompletableFuture.completedFuture(0);
        }

        if (DEBUG) {
            logger.info("[EH] sendFakeChunks called for {} with {} chunks", player.getName(), chunkKeys.size());
        }

        if (!isFakeChunksEnabledForWorld(player.getWorld())) {
            return CompletableFuture.completedFuture(0);
        }

        CompletableFuture<Integer> result = new CompletableFuture<>();
        UUID uuid = player.getUniqueId();
        Set<Long> playerSentChunks = playerFakeChunks.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());

        Set<Long> toRemove = new HashSet<>(playerSentChunks);
        toRemove.removeAll(chunkKeys);
        playerSentChunks.removeAll(toRemove);

        List<Long> toSend = new ArrayList<>();
        List<Long> toGenerate = new ArrayList<>();

        int playerChunkX = player.getLocation().getBlockX() >> 4;
        int playerChunkZ = player.getLocation().getBlockZ() >> 4;

        long currentChunkPos = ChunkUtils.packChunkKey(playerChunkX, playerChunkZ);
        Long lastPos = lastChunkPosition.get(uuid);
        boolean isTeleport = false;
        if (lastPos != null && lastPos != currentChunkPos) {
            int lastChunkX = ChunkUtils.unpackX(lastPos);
            int lastChunkZ = ChunkUtils.unpackZ(lastPos);
            int dx = Math.abs(lastChunkX - playerChunkX);
            int dz = Math.abs(lastChunkZ - playerChunkZ);
            isTeleport = (dx > TELEPORT_DETECTION_THRESHOLD || dz > TELEPORT_DETECTION_THRESHOLD);
        }
        lastChunkPosition.put(uuid, currentChunkPos);

        if (isTeleport) {
            warmupStartTimes.put(uuid, System.currentTimeMillis());
            Queue<Long> oldQueue = playerChunkQueues.get(uuid);
            if (oldQueue != null)
                oldQueue.clear();
            if (DEBUG)
                logger.info("[EH] Teleport detected for {}, starting warmup", player.getName());
        }

        Long startTime = warmupStartTimes.get(uuid);
        long warmupDelay = configService.get().performance().teleportWarmupDelay();
        boolean inWarmup = startTime != null && System.currentTimeMillis() - startTime < warmupDelay;

        if (inWarmup) {
            List<Long> sortedKeys = new ArrayList<>(chunkKeys);
            sortedKeys.sort((key1, key2) -> {
                int x1 = ChunkUtils.unpackX(key1);
                int z1 = ChunkUtils.unpackZ(key1);
                int x2 = ChunkUtils.unpackX(key2);
                int z2 = ChunkUtils.unpackZ(key2);

                int dx1 = x1 - playerChunkX;
                int dz1 = z1 - playerChunkZ;
                int dx2 = x2 - playerChunkX;
                int dz2 = z2 - playerChunkZ;
                long dist1Squared = (long) dx1 * dx1 + (long) dz1 * dz1;
                long dist2Squared = (long) dx2 * dx2 + (long) dz2 * dz2;

                return Long.compare(dist1Squared, dist2Squared);
            });

            Queue<Long> queue = playerChunkQueues.computeIfAbsent(uuid,
                    k -> new java.util.concurrent.ConcurrentLinkedQueue<>());

            for (long key : sortedKeys) {
                if (!queue.contains(key)) {
                    queue.add(key);
                }
            }

            if (DEBUG)
                logger.info("[EH] Warmup active for {}, queued {} chunks (sorted by distance)", player.getName(),
                        chunkKeys.size());
            return CompletableFuture.completedFuture(0);
        }

        for (long key : chunkKeys) {
            if (playerSentChunks.contains(key)) {
                continue;
            }

            int chunkX = ChunkUtils.unpackX(key);
            int chunkZ = ChunkUtils.unpackZ(key);

            if (!ChunkUtils.isChunkWithinWorldBorder(borderCenterX, borderCenterZ, borderSize, chunkX, chunkZ)) {
                continue;
            }

            if (columnCache.get(chunkX, chunkZ) != null) {
                toSend.add(key);
            } else if (!generatingChunks.contains(key)) {
                toGenerate.add(key);
            }
        }

        if (!toSend.isEmpty()) {
            if (DEBUG) {
                logger.info("[EH] Sending {} cached chunks to {}", toSend.size(), player.getName());
            }

            for (long key : toSend) {
                int chunkX = ChunkUtils.unpackX(key);
                int chunkZ = ChunkUtils.unpackZ(key);
                Column cachedColumn = columnCache.get(chunkX, chunkZ);

                if (cachedColumn != null && sendColumnToPlayer(player, cachedColumn)) {
                    playerSentChunks.add(key);
                }
            }
        }

        if (!toGenerate.isEmpty()) {
            toGenerate.sort((key1, key2) -> {
                int x1 = ChunkUtils.unpackX(key1);
                int z1 = ChunkUtils.unpackZ(key1);
                int x2 = ChunkUtils.unpackX(key2);
                int z2 = ChunkUtils.unpackZ(key2);

                int dx1 = x1 - playerChunkX;
                int dz1 = z1 - playerChunkZ;
                int dx2 = x2 - playerChunkX;
                int dz2 = z2 - playerChunkZ;
                long dist1Squared = (long) dx1 * dx1 + (long) dz1 * dz1;
                long dist2Squared = (long) dx2 * dx2 + (long) dz2 * dz2;

                return Long.compare(dist1Squared, dist2Squared);
            });

            Queue<Long> queue = playerChunkQueues.computeIfAbsent(uuid,
                    k -> new java.util.concurrent.ConcurrentLinkedQueue<>());

            if (!queue.isEmpty()) {
                int currentChunkX = player.getLocation().getBlockX() >> 4;
                int currentChunkZ = player.getLocation().getBlockZ() >> 4;

                if (shouldClearQueue(queue, currentChunkX, currentChunkZ)) {
                    if (DEBUG) {
                        logger.info("[EH] Clearing old chunk queue for {} due to significant movement ({} chunks)",
                                player.getName(), queue.size());
                    }
                    queue.clear();

                    generatingChunks.removeIf(key -> {
                        int chunkX = ChunkUtils.unpackX(key);
                        int chunkZ = ChunkUtils.unpackZ(key);
                        double dist = Math.sqrt((chunkX - currentChunkX) * (chunkX - currentChunkX) +
                                (chunkZ - currentChunkZ) * (chunkZ - currentChunkZ));
                        return dist > QUEUE_CLEAR_FAR_DISTANCE;
                    });
                } else {
                    if (DEBUG) {
                        logger.info("[EH] Keeping existing queue for {} (chunks still relevant)", player.getName());
                    }
                }
            }

            queue.addAll(toGenerate);

            playerChunksProcessedThisTick.remove(uuid);
            processChunkQueue(player, queue);
        }

        result.complete(0);
        return result;
    }

    /**
     * Checks if the queue should be cleared due to significant player movement.
     * Only clears if all sampled chunks are far away (like teleporting).
     * 
     * @param queue        The queue of chunk keys to check
     * @param playerChunkX The player's current chunk X coordinate
     * @param playerChunkZ The player's current chunk Z coordinate
     * @return true if the queue should be cleared (all chunks are far away)
     */
    private boolean shouldClearQueue(Queue<Long> queue, int playerChunkX, int playerChunkZ) {
        if (queue.isEmpty()) {
            return false;
        }

        int samples = Math.min(10, queue.size());
        Iterator<Long> it = queue.iterator();
        int farChunks = 0;
        int totalSamples = 0;

        for (int i = 0; i < samples && it.hasNext(); i++) {
            long key = it.next();
            int chunkX = ChunkUtils.unpackX(key);
            int chunkZ = ChunkUtils.unpackZ(key);
            double dist = Math.sqrt((chunkX - playerChunkX) * (chunkX - playerChunkX) +
                    (chunkZ - playerChunkZ) * (chunkZ - playerChunkZ));

            totalSamples++;

            if (dist > QUEUE_CLEAR_DISTANCE_THRESHOLD) {
                farChunks++;
            }
        }

        return totalSamples > 0 && farChunks == totalSamples;
    }

    /**
     * Attempts to get a chunk from the servers memory cache or our own cache
     */
    private LevelChunk getChunkFromMemoryCache(World world, int chunkX, int chunkZ) {
        boolean antiXrayEnabled = configService.get().performance().fakeChunks().antiXray().enabled();
        if (!configService.get().performance().fakeChunks().enableMemoryCache() || antiXrayEnabled) {
            return null;
        }

        long chunkKey = ChunkUtils.packChunkKey(chunkX, chunkZ);

        synchronized (chunkMemoryCache) {
            LevelChunk cached = chunkMemoryCache.get(chunkKey);
            if (cached != null) {
                return cached;
            }
        }

        try {
            ServerLevel serverLevel = ((CraftWorld) world).getHandle();

            ChunkHolder chunkHolder = serverLevel.getChunkSource().chunkMap
                    .getVisibleChunkIfPresent(chunkKey);

            if (chunkHolder != null) {
                LevelChunk chunk = chunkHolder.getFullChunkNow();
                if (chunk != null && !(chunk instanceof EmptyLevelChunk)) {
                    cacheChunkInMemory(chunkKey, chunk);
                    return chunk;
                }
            }
        } catch (NoSuchMethodError | NoClassDefFoundError | Exception e) {
            if (DEBUG) {
                logger.debug("[EH] Memory cache lookup failed for {},{}: {}", chunkX, chunkZ, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Caches a chunk in memory for reuse
     */
    private void cacheChunkInMemory(long chunkKey, LevelChunk chunk) {
        if (!configService.get().performance().fakeChunks().enableMemoryCache()) {
            return;
        }

        synchronized (chunkMemoryCache) {
            chunkMemoryCache.put(chunkKey, chunk);
        }
    }

    /**
     * Generates a new chunk and sends it to the player
     * This is the slowest method and should be the last resort
     */
    private void generateChunkAndSend(Player player, World world, int chunkX, int chunkZ,
            long key, Set<Long> sentTracker) {
        world.getChunkAtAsync(chunkX, chunkZ, true).thenAcceptAsync(chunk -> {
            if (!player.isOnline()) {
                generatingChunks.remove(key);
                return;
            }

            try {
                org.bukkit.craftbukkit.CraftChunk craftChunk = (org.bukkit.craftbukkit.CraftChunk) chunk;
                LevelChunk nmsChunk = (LevelChunk) craftChunk.getHandle(ChunkStatus.FULL);

                if (nmsChunk != null) {
                    if (DEBUG) {
                        logger.info("[EH] Generated chunk {},{}", chunkX, chunkZ);
                    }
                    long chunkKey = ChunkUtils.packChunkKey(chunkX, chunkZ);
                    cacheChunkInMemory(chunkKey, nmsChunk);
                    sendChunkPacket(player, nmsChunk, key, sentTracker);
                } else {
                    generatingChunks.remove(key);
                    if (DEBUG) {
                        logger.warn("[EH] Generated chunk {},{} is null", chunkX, chunkZ);
                    }
                }
            } catch (Exception e) {
                generatingChunks.remove(key);
                if (DEBUG) {
                    logger.warn("[EH] Failed to process generated chunk {},{}: {}", chunkX, chunkZ, e.getMessage());
                }
            }
        }, chunkProcessor).exceptionally(throwable -> {
            generatingChunks.remove(key);
            logger.warn("[EH] Failed to generate chunk {},{}: {}", chunkX, chunkZ, throwable.getMessage());
            return null;
        });
    }

    /**
     * Cache for light BitSets to avoid recalculating them for each chunk
     */
    private final Map<Integer, java.util.BitSet[]> lightMaskCache = new ConcurrentHashMap<>();

    /**
     * Enqueues a chunk packet to be sent to the player
     * Packets are created here in async thread and queued for sending
     */
    private void sendChunkPacket(Player player, LevelChunk nmsChunk, long key, Set<Long> sentTracker) {
        if (configService.get().performance().fakeChunks().antiXray().enabled()) {
            try {
                nmsChunk = cloneChunkForObfuscation(nmsChunk);

                boolean hideOres = configService.get().performance().fakeChunks().antiXray().hideOres();
                boolean addFakeOres = configService.get().performance().fakeChunks().antiXray().addFakeOres();
                double density = configService.get().performance().fakeChunks().antiXray().fakeOreDensity();

                ChunkAntiXray.obfuscateChunk(nmsChunk, hideOres, addFakeOres, density);
            } catch (Exception e) {
                if (DEBUG) {
                    logger.warn("[EH] Failed to obfuscate chunk: {}", e.getMessage());
                }
            }
        }

        net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet = null;
        try {
            net.minecraft.world.level.lighting.LevelLightEngine lightEngine = nmsChunk.getLevel().getLightEngine();
            int sectionCount = nmsChunk.getSections().length;
            java.util.BitSet[] lightMasks = getLightMasks(sectionCount);

            @SuppressWarnings("deprecation")
            net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket createdPacket = new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                    nmsChunk,
                    lightEngine,
                    lightMasks[0],
                    lightMasks[1]);
            packet = createdPacket;
        } catch (Throwable e) {
            if (DEBUG)
                logger.error("[EH] Failed to create chunk packet", e);
            generatingChunks.remove(key);
            return;
        }

        if (packet == null) {
            generatingChunks.remove(key);
            return;
        }

        pendingPackets.computeIfAbsent(player.getUniqueId(), k -> new java.util.concurrent.ConcurrentLinkedQueue<>())
                .add(packet);

        sentTracker.add(key);
        generatingChunks.remove(key);

        if (DEBUG) {
            int chunkX = ChunkUtils.unpackX(key);
            int chunkZ = ChunkUtils.unpackZ(key);
            logger.info("[EH] Queued chunk packet {},{} for {}", chunkX, chunkZ, player.getName());
        }
    }

    /**
     * Placeholder for chunk cloning (not needed - memory cache is disabled when
     * anti-xray is active)
     */
    private LevelChunk cloneChunkForObfuscation(LevelChunk original) {
        // No cloning needed: memory cache is automatically disabled when anti-xray is
        // enabled
        // This prevents cached chunks from being permanently obfuscated
        return original;
    }

    /**
     * Sends a Column directly to the player using PacketEvents
     * Returns true if successful, false if Column is null or invalid
     */
    private boolean sendColumnToPlayer(Player player, Column column) {
        if (column == null) {
            if (DEBUG) {
                logger.warn("[EH] Attempted to send null column to " + player.getName());
            }
            return false;
        }

        try {
            WrapperPlayServerChunkData packet = new WrapperPlayServerChunkData(column);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
            return true;
        } catch (Exception e) {
            if (DEBUG) {
                logger.warn("[EH] Failed to send column to " + player.getName() + ": " + e.getMessage());
            }
            return false;
        }
    }

    /**
     * Clears fake chunks for a player
     * 
     * @param player      The player to clear chunks for
     * @param sendPackets If true, sends unload packets to client. False if player
     *                    quit or changed worlds
     */
    public void clearPlayerFakeChunks(Player player, boolean sendPackets) {
        UUID playerId = player.getUniqueId();
        Set<Long> fakeChunks = playerFakeChunks.remove(playerId);

        if (sendPackets && fakeChunks != null && !fakeChunks.isEmpty()) {
            for (Long key : fakeChunks) {
                int chunkX = me.mapacheee.extendedhorizons.shared.utils.ChunkUtils.unpackX(key);
                int chunkZ = me.mapacheee.extendedhorizons.shared.utils.ChunkUtils.unpackZ(key);
                sendUnloadPacket(player, chunkX, chunkZ);
            }
        }

        playerChunkQueues.remove(playerId);
        playerChunksProcessedThisTick.remove(playerId);
        lastChunkPosition.remove(playerId);
        warmupStartTimes.remove(playerId);
        pendingPackets.remove(playerId);
        playerBytesThisSecond.remove(playerId);
        playerByteResetTime.remove(playerId);
        playerAvgPing.remove(playerId);
        playerActualBytesSent.remove(playerId);
        playerAvgPacketSize.remove(playerId);
        playerBytesThisTick.remove(playerId);
    }

    public void clearPlayerFakeChunks(Player player) {
        clearPlayerFakeChunks(player, true);
    }

    /**
     * Sends an unload packet to the client for a specific chunk
     */
    private void sendUnloadPacket(Player player, int chunkX, int chunkZ) {
        try {
            net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket packet = new net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket(
                    new net.minecraft.world.level.ChunkPos(chunkX, chunkZ));
            ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle().connection.send(packet);
        } catch (Exception e) {
        }
    }

    /**
     * Shutdown the async executor
     */
    public void shutdown() {
        chunkProcessor.shutdown();
        chunkMemoryCache.clear();
    }

    /**
     * Clears the memory cache (useful for memory management)
     */
    public void clearMemoryCache() {
        chunkMemoryCache.clear();
    }

    /**
     * Gets the current size of the memory cache
     */
    public int getMemoryCacheSize() {
        return chunkMemoryCache.size();
    }

    public int getCacheSize() {
        return columnCache.size();
    }

    public double getCacheHitRate() {
        long hits = 0;
        long total = 0;
        for (Set<Long> chunks : playerFakeChunks.values()) {
            total += chunks.size();
            for (long key : chunks) {
                int chunkX = ChunkUtils.unpackX(key);
                int chunkZ = ChunkUtils.unpackZ(key);
                if (columnCache.get(chunkX, chunkZ) != null) {
                    hits++;
                }
            }
        }
        return total > 0 ? (hits * 100.0 / total) : 0.0;
    }

    public double getEstimatedMemoryUsageMB() {
        return columnCache.size() * 0.04;
    }

    /**
     * Thread pool for parallel chunk processing
     * Size is configurable via config.yml (performance.chunk-processor-threads)
     * Defaults to max(4, availableProcessors) if set to 0
     */
    private final ExecutorService chunkProcessor;

    /**
     * Cache for NMS chunks already loaded in memory
     * Reuses chunks without regenerating them
     */
    private final Map<Long, LevelChunk> chunkMemoryCache;

    /**
     * Checks if fake chunks are enabled for a specific world
     * 
     * @param world The world to check
     * @return true if fake chunks are enabled for this world
     */
    public boolean isFakeChunksEnabledForWorld(org.bukkit.World world) {
        String worldName = world.getName();
        java.util.Map<String, me.mapacheee.extendedhorizons.shared.config.MainConfig.WorldConfig> worldSettings = configService
                .get().worldSettings();

        if (worldSettings == null || !worldSettings.containsKey(worldName)) {
            return configService.get().performance().fakeChunks().enabled();
        }
        return worldSettings.get(worldName).enabled();
    }
}
