package com.evosim.mod.reg;

import com.evosim.mod.EvoSimMod;
import com.evosim.mod.gui.EvoMatchMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 메뉴(컨테이너 GUI) 등록 — 짝매칭 현황판. */
public final class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, EvoSimMod.MODID);

    public static final RegistryObject<MenuType<EvoMatchMenu>> EVOMATCH = MENUS.register("evomatch",
            () -> IForgeMenuType.create((windowId, inv, buf) -> new EvoMatchMenu(windowId, buf)));

    private ModMenus() {
    }
}
