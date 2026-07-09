package com.evosim.mod.client;

import com.evosim.mod.EvoSimMod;
import com.evosim.mod.reg.ModEntities;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.MIMIC.get(), MimicRenderer::new);
    }

    @SubscribeEvent
    public static void onRegisterLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(MIMIC_WIDE_LAYER,
                () -> LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64));
        event.registerLayerDefinition(MIMIC_SLIM_LAYER,
                () -> LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, true), 64, 64));
    }
}
