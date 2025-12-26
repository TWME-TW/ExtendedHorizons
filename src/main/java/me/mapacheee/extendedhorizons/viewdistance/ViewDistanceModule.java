package me.mapacheee.extendedhorizons.viewdistance;

import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.thewinterframework.module.annotation.ModuleComponent;
import com.thewinterframework.plugin.WinterPlugin;
import com.thewinterframework.plugin.module.PluginModule;
import me.mapacheee.extendedhorizons.viewdistance.service.strategy.ChunkLoadStrategy;
import me.mapacheee.extendedhorizons.viewdistance.service.strategy.ProgressiveChunkLoadStrategy;
import me.mapacheee.extendedhorizons.viewdistance.service.nms.NMSChunkAccess;
import me.mapacheee.extendedhorizons.viewdistance.service.nms.NMSPacketAccess;
import me.mapacheee.extendedhorizons.viewdistance.service.nms.NMSPlayerAccess;
import me.mapacheee.extendedhorizons.viewdistance.service.nms.v1_21_R1.NMSChunkAccess_v1_21_R1;
import me.mapacheee.extendedhorizons.viewdistance.service.nms.v1_21_R1.NMSPacketAccess_v1_21_R1;
import me.mapacheee.extendedhorizons.viewdistance.service.nms.v1_21_R1.NMSPlayerAccess_v1_21_R1;

@ModuleComponent
public class ViewDistanceModule implements PluginModule {
    @Override
    public boolean onLoad(WinterPlugin plugin) {
        return true;
    }

    @Override
    public boolean onEnable(WinterPlugin plugin) {
        return true;
    }

    @Override
    public boolean onDisable(WinterPlugin plugin) {
        return true;
    }

    @Provides
    @Singleton
    public ChunkLoadStrategy provideChunkLoadStrategy(ProgressiveChunkLoadStrategy strategy) {
        return strategy;
    }

    @Provides
    @Singleton
    public NMSChunkAccess provideNMSChunkAccess(
            NMSChunkAccess_v1_21_R1 impl) {
        return impl;
    }

    @Provides
    @Singleton
    public NMSPacketAccess provideNMSPacketAccess(
            NMSPacketAccess_v1_21_R1 impl) {
        return impl;
    }

    @Provides
    @Singleton
    public NMSPlayerAccess provideNMSPlayerAccess(
            NMSPlayerAccess_v1_21_R1 impl) {
        return impl;
    }
}
