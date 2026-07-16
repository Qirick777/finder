package com.evosim.mod.client;

import com.evosim.mod.EvoSimMod;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 고정 발광 하트비트 (클라 전용, UX-D) — 고정 중 20틱마다 서버 GlowKeeper 만료를 연장한다. */
@Mod.EventBusSubscriber(modid = EvoSimMod.MODID, value = Dist.CLIENT)
public final class PinGlowHeartbeat {

    private static final int INTERVAL = 20;
    private static int ticks;

    private PinGlowHeartbeat() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || Minecraft.getInstance().player == null) {
            return;
        }
        if (++ticks % INTERVAL == 0) {
            ClientScanCache.glowHeartbeat();
        }
    }
}
