package me.mapacheee.extendedhorizons.viewdistance.service.nms;

import org.bukkit.World;

public interface NMSChunkAccess {
    /**
     * Gets a chunk from server memory if it exists, without triggering a load.
     * 
     * @return The NMS chunk object, or null.
     */
    Object getChunkIfLoaded(World world, int x, int z);

    /**
     * Checks if a chunk object is valid and loaded.
     */
    boolean isChunkLoaded(Object chunk);

    /**
     * Gets a ChunkAccess from a Bukkit Chunk.
     * 
     * @return The NMS ChunkAccess object (usually LevelChunk).
     */
    Object getNMSChunk(org.bukkit.Chunk chunk);

    /**
     * Clones a chunk (shallow copy of sections for obfuscation).
     */
    Object cloneChunk(Object chunk);

    /**
     * Applies anti-xray obfuscation to a chunk.
     */
    void obfuscateChunk(Object chunk, boolean hideOres, boolean addFakeOres, double density);
}
