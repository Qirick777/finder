package com.evosim.mod.entity;

import com.evosim.mod.EvoSimMod;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 미믹 사냥 부수효과 (설계서: 미믹은 식량만 얻고 전리품은 남기지 않는다). 미믹이 죽인 동물·몹은
 * 아이템을 떨구지 않는다 — 사냥은 소지 식량(holding)으로만 반영되고 바닥에 잡동사니가 쌓이지 않음.
 */
@Mod.EventBusSubscriber(modid = EvoSimMod.MODID)
public final class MimicHuntEvents {

    private MimicHuntEvents() {
    }

    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        if (event.getSource().getEntity() instanceof MimicEntity) {
            event.setCanceled(true); // 미믹이 처치한 대상은 드랍 없음
        }
    }
}
