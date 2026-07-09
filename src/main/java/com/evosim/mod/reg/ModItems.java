package com.evosim.mod.reg;

import com.evosim.mod.EvoSimMod;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 아이템 등록 — 미믹 스폰에그 (설계서 §2: 개체는 스폰에그/인위적 방식으로만 생성). */
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, EvoSimMod.MODID);

    public static final RegistryObject<Item> MIMIC_SPAWN_EGG = ITEMS.register("mimic_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.MIMIC, 0x8B5A2B, 0xF5C6A0, new Item.Properties()));

    private ModItems() {
    }
}
