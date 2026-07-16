package com.evosim.mod.client;

import com.evosim.mod.EvoSimMod;
import com.evosim.mod.reg.ModBlocks;
import com.evosim.mod.reg.ModEntities;
import com.evosim.mod.reg.ModMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 클라이언트 전용 등록 — 렌더러 + 모델 레이어 (서버에서 클라 클래스 로딩 방지 위해 분리).
 */
@Mod.EventBusSubscriber(modid = EvoSimMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class MimicClient {

    public static final ModelLayerLocation MIMIC_WIDE_LAYER =
            new ModelLayerLocation(new ResourceLocation(EvoSimMod.MODID, "mimic"), "main");
    public static final ModelLayerLocation MIMIC_SLIM_LAYER =
            new ModelLayerLocation(new ResourceLocation(EvoSimMod.MODID, "mimic_slim"), "main");

    private MimicClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenus.EVOMATCH.get(), EvoMatchScreen::new);
            // 모닥불 모델은 컷아웃 렌더타입 필요 — 미지정 시 불꽃/투명 부분이 검게 렌더된다.
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.MIMIC_HEARTH.get(), RenderType.cutout());
        });
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.MIMIC.get(), MimicRenderer::new);
    }

    @SubscribeEvent
    public static void onRegisterOverlays(
            net.minecraftforge.client.event.RegisterGuiOverlaysEvent event) {
        // 렌즈 카드(P2) — 채팅 패널 "아래" 레이어로 등록: 채팅(심화 기록)이 항상 카드(조준 요약)
        // 위에 그려져 채팅 정보가 가려지지 않는다(UX-A). 표시 전용(서버 배선 무관).
        event.registerBelow(
                net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.CHAT_PANEL.id(),
                "scan_lens", ScanHudOverlay.INSTANCE);
    }

    @SubscribeEvent
    public static void onRegisterLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(MIMIC_WIDE_LAYER,
                () -> LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64));
        event.registerLayerDefinition(MIMIC_SLIM_LAYER,
                () -> LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, true), 64, 64));
    }
}
