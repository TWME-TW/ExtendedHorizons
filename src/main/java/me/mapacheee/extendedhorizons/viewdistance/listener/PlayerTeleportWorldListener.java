package me.mapacheee.extendedhorizons.viewdistance.listener;

import com.google.inject.Inject;
import com.thewinterframework.paper.listener.ListenerComponent;
import me.mapacheee.extendedhorizons.viewdistance.service.ViewDistanceService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import me.mapacheee.extendedhorizons.viewdistance.service.FakeChunkService;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import me.mapacheee.extendedhorizons.api.event.FakeChunkUnloadEvent;
import me.mapacheee.extendedhorizons.viewdistance.service.PacketService;
import me.mapacheee.extendedhorizons.shared.service.ConfigService;

/*
 * Handles teleports and world changes to resync cache center/radius and prefetch
 */
@ListenerComponent
public class PlayerTeleportWorldListener implements Listener {

    private final ViewDistanceService viewDistanceService;
    private final FakeChunkService fakeChunkService;
    private final ConfigService configService;

    @Inject
    public PlayerTeleportWorldListener(ViewDistanceService viewDistanceService,
            FakeChunkService fakeChunkService,
            ConfigService configService) {
        this.viewDistanceService = viewDistanceService;
        this.fakeChunkService = fakeChunkService;
        this.configService = configService;
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        boolean isSameWorld = event.getFrom().getWorld().equals(event.getTo().getWorld());
        fakeChunkService.cleanupPlayer(event.getPlayer(), isSameWorld);

        event.getPlayer().getScheduler().run(
                ExtendedHorizonsPlugin.getPlugin(
                        ExtendedHorizonsPlugin.class),
                (task) -> {
                    if (event.getPlayer().isOnline()) {
                        event.getPlayer().getScheduler().runDelayed(
                                ExtendedHorizonsPlugin.getPlugin(
                                        ExtendedHorizonsPlugin.class),
                                (innerTask) -> {
                                    if (event.getPlayer().isOnline()) {
                                        viewDistanceService.updatePlayerView(event.getPlayer());
                                        var view = viewDistanceService.getPlayerView(event.getPlayer().getUniqueId());
                                        if (view != null) {
                                            fakeChunkService.onPlayerJoin(event.getPlayer());
                                        }
                                    }
                                },
                                null, getDelayTicks());
                    }
                },
                null);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        fakeChunkService.cleanupPlayer(event.getPlayer(), false,
                FakeChunkUnloadEvent.UnloadReason.WORLD_CHANGE);

        ExtendedHorizonsPlugin.getService(
                PacketService.class)
                .resetPlayer(event.getPlayer());

        event.getPlayer().getScheduler().run(
                ExtendedHorizonsPlugin.getPlugin(
                        ExtendedHorizonsPlugin.class),
                (task) -> {
                    if (event.getPlayer().isOnline()) {
                        event.getPlayer().getScheduler().runDelayed(
                                ExtendedHorizonsPlugin.getPlugin(
                                        ExtendedHorizonsPlugin.class),
                                (innerTask) -> {
                                    if (event.getPlayer().isOnline()) {
                                        viewDistanceService.updatePlayerView(event.getPlayer());
                                        fakeChunkService.onPlayerJoin(event.getPlayer());
                                    }
                                },
                                null, getDelayTicks());
                    }
                },
                null);
    }

    private long getDelayTicks() {
        long delayMs = configService.get().performance().teleportWarmupDelay();
        if (delayMs <= 0)
            return 5L;
        return Math.max(5L, delayMs / 50);
    }
}
