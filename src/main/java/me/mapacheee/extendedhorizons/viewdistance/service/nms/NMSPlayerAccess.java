package me.mapacheee.extendedhorizons.viewdistance.service.nms;

import org.bukkit.entity.Player;

public interface NMSPlayerAccess {
    /**
     * Gets the player's ping.
     */
    int getPing(Player player);
}
