package com.evosim.mod.client;

import com.evosim.mod.gui.StatsSnapshot;
import net.minecraft.client.Minecraft;

/** 클라 전용 — 인구 통계 스냅샷 수신 시 통계 화면을 연다. */
public final class ClientStats {

    private ClientStats() {
    }

    public static void open(StatsSnapshot snapshot) {
        Minecraft.getInstance().setScreen(new StatsScreen(snapshot));
    }
}
