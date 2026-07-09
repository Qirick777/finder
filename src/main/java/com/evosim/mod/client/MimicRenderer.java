package com.evosim.mod.client;

import com.evosim.core.LifeStage;
import com.evosim.mod.entity.MimicEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * 미믹 렌더러 — 플레이어 모델. 성별로 스티브(기본)/알렉스(슬림) 구분, 생애단계로 크기·비율 구분.
 *
 * <p>유아: {@code young=true}(아기 비율, 머리 큼) + 작게. 소년: 키 작게. 성년: 기본.
 */
public class MimicRenderer extends MobRenderer<MimicEntity, PlayerModel<MimicEntity>> {

    // 마크 기본 스킨 (1.20.1: wide=스티브 / slim=알렉스).
    private static final ResourceLocation STEVE =
            new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");
    private static final ResourceLocation ALEX =
            new ResourceLocation("minecraft", "textures/entity/player/slim/alex.png");

    private final PlayerModel<MimicEntity> wide;
    private final PlayerModel<MimicEntity> slim;

    public MimicRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel<>(ctx.bakeLayer(MimicClient.MIMIC_WIDE_LAYER), false), 0.5F);
        this.wide = this.getModel();
        this.slim = new PlayerModel<>(ctx.bakeLayer(MimicClient.MIMIC_SLIM_LAYER), true);
    }

    @Override
    public ResourceLocation getTextureLocation(MimicEntity entity) {
        return entity.isFemale() ? ALEX : STEVE;
    }

    @Override
    public void render(MimicEntity entity, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int light) {
        // 성별별 모델 교체(슬림/기본) + 유아 비율.
        this.model = entity.isFemale() ? slim : wide;
        this.model.young = entity.getStage() == LifeStage.INFANT;
        super.render(entity, yaw, partialTick, pose, buffer, light);
    }

    @Override
    protected void scale(MimicEntity entity, PoseStack pose, float partialTick) {
        float s = entity.getStage().modelScale();
        pose.scale(s, s, s);
    }
}
