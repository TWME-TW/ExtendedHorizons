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
    private final PacketChunkCacheService columnCache;
    private final me.mapacheee.extendedhorizons.shared.scheduler.SchedulerService schedulerService;
    private final Map<UUID, Set<Long>> playerFakeChunks = new ConcurrentHashMap<>();
    private final Set<Long> generatingChunks = ConcurrentHashMap.newKeySet();

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

    private static final boolean DEBUG = false;

    private static final int TELEPORT_DETECTION_THRESHOLD = 3;
    private static final int QUEUE_CLEAR_DISTANCE_THRESHOLD = 8;
    private static final int QUEUE_CLEAR_FAR_DISTANCE = 15;
    private static final int PROCESSING_INTERVAL_TICKS = 2;
    private static final int MAX_CHUNKS_PER_TICK_PER_PLAYER = 10;
    private static final long JOIN_WARMUP_DELAY_MS = 1500;

    @Inject
    public FakeChunkService(ConfigService configService, PacketChunkCacheService columnCache,
            me.mapacheee.extendedhorizons.shared.scheduler.SchedulerService schedulerService) {
        this.configService = configService;
        this.columnCache = columnCache;
        this.schedulerService = schedulerService;

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

        startProgressiveLoadingTask();
    }

    public void onPlayerJoin(Player player) {
        warmupStartTimes.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /**
     * Cleans up player data when they quit or change worlds
     */
    public void cleanupPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        playerChunkQueues.remove(uuid);
        playerFakeChunks.remove(uuid);
        lastChunkPosition.remove(uuid);
        warmupStartTimes.put(uuid, System.currentTimeMillis());
        playerChunksProcessedThisTick.remove(uuid);
    }

    /**
     * Starts a task that progressively loads chunks in batches
     */
    private void startProgressiveLoadingTask() {
        schedulerService.runTimer(() -> {
            playerChunksProcessedThisTick.clear();

            List<Map.Entry<UUID, Queue<Long>>> entries = new ArrayList<>(playerChunkQueues.entrySet());

            for (Map.Entry<UUID, Queue<Long>> entry : entries) {
                UUID playerId = entry.getKey();
                Queue<Long> queue = entry.getValue();

                if (queue == null || queue.isEmpty()) {
                    continue;
                }

                Player player = org.bukkit.Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline()) {
                    playerChunkQueues.remove(playerId);
                    continue;
                }

                Long startTime = warmupStartTimes.get(playerId);
                if (startTime != null && System.currentTimeMillis() - startTime < JOIN_WARMUP_DELAY_MS) {
                    continue;
                }

                processChunkQueue(player, queue);
            }

        }, 1L, PROCESSING_INTERVAL_TICKS);
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

        List<Long> batch = new ArrayList<>();
        while (!queue.isEmpty() && batch.size() < MAX_CHUNKS_PER_TICK_PER_PLAYER) {
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

            int chunkX = (int) (key & 0xFFFFFFFFL);
            int chunkZ = (int) (key >> 32);

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
    public CompletableFuture<Integer> sendFakeChunks(Player player, Set<Long> chunkKeys) {
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

        long currentChunkPos = ((long) playerChunkZ << 32) | (playerChunkX & 0xFFFFFFFFL);
        Long lastPos = lastChunkPosition.get(uuid);
        boolean isTeleport = false;
        if (lastPos != null && lastPos != currentChunkPos) {
            int lastChunkX = (int) (lastPos & 0xFFFFFFFFL);
            int lastChunkZ = (int) (lastPos >> 32);
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
        boolean inWarmup = startTime != null && System.currentTimeMillis() - startTime < JOIN_WARMUP_DELAY_MS;

        if (inWarmup) {
            Queue<Long> queue = playerChunkQueues.computeIfAbsent(uuid,
                    k -> new java.util.concurrent.ConcurrentLinkedQueue<>());

            for (long key : chunkKeys) {
                if (!queue.contains(key)) {
                    queue.add(key);
                }
            }

            if (DEBUG)
                logger.info("[EH] Warmup active for {}, queued {} chunks", player.getName(), chunkKeys.size());
            return CompletableFuture.completedFuture(0);
        }

        for (long key : chunkKeys) {
            if (playerSentChunks.contains(key)) {
                continue;
            }

            int chunkX = (int) (key & 0xFFFFFFFFL);
            int chunkZ = (int) (key >> 32);

            if (!isChunkInsideWorldBorder(player, chunkX, chunkZ)) {
                continue;
            }

            if (columnCache.get(chunkX, chunkZ) != null) {
                toSend.add(key);
            } else if (!generatingChunks.contains(key)) {
                toGenerate.add(key);
            }
        }

        if (!toGenerate.isEmpty()) {
            toGenerate.sort((key1, key2) -> {
                int x1 = (int) (key1 & 0xFFFFFFFFL);
                int z1 = (int) (key1 >> 32);
                int x2 = (int) (key2 & 0xFFFFFFFFL);
                int z2 = (int) (key2 >> 32);

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
                        int chunkX = (int) (key & 0xFFFFFFFFL);
                        int chunkZ = (int) (key >> 32);
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
            int chunkX = (int) (key & 0xFFFFFFFFL);
            int chunkZ = (int) (key >> 32);
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
        if (!configService.get().performance().fakeChunks().enableMemoryCache()) {
            return null;
        }

        long chunkKey = packChunkKey(chunkX, chunkZ);

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
     * Packs chunk coordinates into a long key
     */
    private static long packChunkKey(int x, int z) {
        return ((long) z << 32) | ((long) x & 0xFFFFFFFFL);
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
                if (DEBUG) {
                    logger.info("[EH] Chunk {},{} not found on disk, generating", chunkX, chunkZ);
                }
                generateChunkAndSend(player, world, chunkX, chunkZ, key, sentTracker);
                return;
            }

            try {
                org.bukkit.craftbukkit.CraftChunk craftChunk = (org.bukkit.craftbukkit.CraftChunk) chunk;
                LevelChunk nmsChunk = (LevelChunk) craftChunk.getHandle(ChunkStatus.FULL);

                if (nmsChunk != null) {
                    if (DEBUG) {
                        logger.info("[EH] Loaded chunk {},{} from disk", chunkX, chunkZ);
                    }
                    long chunkKey = packChunkKey(chunkX, chunkZ);
                    cacheChunkInMemory(chunkKey, nmsChunk);
                    sendChunkPacket(player, nmsChunk, key, sentTracker);
                } else {
                    if (DEBUG) {
                        logger.info("[EH] Chunk {},{} loaded but not full, generating", chunkX, chunkZ);
                    }
                    generateChunkAndSend(player, world, chunkX, chunkZ, key, sentTracker);
                }
            } catch (Exception e) {
                if (DEBUG) {
                    logger.warn("[EH] Failed to process disk chunk {},{}, falling back to generation: {}",
                            chunkX, chunkZ, e.getMessage());
                }
                generateChunkAndSend(player, world, chunkX, chunkZ, key, sentTracker);
            }
        }, chunkProcessor).exceptionally(throwable -> {
            if (DEBUG) {
                logger.warn("[EH] Failed to load chunk from disk {},{}, falling back to generation: {}",
                        chunkX, chunkZ, throwable.getMessage());
            }
            generateChunkAndSend(player, world, chunkX, chunkZ, key, sentTracker);
            return null;
        });
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
                    long chunkKey = packChunkKey(chunkX, chunkZ);
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
     * Sends a chunk packet to the player
     * This is the common method used by all loading strategies
     */
    private void sendChunkPacket(Player player, LevelChunk nmsChunk, long key, Set<Long> sentTracker) {
        schedulerService.runEntity(player, () -> {
            if (!player.isOnline()) {
                generatingChunks.remove(key);
                return;
            }

            try {
                org.bukkit.craftbukkit.entity.CraftPlayer craftPlayer = (org.bukkit.craftbukkit.entity.CraftPlayer) player;
                net.minecraft.server.level.ServerPlayer nmsPlayer = craftPlayer.getHandle();

                // Verify player is still in the same world as the chunk
                // Use CraftWorld to avoid NMS mapping issues with ServerPlayer.level()
                net.minecraft.server.level.ServerLevel playerLevel = ((org.bukkit.craftbukkit.CraftWorld) player
                        .getWorld()).getHandle();
                if (playerLevel != nmsChunk.getLevel()) {
                    generatingChunks.remove(key);
                    return;
                }

                net.minecraft.world.level.lighting.LevelLightEngine lightEngine = nmsChunk.getLevel().getLightEngine();

                int sectionCount = nmsChunk.getSections().length;
                java.util.BitSet[] lightMasks = getLightMasks(sectionCount);
                java.util.BitSet skyLight = lightMasks[0];
                java.util.BitSet blockLight = lightMasks[1];

                net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet = null;
                try {
                    for (java.lang.reflect.Constructor<?> c : net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket.class
                            .getConstructors()) {
                        if (c.getParameterCount() == 4
                                && c.getParameterTypes()[0].isAssignableFrom(nmsChunk.getClass())) {
                            packet = (net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket) c
                                    .newInstance(
                                            nmsChunk,
                                            lightEngine,
                                            skyLight,
                                            blockLight);
                            break;
                        }
                    }
                } catch (Exception e) {
                    if (DEBUG)
                        logger.error("[EH] Failed to instantiate chunk packet via reflection", e);
                }

                if (packet == null) {
                    // Note: Constructor is deprecated but no alternative available in current Paper
                    // version
                    @SuppressWarnings("deprecation")
                    net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket fallbackPacket = new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                            nmsChunk,
                            lightEngine,
                            skyLight,
                            blockLight);
                    packet = fallbackPacket;
                }

                nmsPlayer.connection.send(packet);

                sentTracker.add(key);
                generatingChunks.remove(key);

                if (DEBUG) {
                    int chunkX = (int) (key & 0xFFFFFFFFL);
                    int chunkZ = (int) (key >> 32);
                    logger.info("[EH] Sent fake chunk {},{} to {}", chunkX, chunkZ, player.getName());
                }
            } catch (Exception e) {
                generatingChunks.remove(key);
                if (DEBUG) {
                    logger.warn("[EH] Failed to send chunk packet: {}", e.getMessage());
                    e.printStackTrace();
                }
            }
        });
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
     */
    public void clearPlayerFakeChunks(Player player) {
        UUID playerId = player.getUniqueId();
        playerFakeChunks.remove(playerId);
        playerChunkQueues.remove(playerId);
        playerChunksProcessedThisTick.remove(playerId);
        playerChunksProcessedThisTick.remove(playerId);
        lastChunkPosition.remove(playerId);
        warmupStartTimes.remove(playerId);
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
                int chunkX = (int) (key & 0xFFFFFFFFL);
                int chunkZ = (int) (key >> 32);
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
    private boolean isFakeChunksEnabledForWorld(org.bukkit.World world) {
        String worldName = world.getName();
        java.util.Map<String, me.mapacheee.extendedhorizons.shared.config.MainConfig.WorldConfig> worldSettings = configService
                .get().worldSettings();

        if (worldSettings == null || !worldSettings.containsKey(worldName)) {
            return configService.get().performance().fakeChunks().enabled();
        }
        return worldSettings.get(worldName).enabled();
    }

    /**
     * Verifies if a chunk is inside the world border
     * 
     * @param player The player whose world to check
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return true if the chunk is inside the world border
     */
    private boolean isChunkInsideWorldBorder(Player player, int chunkX, int chunkZ) {
        org.bukkit.WorldBorder border = player.getWorld().getWorldBorder();
        if (border == null) {
            return true;
        }

        double borderSize = border.getSize();
        if (borderSize >= 5.9999968E7) {
            return true;
        }

        double borderCenterX = border.getCenter().getX();
        double borderCenterZ = border.getCenter().getZ();
        double borderRadius = borderSize / 2.0;

        double chunkMinX = chunkX * 16.0;
        double chunkMaxX = chunkMinX + 16.0;
        double chunkMinZ = chunkZ * 16.0;
        double chunkMaxZ = chunkMinZ + 16.0;

        double dx1 = Math.abs(chunkMinX - borderCenterX);
        double dz1 = Math.abs(chunkMinZ - borderCenterZ);
        double dx2 = Math.abs(chunkMaxX - borderCenterX);
        double dz2 = Math.abs(chunkMaxZ - borderCenterZ);

        return (dx1 <= borderRadius && dz1 <= borderRadius) ||
                (dx2 <= borderRadius && dz2 <= borderRadius) ||
                (dx1 <= borderRadius && dz2 <= borderRadius) ||
                (dx2 <= borderRadius && dz1 <= borderRadius);
    }
}
