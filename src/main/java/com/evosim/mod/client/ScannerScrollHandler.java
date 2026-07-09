package com.evosim.mod.client;

import com.evosim.mod.EvoSimMod;
import com.evosim.mod.item.TraitScannerItem;
import com.evosim.mod.net.CycleScannerModePacket;
import com.evosim.mod.net.ModNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 검사봉 스크롤 모드 전환 (클라 전용, 설계서 §14). 쉬프트 + 스크롤 시 모드 순환 패킷 전송 + 핫바 스크롤 취소.
 * (쉬프트 없이는 평소대로 핫바 전환.)
 */
@Mod.EventBusSubscriber(modid = EvoSimMod.MODID, value = Dist.CLIENT)
public final class ScannerScrollHandler {

    private ScannerScrollHandler() {
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !player.isShiftKeyDown()) {
            return;
        }
        boolean holding = player.getMainHandItem().getItem() instanceof TraitScannerItem
                || player.getOffhandItem().getItem() instanceof TraitScannerItem;
        if (!holding) {
            return;
        }
        double delta = event.getScrollDelta();
        if (delta == 0) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new CycleScannerModePacket(delta > 0 ? 1 : -1));
        event.setCanceled(true);
    }
}
