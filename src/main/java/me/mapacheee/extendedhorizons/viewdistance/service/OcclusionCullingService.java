package me.mapacheee.extendedhorizons.viewdistance.service;

import com.google.inject.Inject;
import com.thewinterframework.service.annotation.Service;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;
import org.bukkit.entity.Player;

import org.bukkit.World.Environment;

@Service
public class OcclusionCullingService {

    private final ConfigService configService;

    @Inject
    public OcclusionCullingService(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * Checks if the player is occluded (underground or in a closed space)
     * based on configuration thresholds.
     *
     * @param player The player to check
     * @return true if the player is occluded and fake chunks should be hidden
     */
    public boolean isOccluded(Player player) {
        var config = configService.get().performance().occlusionCulling();

        if (config == null || !config.enabled()) {
            return false;
        }

        if (player.getWorld().getEnvironment() == Environment.NETHER
                || player.getWorld().getEnvironment() == Environment.THE_END) {
            return false;
        }

        int playerY = player.getLocation().getBlockY();
        if (playerY > config.maxYLevel() || playerY < config.minYLevel()) {
            return false;
        }

        byte skyLight = player.getEyeLocation().getBlock().getLightFromSky();
        return skyLight < config.skyLightThreshold();
    }
}
