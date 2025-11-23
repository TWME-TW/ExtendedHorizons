package me.mapacheee.extendedhorizons.shared.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

public class FoliaScheduler implements PlatformScheduler {
    private final Plugin plugin;

    public FoliaScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run(Runnable task) {
        Bukkit.getGlobalRegionScheduler().run(plugin, (t) -> task.run());
    }

    @Override
    public void runLater(Runnable task, long delayTicks) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, (t) -> task.run(), delayTicks);
    }

    @Override
    public void runTimer(Runnable task, long delayTicks, long periodTicks) {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, (t) -> task.run(), delayTicks, periodTicks);
    }

    @Override
    public void runAsync(Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, (t) -> task.run());
    }

    @Override
    public void runAsyncLater(Runnable task, long delayTicks) {
        long delayMs = delayTicks * 50;
        Bukkit.getAsyncScheduler().runDelayed(plugin, (t) -> task.run(), delayMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void runAsyncTimer(Runnable task, long delayTicks, long periodTicks) {
        long delayMs = delayTicks * 50;
        long periodMs = periodTicks * 50;
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, (t) -> task.run(), delayMs, periodMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void runRegion(Location location, Runnable task) {
        Bukkit.getRegionScheduler().run(plugin, location, (t) -> task.run());
    }

    @Override
    public void runRegionLater(Location location, Runnable task, long delayTicks) {
        Bukkit.getRegionScheduler().runDelayed(plugin, location, (t) -> task.run(), delayTicks);
    }

    @Override
    public void runEntity(Entity entity, Runnable task) {
        entity.getScheduler().run(plugin, (t) -> task.run(), null);
    }

    @Override
    public void runEntityLater(Entity entity, Runnable task, long delayTicks) {
        entity.getScheduler().runDelayed(plugin, (t) -> task.run(), null, delayTicks);
    }
}
