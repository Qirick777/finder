package com.evosim.mod.reg;

import com.evosim.mod.EvoSimMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** 크리에이티브 탭 — "미믹 도구": 모드의 관찰·디버그 도구를 한 탭에 모은다(§14 관찰 계열). */
public final class ModTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, EvoSimMod.MODID);

    public static final RegistryObject<CreativeModeTab> MIMIC_TOOLS = TABS.register("mimic_tools",
            () -> CreativeModeTab.builder()
                    .title(Component.literal("미믹 도구"))
                    .icon(() -> new ItemStack(ModItems.TRAIT_SCANNER.get()))
                    .displayItems((params, output) -> {
                        output.accept(ModItems.MIMIC_SPAWN_EGG.get());
                        output.accept(ModItems.TRAIT_SCANNER.get());
                        output.accept(ModItems.SIM_CLOCK.get());
                        output.accept(ModItems.TRAIT_EDITOR.get());
                        output.accept(ModItems.LAND_DEED.get());
                    })
                    .build());

    private ModTabs() {
    }
}
