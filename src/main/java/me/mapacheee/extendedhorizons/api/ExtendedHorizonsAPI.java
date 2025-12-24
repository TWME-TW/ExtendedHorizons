package me.mapacheee.extendedhorizons.api;

import me.mapacheee.extendedhorizons.api.event.FakeChunkBatchLoadEvent.ChunkCoordinate;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Public API for ExtendedHorizons plugin.
 * Provides methods to query and control fake chunks.
 * <p>
 * To access this API from another plugin:
 * 
 * <pre>{@code
 * ExtendedHorizonsAPI api = ExtendedHorizonsPlugin.getService(ExtendedHorizonsAPI.class);
 * }</pre>
 */
public interface ExtendedHorizonsAPI {

    /**
     * Gets all fake chunks currently loaded for a player.
     *
     * @param player The player
     * @return Immutable set of chunk coordinates
     */
    @NotNull
    Set<ChunkCoordinate> getFakeChunksForPlayer(@NotNull Player player);

    /**
     * Checks if a specific chunk is a fake chunk for a player.
     *
     * @param player The player
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return true if the chunk is a fake chunk for this player
     */
    boolean isFakeChunk(@NotNull Player player, int chunkX, int chunkZ);

    /**
     * Gets the number of fake chunks currently loaded for a player.
     *
     * @param player The player
     * @return Number of fake chunks
     */
    int getFakeChunkCount(@NotNull Player player);

    /**
     * Clears all fake chunks for a player.
     * This will send unload packets to the client.
     *
     * @param player The player
     */
    void clearFakeChunks(@NotNull Player player);

    /**
     * Refreshes fake chunks for a player.
     * This will recalculate and resend chunks based on current view distance.
     *
     * @param player The player
     */
    void refreshFakeChunks(@NotNull Player player);

    /**
     * Gets the total size of the packet cache.
     *
     * @return Number of cached chunks
     */
    int getCacheSize();

    /**
     * Gets the cache hit rate as a percentage.
     *
     * @return Hit rate from 0.0 to 100.0
     */
    double getCacheHitRate();

    /**
     * Gets the estimated memory usage in megabytes.
     *
     * @return Estimated memory usage in MB
     */
    double getEstimatedMemoryUsageMB();

    /**
     * Checks if fake chunks are enabled for a specific world.
     *
     * @param worldName The world name
     * @return true if fake chunks are enabled for this world
     */
    boolean isFakeChunksEnabledForWorld(@NotNull String worldName);
}
