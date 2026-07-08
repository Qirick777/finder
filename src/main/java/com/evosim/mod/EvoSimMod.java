package com.evosim.mod;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * EvoSim 모드 진입점 (Forge 1.20.1).
 *
 * <p>Phase 0/1 은 마크에 안 얽힌 순수 로직(설계서 §18)이고, 이 클래스는 그 로직을 게임에서
 * 호출하는 표현층일 뿐이다. 현재는 {@code /evotest} 명령어만 노출 — 실제 개체 소환/행동은 Phase 2↑.
 */
@Mod(EvoSimMod.MODID)
public final class EvoSimMod {

    public static final String MODID = "evosim";
    private static final Logger LOGGER = LogUtils.getLogger();

    public EvoSimMod() {
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("EvoSim 로드됨 — 게임에서 /evotest all 로 순수 로직 검증 가능 (Phase 0).");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        EvoTestCommand.register(event.getDispatcher());
    }
}
