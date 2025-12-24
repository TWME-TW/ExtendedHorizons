package me.mapacheee.extendedhorizons.api.event;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Called when a fake chunk is unloaded from a player.
 * This event is not cancellable - it is purely informational.
 */
public class FakeChunkUnloadEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final int chunkX;
    private final int chunkZ;
    private final World world;
    private final UnloadReason reason;

    /**
     * Creates a new FakeChunkUnloadEvent.
     *
     * @param player The player from whom the chunk is being unloaded
     * @param chunkX The X coordinate of the chunk
     * @param chunkZ The Z coordinate of the chunk
     * @param world  The world containing the chunk
     * @param reason The reason for unloading
     */
    public FakeChunkUnloadEvent(@NotNull Player player, int chunkX, int chunkZ,
            @NotNull World world, @NotNull UnloadReason reason) {
        this.player = player;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.world = world;
        this.reason = reason;
    }

    /**
     * Gets the player from whom the chunk is being unloaded.
     *
     * @return The player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * Gets the X coordinate of the chunk.
     *
     * @return Chunk X coordinate
     */
    public int getChunkX() {
        return chunkX;
    }

    /**
     * Gets the Z coordinate of the chunk.
     *
     * @return Chunk Z coordinate
     */
    public int getChunkZ() {
        return chunkZ;
    }

    /**
     * Gets the world containing the chunk.
     *
     * @return The world
     */
    @NotNull
    public World getWorld() {
        return world;
    }

    /**
     * Gets the reason why the chunk is being unloaded.
     *
     * @return The unload reason
     */
    @NotNull
    public UnloadReason getReason() {
        return reason;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    /**
     * Represents the reason why a fake chunk is being unloaded.
     */
    public enum UnloadReason {
        /**
         * Player quit the server.
         */
        PLAYER_QUIT,

        /**
         * Player changed worlds.
         */
        WORLD_CHANGE,

        /**
         * Chunk is too far from player.
         */
        DISTANCE,

        /**
         * Manual unload (API call or command).
         */
        MANUAL
    }
}
