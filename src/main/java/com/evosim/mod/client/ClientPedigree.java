package com.evosim.mod.client;

import com.evosim.mod.gui.PedigreeSnapshot;
import net.minecraft.client.Minecraft;

/** 클라 전용 — 가계도 스냅샷 수신 시 화면을 열거나(첫 조회) 갱신한다(노드 클릭 재조회 응답). */
public final class ClientPedigree {

    private ClientPedigree() {
    }

    public static void open(PedigreeSnapshot snapshot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PedigreeScreen open) {
            open.setSnapshot(snapshot);
        } else {
            mc.setScreen(new PedigreeScreen(snapshot));
        }
    }
}
