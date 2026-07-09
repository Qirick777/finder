package com.evosim.mod.client;

import com.evosim.mod.gui.EvoMatchMenu;
import com.evosim.mod.gui.MateBoardSnapshot;
import net.minecraft.client.Minecraft;

/** 클라 전용 — 열려 있는 현황판 화면에 새 스냅샷을 반영(실시간 갱신). */
public final class ClientMateBoard {

    private ClientMateBoard() {
    }

    public static void update(MateBoardSnapshot snapshot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof EvoMatchScreen board) {
            board.getMenu().setSnapshot(snapshot);
            board.onSnapshotUpdated();
        }
    }
}
