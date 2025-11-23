package me.mapacheee.extendedhorizons.shared.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

public class PaperScheduler implements PlatformScheduler {
    private final Plugin plugin;

    public PaperScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public void runLater(Runnable task, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    @Override
    public void runTimer(Runnable task, long delayTicks, long periodTicks) {
        Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
    }

    @Override
    public void runAsync(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public void runAsyncLater(Runnable task, long delayTicks) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks);
    }

    @Override
    public void runAsyncTimer(Runnable task, long delayTicks, long periodTicks) {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
    }

    @Override
    public void runRegion(Location location, Runnable task) {
        // Paper (non-folia) runs everything on main thread, so this is just a normal
        // run
        run(task);
    }

    @Override
    public void runRegionLater(Location location, Runnable task, long delayTicks) {
        runLater(task, delayTicks);
    }

    @Override
    public void runEntity(Entity entity, Runnable task) {
        run(task);
    }

    @Override
    public void runEntityLater(Entity entity, Runnable task, long delayTicks) {
        runLater(task, delayTicks);
    }
}
