package me.mapacheee.extendedhorizons.viewdistance.listener;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/*
 * Handles teleports and world changes to resync cache center/radius and prefetch
 */
@ListenerComponent
public class PlayerTeleportWorldListener implements Listener {

    private final ViewDistanceService viewDistanceService;
    private final me.mapacheee.extendedhorizons.viewdistance.service.FakeChunkService fakeChunkService;

    @Inject
    public PlayerTeleportWorldListener(ViewDistanceService viewDistanceService,
            me.mapacheee.extendedhorizons.viewdistance.service.FakeChunkService fakeChunkService) {
        this.viewDistanceService = viewDistanceService;
        this.fakeChunkService = fakeChunkService;
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        fakeChunkService.cleanupPlayer(event.getPlayer());

        event.getPlayer().getScheduler().run(
                me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.getPlugin(
                        me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.class),
                (task) -> {
                    if (event.getPlayer().isOnline()) {
                        event.getPlayer().getScheduler().runDelayed(
                                me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.getPlugin(
                                        me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.class),
                                (innerTask) -> {
                                    if (event.getPlayer().isOnline()) {
                                        viewDistanceService.updatePlayerView(event.getPlayer());
                                        var view = viewDistanceService.getPlayerView(event.getPlayer().getUniqueId());
                                        if (view != null) {
                                            fakeChunkService.onPlayerJoin(event.getPlayer());
                                        }
                                    }
                                },
                                null, 40L);
                    }
                },
                null);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        fakeChunkService.cleanupPlayer(event.getPlayer());

        org.bukkit.Bukkit.getScheduler().runTask(
                me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.getPlugin(
                        me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.class),
                () -> {
                    if (event.getPlayer().isOnline()) {
                        org.bukkit.Bukkit.getScheduler().runTaskLater(
                                me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.getPlugin(
                                        me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin.class),
                                () -> {
                                    if (event.getPlayer().isOnline()) {
                                        viewDistanceService.updatePlayerView(event.getPlayer());
                                    }
                                },
                                40L);
                    }
                });
    }
}
