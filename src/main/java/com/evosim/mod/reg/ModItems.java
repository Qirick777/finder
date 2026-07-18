package com.evosim.mod.reg;

import com.evosim.mod.EvoSimMod;
import com.evosim.mod.item.SimClockItem;
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

    /** 우클릭 → 지금 시간대 + 미믹이 지금 할 일 + 주변 인구 요약(§16). */
    public static final RegistryObject<Item> SIM_CLOCK = ITEMS.register("sim_clock",
            () -> new SimClockItem(new Item.Properties().stacksTo(1)));

    /** 미믹 우클릭 → 특성 편집 화면(추가·삭제·우성·등급). */
    public static final RegistryObject<Item> TRAIT_EDITOR = ITEMS.register("trait_editor",
            () -> new com.evosim.mod.item.TraitEditorItem(new Item.Properties().stacksTo(1)));

    /** 밭 타일 우클릭 → 그 구획의 원장 GUI(창설·소유·소작·확장 이력·수확 분배). */
    public static final RegistryObject<Item> LAND_DEED = ITEMS.register("land_deed",
            () -> new com.evosim.mod.item.LandDeedItem(new Item.Properties().stacksTo(1)));

    private ModItems() {
    }
}
