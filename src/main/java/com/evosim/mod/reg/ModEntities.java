package com.evosim.mod.reg;

import com.evosim.mod.EvoSimMod;
import com.evosim.mod.entity.MimicEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 엔티티 등록 (Forge DeferredRegister). */
public final class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, EvoSimMod.MODID);

    public static final RegistryObject<EntityType<MimicEntity>> MIMIC = ENTITIES.register("mimic",
            () -> EntityType.Builder.of(MimicEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F)          // 플레이어 크기(성년 기준). 단계별 축소는 getDimensions.
                    .clientTrackingRange(10)
                    .build("mimic"));

    private ModEntities() {
    }
}
