package me.mapacheee.extendedhorizons.api.event;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Called when a batch of fake chunks is about to be processed for a player.
 * This event is cancellable - if cancelled, the entire batch will be skipped.
 */
public class FakeChunkBatchLoadEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private boolean cancelled = false;

    private final Player player;
    private final List<ChunkCoordinate> chunks;
    private final World world;

    /**
     * Creates a new FakeChunkBatchLoadEvent.
     *
     * @param player The player receiving the chunks
     * @param chunks The list of chunk coordinates in this batch
     * @param world  The world containing the chunks
     */
    public FakeChunkBatchLoadEvent(@NotNull Player player, @NotNull List<ChunkCoordinate> chunks,
            @NotNull World world) {
        this.player = player;
        this.chunks = List.copyOf(chunks); // immutable copy
        this.world = world;
    }

    /**
     * Gets the player receiving the chunks.
     *
     * @return The player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * Gets an immutable list of chunk coordinates in this batch.
     *
     * @return List of chunk coordinates
     */
    @NotNull
    public List<ChunkCoordinate> getChunks() {
        return chunks;
    }

    /**
     * Gets the total number of chunks in this batch.
     *
     * @return Number of chunks
     */
    public int getTotalChunks() {
        return chunks.size();
    }

    /**
     * Gets the world containing the chunks.
     *
     * @return The world
     */
    @NotNull
    public World getWorld() {
        return world;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
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
     * Represents a chunk coordinate (X, Z).
     */
    public static class ChunkCoordinate {
        private final int x;
        private final int z;

        public ChunkCoordinate(int x, int z) {
            this.x = x;
            this.z = z;
        }

        public int getX() {
            return x;
        }

        public int getZ() {
            return z;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof ChunkCoordinate))
                return false;
            ChunkCoordinate that = (ChunkCoordinate) o;
            return x == that.x && z == that.z;
        }

        @Override
        public int hashCode() {
            return 31 * x + z;
        }

        @Override
        public String toString() {
            return "ChunkCoordinate{x=" + x + ", z=" + z + '}';
        }
    }
}
