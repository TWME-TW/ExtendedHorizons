package me.mapacheee.extendedhorizons.viewdistance.service.nms.v1_21_R1;

import me.mapacheee.extendedhorizons.viewdistance.service.nms.NMSPlayerAccess;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import com.thewinterframework.service.annotation.Service;

@Service
public class NMSPlayerAccess_v1_21_R1 implements NMSPlayerAccess {

    @Override
    public int getPing(Player player) {
        return ((CraftPlayer) player).getHandle().connection.latency();
    }
}
