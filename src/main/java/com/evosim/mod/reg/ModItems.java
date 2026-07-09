package com.evosim.mod.reg;

import com.evosim.mod.EvoSimMod;
import com.evosim.mod.item.TraitScannerItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 아이템 등록 — 미믹 스폰에그(§2) + 특성 검사봉(§14). */
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, EvoSimMod.MODID);

    public static final RegistryObject<Item> MIMIC_SPAWN_EGG = ITEMS.register("mimic_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.MIMIC, 0x8B5A2B, 0xF5C6A0, new Item.Properties()));

    /** 미믹 우클릭 → 보유 특성 표시. */
    public static final RegistryObject<Item> TRAIT_SCANNER = ITEMS.register("trait_scanner",
            () -> new TraitScannerItem(new Item.Properties().stacksTo(1)));

    private ModItems() {
    }
}
