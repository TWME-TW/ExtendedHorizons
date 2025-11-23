package me.mapacheee.extendedhorizons.shared.scheduler;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

public interface PlatformScheduler {
    void run(Runnable task);

    void runLater(Runnable task, long delayTicks);

    void runTimer(Runnable task, long delayTicks, long periodTicks);

    void runAsync(Runnable task);

    void runAsyncLater(Runnable task, long delayTicks);

    void runAsyncTimer(Runnable task, long delayTicks, long periodTicks);

    void runRegion(Location location, Runnable task);

    void runRegionLater(Location location, Runnable task, long delayTicks);

    void runEntity(Entity entity, Runnable task);

    void runEntityLater(Entity entity, Runnable task, long delayTicks);
}
