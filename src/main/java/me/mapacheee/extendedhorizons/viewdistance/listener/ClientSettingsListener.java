package me.mapacheee.extendedhorizons.viewdistance.listener;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import com.destroystokyo.paper.event.player.PlayerClientOptionsChangeEvent;

@ListenerComponent
public class ClientSettingsListener implements Listener {

    private final ViewDistanceService viewDistanceService;

    @Inject
    public ClientSettingsListener(ViewDistanceService viewDistanceService) {
        this.viewDistanceService = viewDistanceService;
    }

    @EventHandler
    public void onClientOptionsChange(PlayerClientOptionsChangeEvent event) {
        if (event.getPlayer() == null)
            return;

        if (event.hasViewDistanceChanged()) {
            int newDistance = event.getViewDistance();
            try {
                viewDistanceService.setPlayerDistance(event.getPlayer(), newDistance);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }
}
