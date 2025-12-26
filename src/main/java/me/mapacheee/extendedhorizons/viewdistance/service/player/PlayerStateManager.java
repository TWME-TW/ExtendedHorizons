package me.mapacheee.extendedhorizons.viewdistance.service.player;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the lifecycle of PlayerChunkState instances.
 * 
 * This service provides centralized management of player states,
 * ensuring proper creation, retrieval, and cleanup.
 * 
 * Thread-safe: All operations are safe for concurrent access.
 * 
 * @since 2.3.0
 */
@Service
public class PlayerStateManager {

    private static final Logger logger = LoggerFactory.getLogger(PlayerStateManager.class);
    private static final boolean DEBUG = false;

    /**
     * Map of player UUIDs to their chunk states.
     * This replaces 15+ separate ConcurrentHashMaps in FakeChunkService.
     */
    private final Map<UUID, PlayerChunkState> playerStates = new ConcurrentHashMap<>();

    @Inject
    public PlayerStateManager() {
    }

    // === State Management ===

    /**
     * Gets or creates a PlayerChunkState for the given player.
     * 
     * @param player The player
     * @return The player's chunk state (never null)
     */
    public PlayerChunkState getOrCreate(Player player) {
        return getOrCreate(player.getUniqueId());
    }

    /**
     * Gets or creates a PlayerChunkState for the given player UUID.
     * 
     * @param playerId The player UUID
     * @return The player's chunk state (never null)
     */
    public PlayerChunkState getOrCreate(UUID playerId) {
        return playerStates.computeIfAbsent(playerId, id -> {
            if (DEBUG) {
                logger.debug("[PlayerStateManager] Creating new state for player {}", id);
            }
            return new PlayerChunkState(id);
        });
    }

    /**
     * Gets a PlayerChunkState if it exists.
     * 
     * @param player The player
     * @return Optional containing the state, or empty if not found
     */
    public Optional<PlayerChunkState> get(Player player) {
        return get(player.getUniqueId());
    }

    /**
     * Gets a PlayerChunkState if it exists.
     * 
     * @param playerId The player UUID
     * @return Optional containing the state, or empty if not found
     */
    public Optional<PlayerChunkState> get(UUID playerId) {
        return Optional.ofNullable(playerStates.get(playerId));
    }

    /**
     * Checks if a player has a state registered.
     * 
     * @param playerId The player UUID
     * @return true if the player has a state
     */
    public boolean hasState(UUID playerId) {
        return playerStates.containsKey(playerId);
    }

    /**
     * Removes and cleans up a player's state.
     * 
     * This performs atomic cleanup of all player data.
     * Should be called when a player quits or changes worlds.
     * 
     * @param player The player to remove
     * @return The removed state, or null if none existed
     */
    public PlayerChunkState remove(Player player) {
        return remove(player.getUniqueId());
    }

    /**
     * Removes and cleans up a player's state.
     * 
     * This performs atomic cleanup of all player data.
     * Should be called when a player quits or changes worlds.
     * 
     * @param playerId The player UUID to remove
     * @return The removed state, or null if none existed
     */
    public PlayerChunkState remove(UUID playerId) {
        PlayerChunkState state = playerStates.remove(playerId);
        if (state != null) {
            state.clear();
            if (DEBUG) {
                logger.debug("[PlayerStateManager] Removed state for player {}", playerId);
            }
        }
        return state;
    }

    /**
     * Clears all player states.
     * Should be called on plugin disable.
     */
    public void clearAll() {
        logger.info("[PlayerStateManager] Clearing {} player states", playerStates.size());
        playerStates.values().forEach(PlayerChunkState::clear);
        playerStates.clear();
    }

    // === Warmup Management ===
    // Moved to WarmupManager.java

    // === Tick Management ===

    /**
     * Resets per-tick counters for all players.
     * Should be called at the start of each processing tick.
     */
    public void resetTickCounters() {
        playerStates.values().forEach(PlayerChunkState::resetTickCounters);
    }

    /**
     * Resets per-second counters for players when needed.
     * Should be called periodically during packet processing.
     */
    public void resetSecondCountersIfNeeded() {
        playerStates.values().forEach(state -> {
            if (state.shouldResetSecondCounters()) {
                state.resetSecondCounters();
            }
        });
    }

    // === Statistics ===

    /**
     * Gets the total number of tracked players.
     */
    public int getTrackedPlayerCount() {
        return playerStates.size();
    }

    /**
     * Gets the total number of fake chunks across all players.
     */
    public int getTotalFakeChunks() {
        return playerStates.values().stream()
                .mapToInt(PlayerChunkState::getFakeChunkCount)
                .sum();
    }

    /**
     * Gets the total number of queued chunks across all players.
     */
    public int getTotalQueuedChunks() {
        return playerStates.values().stream()
                .mapToInt(PlayerChunkState::getQueuedChunkCount)
                .sum();
    }

    /**
     * Gets the total number of pending packets across all players.
     */
    public int getTotalPendingPackets() {
        return playerStates.values().stream()
                .mapToInt(PlayerChunkState::getPendingPacketCount)
                .sum();
    }

    /**
     * Gets statistics for a specific player.
     * 
     * @param player The player
     * @return Statistics string, or "No data" if player not tracked
     */
    public String getPlayerStats(Player player) {
        return get(player)
                .map(PlayerChunkState::toString)
                .orElse("No data for player " + player.getName());
    }

    /**
     * Gets all tracked player UUIDs.
     * Useful for iterating over all players.
     */
    public Set<UUID> getAllPlayerIds() {
        return playerStates.keySet();
    }

    /**
     * Gets summary statistics for all players.
     */
    public String getSummaryStats() {
        return String.format(
                "PlayerStateManager: %d players, %d fake chunks, %d queued, %d pending packets",
                getTrackedPlayerCount(),
                getTotalFakeChunks(),
                getTotalQueuedChunks(),
                getTotalPendingPackets());
    }
}
