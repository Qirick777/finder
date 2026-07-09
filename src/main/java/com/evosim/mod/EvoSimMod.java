package com.evosim.mod;

import com.evosim.mod.entity.MimicEntity;
import com.evosim.mod.reg.ModEntities;
import com.evosim.mod.reg.ModItems;
import com.mojang.logging.LogUtils;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * EvoSim 모드 진입점 (Forge 1.20.1).
 *
 * <p>순수 도메인 로직(com.evosim.core/test)은 마크에 안 얽혀 있고(§18), 이 패키지는 표현층.
 * 명령어({@code /evotest}·{@code /evodebug}·{@code /evosim})와 미믹 개체·스폰에그를 등록한다.
 */
@Mod(EvoSimMod.MODID)
public final class EvoSimMod {

    public static final String MODID = "evosim";
    private static final Logger LOGGER = LogUtils.getLogger();

    public EvoSimMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModEntities.ENTITIES.register(modBus);
        ModItems.ITEMS.register(modBus);
        modBus.addListener(this::onAttributes);
        modBus.addListener(this::onBuildTabs);

        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("EvoSim 로드됨 — /evotest, /evodebug, /evosim spawn 사용 가능 (Phase 2).");
    }

    private void onAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.MIMIC.get(), MimicEntity.createAttributes().build());
    }

    private void onBuildTabs(BuildCreativeModeTabContentsEvent event) {
        // 미믹 스폰에그를 "스폰 알" 크리에이티브 탭에 노출.
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(ModItems.MIMIC_SPAWN_EGG.get());
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        EvoTestCommand.register(event.getDispatcher());
        EvoDebugCommand.register(event.getDispatcher());
        EvoSimCommand.register(event.getDispatcher());
    }
}
