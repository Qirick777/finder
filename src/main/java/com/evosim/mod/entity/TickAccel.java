package com.evosim.mod.entity;

import com.evosim.mod.EvoSimMod;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 틱레이트 가속 (관측 가속 A안) — 서버 루프가 매 틱 더한 50ms 대기 목표(nextTickTime)를
 * 틱 종료 시 리플렉션으로 되당겨, 실질 틱 간격을 50/배율 ms 로 줄인다. 모든 시뮬 로직
 * (성장·익음·스케줄·SimTime)이 <b>틱 번호 기반</b>이라 시맨틱은 불변 — 순수 벽시계 압축.
 *
 * <p><b>MSPT 가드</b>: 평균 틱 소요가 새 예산의 80%를 넘으면 그 틱은 가속을 쉰다(자동
 * 스로틀) — 과부하 시 조용히 1배로 수렴할 뿐 오작동이 없다. 리플렉션 실패 시 영구 비활성.
 *
 * <p>휘발 상태(재기동 시 1배) — 관측 체크리스트에 {@code evosim tickrate 2} 포함할 것.
 */
@Mod.EventBusSubscriber(modid = EvoSimMod.MODID)
public final class TickAccel {

    private static volatile int factor = 1;
    private static java.lang.reflect.Field nextTickTime;
    private static boolean broken;

    private TickAccel() {
    }

    /** 배율 설정(1~4). 1 = 가속 해제. */
    public static void setFactor(int f) {
        factor = Math.max(1, Math.min(4, f));
    }

    public static int factor() {
        return factor;
    }

    /** 진단 문자열 — 배율·평균 MSPT·가드 상태. */
    public static String status(MinecraftServer server) {
        return String.format("배율 ×%d · 평균 틱 %.1fms · 예산 %dms%s", factor,
                server.getAverageTickTime(), 50 / Math.max(1, factor),
                broken ? " · [리플렉션 실패 — 비활성]" : "");
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || factor <= 1 || broken
                || event.getServer() == null) {
            return;
        }
        MinecraftServer server = event.getServer();
        long budget = 50L / factor;
        if (server.getAverageTickTime() > budget * 0.8f) {
            return; // 과부하 가드 — 이번 틱은 정속
        }
        try {
            if (nextTickTime == null) {
                nextTickTime = MinecraftServer.class.getDeclaredField("nextTickTime");
                nextTickTime.setAccessible(true);
            }
            nextTickTime.setLong(server, nextTickTime.getLong(server) - (50L - budget));
        } catch (ReflectiveOperationException e) {
            broken = true; // 매핑 상이 등 — 조용히 영구 비활성(정속 폴백)
        }
    }
}
