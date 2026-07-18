package com.evosim.mod.entity;

import com.evosim.mod.EvoSimMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * 밤 스킵 (관측 가속) — 전원 취침 구간(tod≥14000: 최대 취침 경계)에서 최조 기상(부지런 tod 0)
 * 직전까지 점프한다. 취침은 소모 0(Activity.SLEEP)이라 시뮬 결과 불변, 실시간만 ~40% 단축.
 *
 * <p>안전판: <b>위급 개체가 있으면 스킵하지 않는다</b> — 위급자는 밤에도 채집을 강행하는 생존
 * 경로(R6)라, 그들의 밤 시간을 지우면 아사가 과대 판정된다. 점프분은 {@link SimTime}에 가산되어
 * 익음·일 경계·타임스탬프가 전부 "실제로 지난 것"으로 처리된다.
 */
@Mod.EventBusSubscriber(modid = EvoSimMod.MODID)
public final class NightSkipTicker {

    private static final int CHECK_INTERVAL = 100;
    private static final long SLEEP_ALL = 14000L; // 최대 취침 경계(전 특성 취침 보장)
    private static final long WAKE_FIRST = 0L;    // 최조 기상(부지런 −1000 → 경계 1000-1000)
    private static boolean loaded = false;        // SavedData 오프셋 복원(재기동 1회)

    private NightSkipTicker() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getTickCount() % CHECK_INTERVAL != 0) {
            return;
        }
        if (!loaded) {
            loaded = true;
            SimTime.Store.get(server.overworld()); // 정적 오프셋·토글 복원(load 부수효과)
        }
        if (!SimTime.skipEnabled()) {
            return;
        }
        ServerLevel level = server.overworld();
        long tod = level.getDayTime() % 24000L;
        if (tod < SLEEP_ALL + 100) { // +100 여유 — 취침 경계 직후 정산 스캔이 끝난 뒤
            return;
        }
        // 위급 개체(밤 채집 강행)가 있으면 그들의 생존 시간을 지우지 않는다.
        boolean anyCritical = !level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null && e.isCritical()).isEmpty();
        if (anyCritical) {
            return;
        }
        long delta = 24000L - tod + WAKE_FIRST;
        level.setDayTime(level.getDayTime() + delta);
        SimTime.addSkip(level, delta);
        com.evosim.mod.log.SimEvents.note(level, "밤스킵", String.format(
                "tod %d → 기상(+%d틱, 누적 오프셋 %d)", tod, delta, SimTime.offset()));
    }
}
