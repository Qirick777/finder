package com.evosim.mod.gui;

import com.evosim.mod.reg.ModMenus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 짝매칭 현황판 메뉴 (상자처럼 열리는 컨테이너 GUI, 슬롯 없음). 데이터는 {@link MateBoardSnapshot}로
 * 들고 있고, 열려 있는 동안 서버가 주기적으로 새 스냅샷을 밀어 갱신한다(실시간).
 */
public class EvoMatchMenu extends AbstractContainerMenu {

    private MateBoardSnapshot snapshot;

    /** 서버측 생성. */
    public EvoMatchMenu(int windowId, MateBoardSnapshot snapshot) {
        super(ModMenus.EVOMATCH.get(), windowId);
        this.snapshot = snapshot;
    }

    /** 클라측 생성(네트워크 factory) — 버퍼에서 스냅샷 복원. */
    public EvoMatchMenu(int windowId, FriendlyByteBuf buf) {
        super(ModMenus.EVOMATCH.get(), windowId);
        this.snapshot = MateBoardSnapshot.decode(buf);
    }

    public MateBoardSnapshot snapshot() {
        return snapshot;
    }

    public void setSnapshot(MateBoardSnapshot s) {
        this.snapshot = s;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY; // 슬롯 없음
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
