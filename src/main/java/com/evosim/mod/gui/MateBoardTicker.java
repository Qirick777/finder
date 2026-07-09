package com.evosim.mod.gui;

import com.evosim.mod.EvoSimMod;
import com.evosim.mod.net.MateBoardPacket;
import com.evosim.mod.net.ModNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;

/** 현황판 실시간 갱신 — 현황판을 연 플레이어에게 매 1초 새 스냅샷을 밀어준다. */
@Mod.EventBusSubscriber(modid = EvoSimMod.MODID)
public final class MateBoardTicker {

    private static final int REFRESH_TICKS = 20;

    private MateBoardTicker() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getTickCount() % REFRESH_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof EvoMatchMenu) {
                ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new MateBoardPacket(MateBoardSnapshot.build(player)));
            }
        }
    }
}
