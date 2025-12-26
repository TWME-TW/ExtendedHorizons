package me.mapacheee.extendedhorizons.viewdistance.service.nms.v1_21_R1;

import me.mapacheee.extendedhorizons.viewdistance.service.nms.NMSPacketAccess;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import com.thewinterframework.service.annotation.Service;
import java.util.BitSet;

@Service
public class NMSPacketAccess_v1_21_R1 implements NMSPacketAccess {

    @Override
    public Object createChunkPacket(Object chunk) {
        if (!(chunk instanceof LevelChunk))
            return null;

        LevelChunk nmsChunk = (LevelChunk) chunk;
        LevelLightEngine lightEngine = nmsChunk.getLevel().getLightEngine();
        int sectionCount = nmsChunk.getSections().length;
        BitSet[] lightMasks = getLightMasks(sectionCount);

        @SuppressWarnings("deprecation")
        ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(
                nmsChunk,
                lightEngine,
                lightMasks[0],
                lightMasks[1]);
        return packet;
    }

    @Override
    public Object createUnloadPacket(int x, int z) {
        return new ClientboundForgetLevelChunkPacket(new ChunkPos(x, z));
    }

    @Override
    public void sendPacket(Player player, Object packet) {
        if (packet instanceof net.minecraft.network.protocol.Packet) {
            ((CraftPlayer) player).getHandle().connection.send((net.minecraft.network.protocol.Packet<?>) packet);
        }
    }

    @Override
    public int getPacketSize(Object packet) {
        if (packet instanceof ClientboundLevelChunkWithLightPacket) {
            return -1;
        }
        return 512;
    }

    @Override
    public Object createChunkCacheRadiusPacket(int radius) {
        return new ClientboundSetChunkCacheRadiusPacket(radius);
    }

    @Override
    public Object createChunkCacheCenterPacket(int x, int z) {
        return new ClientboundSetChunkCacheCenterPacket(x, z);
    }

    @Override
    public Object createSimulationDistancePacket(int distance) {
        return new ClientboundSetSimulationDistancePacket(distance);
    }

    private BitSet[] getLightMasks(int sectionCount) {
        BitSet skyLight = new BitSet(sectionCount + 2);
        BitSet blockLight = new BitSet(sectionCount + 2);
        for (int i = 0; i < sectionCount + 2; i++) {
            skyLight.set(i);
            blockLight.set(i);
        }
        return new BitSet[] { skyLight, blockLight };
    }
}
