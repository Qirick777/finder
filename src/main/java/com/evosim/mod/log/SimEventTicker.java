package com.evosim.mod.log;

import com.evosim.mod.EvoSimMod;
import com.evosim.mod.entity.MimicEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 하루 1회 인구 조사 (설계서 §14 관찰). 로그가 켜져 있으면 매일 정해진 시각에 플레이어 주변 미믹을
 * 스캔해 인구·세대 스냅샷을 남긴다 → 시간축을 따라 개체군이 실제로 자라는지/줄어드는지 통째 검증.
 *
 * <p>로그 OFF면 아무 일도 안 함(오버헤드 0). 개체 자체는 발생-즉시 로그가 따로 남는다.
 */
@Mod.EventBusSubscriber(modid = EvoSimMod.MODID)
public final class SimEventTicker {

    private static final int CENSUS_TIME = 100;   // 하루 중 인구조사 시각(기상 직후)
    private static final double SCAN_RADIUS = 128.0;
    private static long lastCensusDay = Long.MIN_VALUE;

    private SimEventTicker() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !SimEvents.enabled()) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            long day = level.getGameTime() / 24000L;
            long tod = level.getDayTime() % 24000L;
            if (day == lastCensusDay || tod < CENSUS_TIME) {
                continue;
            }
            lastCensusDay = day;
            Set<MimicEntity> seen = new HashSet<>();
            List<MimicEntity> mimics = new ArrayList<>();
            for (ServerPlayer player : level.players()) {
                for (MimicEntity m : level.getEntitiesOfClass(MimicEntity.class,
                        player.getBoundingBox().inflate(SCAN_RADIUS))) {
                    if (seen.add(m)) {
                        mimics.add(m);
                    }
                }
            }
            if (level.players().isEmpty()) {
                // 무인(헤드리스 관측 런) 폴백 — 플레이어 반경 스캔이 인구 0으로 찍히는 함정 방지.
                for (MimicEntity m : level.getEntities(
                        com.evosim.mod.reg.ModEntities.MIMIC.get(),
                        e -> e.isAlive() && e.getIndividual() != null)) {
                    if (seen.add(m)) {
                        mimics.add(m);
                    }
                }
            }
            SimEvents.census(level, mimics);
            SimAudit.daily(level); // AI 검수용 일일 감사 1줄(어큐뮬레이터 리셋)
            return; // 하루 1회면 충분(첫 레벨 기준)
        }
    }
}
