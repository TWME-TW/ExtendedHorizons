package me.mapacheee.extendedhorizons.viewdistance.service.player;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.shared.utils.ChunkUtils;
import org.bukkit.entity.Player;

/**
 * Detects if a player has moved significantly (teleported) requiring a reset of
 * fake chunks state.
 */
@Service
public class TeleportDetector {

    private static final int TELEPORT_DETECTION_THRESHOLD = 3;

    @Inject
    public TeleportDetector() {
    }

    /**
     * Checks if the player has moved significantly since the last check.
     * Updates the last known chunk position in the state.
     * 
     * @param player The player to check
     * @param state  The player's chunk state
     * @return true if significant movement (teleport) was detected
     */
    public boolean hasMovedSignificantly(Player player, PlayerChunkState state) {
        int playerChunkX = player.getLocation().getBlockX() >> 4;
        int playerChunkZ = player.getLocation().getBlockZ() >> 4;
        long currentChunkPos = ChunkUtils.packChunkKey(playerChunkX, playerChunkZ);

        long lastPos = state.getLastChunkPosition();
        boolean isTeleport = false;

        if (lastPos != 0 && lastPos != currentChunkPos) {
            int lastChunkX = ChunkUtils.unpackX(lastPos);
            int lastChunkZ = ChunkUtils.unpackZ(lastPos);
            int dx = Math.abs(lastChunkX - playerChunkX);
            int dz = Math.abs(lastChunkZ - playerChunkZ);
            isTeleport = (dx > TELEPORT_DETECTION_THRESHOLD || dz > TELEPORT_DETECTION_THRESHOLD);
        }

        state.setLastChunkPosition(currentChunkPos);

        return isTeleport;
    }
}
