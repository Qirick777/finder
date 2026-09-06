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
    /** 위급자 유예 경계 — 위급 개체가 있으면 스킵을 여기까지 미룬다(밤 채집 ~3900틱 보장 후
     *  잔여 밤만 점프). 종전 "위급 시 스킵 전면 금지"는 굶주림 국면부터 관측이 ~5배 느려지는
     *  부작용(런6 실측: d5 밤부터 스킵 0회) — 생존 경로 보존과 관측 속도의 절충. */
    private static final long CRITICAL_GRACE_TOD = 18000L;
    /**
     * <b>경비 유예 경계</b> — 경비대원이 있으면 스킵을 여기까지 미룬다.
     *
     * <p>이 클래스의 전제는 "취침은 소모 0 이라 시뮬 결과 불변"인데, <b>경비대에게 밤은
     * 근무 시간</b>이라 그 전제가 깨진다. {@link MimicGarrisonGoal} 주석에 적힌 실측이 이
     * 자리에도 그대로 적용된다: 순찰 창이 tod 14000~14100 의 <b>100틱</b>뿐이면 대원이
     * 표적에 닿을 수가 없어, 야간 경계가 한 번도 작동하지 않는다.
     *
     * <p>전면 금지(압박이 쓰는 방식)가 아니라 <b>유예</b>로 두는 이유는 관측 속도다. 경비대는
     * 이르면 d4 에 서므로 전면 금지면 그 뒤 모든 런이 두 배로 느려진다. 위급자 유예와 같은
     * 수(18000)를 쓰면 밤 경계 ~3900틱을 보장하면서 잔여 밤은 그대로 지운다 — 같은 절충을
     * 같은 눈금으로 한다.
     */
    private static final long WATCH_GRACE_TOD = 18000L;
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
        // 위급 개체(밤 채집 강행)가 있으면 즉시 스킵하지 않고 CRITICAL_GRACE_TOD까지 유예 —
        // 밤 채집 창을 보장한 뒤 잔여 밤만 점프(전면 금지 → 유예로 완화, 관측 하니스 절충).
        boolean anyCritical = !level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null && e.isCritical()).isEmpty();
        if (anyCritical && tod < CRITICAL_GRACE_TOD) {
            return;
        }
        // 경비대가 근무 중인 밤은 <b>절반만</b> 지운다(위 WATCH_GRACE_TOD 참조). 위급자는
        // 위에서 이미 걸렀으므로 여기서는 실제로 경계를 서고 있는 대원만 센다.
        boolean anyWatch = !level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null && e.inPoorhouse()
                        && !e.isCritical()).isEmpty();
        if (anyWatch && tod < WATCH_GRACE_TOD) {
            return;
        }
        // <b>압박이 걸린 밤은 지우지 않는다.</b> 이 클래스의 전제는 "취침은 소모 0 이라 시뮬
        // 결과 불변"인데, 군인에게 밤은 근무 시간이라 그 전제가 깨진다 — 순찰이 도는 구간이
        // night(tod ≥ 14000)인데 여기서 14100 에 점프하므로 창이 100틱뿐이고, 병사가 52블록
        // 떨어진 표적에 갈 수가 없다(실측: 최근접 32~43블록에서 제자리 배회).
        //
        // 평시에는 여전히 지운다 — 표적이 있을 때만 막아 관측 속도의 손해를 전쟁 국면으로
        // 한정한다. 위급자 유예와 같은 성격의 안전판이다.
        if (FarmTicker.pressureActive()) {
            return;
        }
        long delta = 24000L - tod + WAKE_FIRST;
        level.setDayTime(level.getDayTime() + delta);
        SimTime.addSkip(level, delta);
        GardenTicker.catchUp(level, delta); // 스킵된 밤에도 정원은 자란 것으로(유인 월드 등가)
        for (MimicEntity e : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                x -> x.isAlive())) {
            e.addGrowthTicks((int) delta); // 성장 시계 SimTime 정합(P2) — 수면 중에도 자란다
        }
        com.evosim.mod.log.SimEvents.note(level, "밤스킵", String.format(
                "tod %d → 기상(+%d틱, 누적 오프셋 %d)", tod, delta, SimTime.offset()));
    }
}
