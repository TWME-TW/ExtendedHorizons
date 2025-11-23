package me.mapacheee.extendedhorizons.shared.scheduler;

import com.thewinterframework.service.annotation.Service;
import com.google.inject.Inject;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import me.mapacheee.extendedhorizons.ExtendedHorizonsPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class SchedulerService implements PlatformScheduler {
    private static final Logger logger = LoggerFactory.getLogger(SchedulerService.class);
    private final PlatformScheduler delegate;

    @Inject
    public SchedulerService() {
        ExtendedHorizonsPlugin plugin = org.bukkit.plugin.java.JavaPlugin.getPlugin(ExtendedHorizonsPlugin.class);
        if (isFolia()) {
            this.delegate = new FoliaScheduler(plugin);
            logger.info("Folia detected! Using FoliaScheduler.");
        } else {
            this.delegate = new PaperScheduler(plugin);
            logger.info("Using standard PaperScheduler.");
        }
    }

    private boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public void run(Runnable task) {
        delegate.run(task);
    }

    @Override
    public void runLater(Runnable task, long delayTicks) {
        delegate.runLater(task, delayTicks);
    }

    @Override
    public void runTimer(Runnable task, long delayTicks, long periodTicks) {
        delegate.runTimer(task, delayTicks, periodTicks);
    }

    @Override
    public void runAsync(Runnable task) {
        delegate.runAsync(task);
    }

    @Override
    public void runAsyncLater(Runnable task, long delayTicks) {
        delegate.runAsyncLater(task, delayTicks);
    }

    @Override
    public void runAsyncTimer(Runnable task, long delayTicks, long periodTicks) {
        delegate.runAsyncTimer(task, delayTicks, periodTicks);
    }

    @Override
    public void runRegion(Location location, Runnable task) {
        delegate.runRegion(location, task);
    }

    @Override
    public void runRegionLater(Location location, Runnable task, long delayTicks) {
        delegate.runRegionLater(location, task, delayTicks);
    }

    @Override
    public void runEntity(Entity entity, Runnable task) {
        delegate.runEntity(entity, task);
    }

    @Override
    public void runEntityLater(Entity entity, Runnable task, long delayTicks) {
        delegate.runEntityLater(entity, task, delayTicks);
    }
}
