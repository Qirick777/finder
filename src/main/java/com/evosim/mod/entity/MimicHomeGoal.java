package com.evosim.mod.entity;

import com.evosim.core.Schedule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.List;

/**
 * 거처 귀환 goal (설계서 §3 §16). 거처가 있는 미믹은 <b>밤(귀가)·취침</b> 구간에 거처 좌표로 수렴한다
 * → 밤에 가족이 한 곳에 뭉쳐 정산(§4). 낮(노동·배회)에는 풀려나 채집·구애하러 돌아다닌다.
 *
 * <h3>목적지는 앵커가 아니라 "내 자리"다</h3>
 * 종전에는 가구원 전원이 {@code homePos}(앵커) 한 칸을 목표로 삼았다. 천막 시절엔 그 한 칸이
 * 곧 실내여서 문제가 없었지만, 스키메틱 거처는 문이 앵커에서 2~5칸 떨어져 있다. 그래서
 * <b>먼저 온 한 명이 앵커에 누우면 나머지는 갈 곳이 없어 문간에 멈춰 선 채 밤을 샜다</b>
 * (제보 스크린샷). 지금은 {@link MimicEntity#homeSpot} 이 가구원마다 다른 실내 칸을 준다.
 *
 * <h3>밤에는 집 안을 돌아다닌다</h3>
 * 취침 구간 전(NIGHT)에는 자리에 도착해도 얼어붙지 않고, 이따금 다른 실내 칸으로 옮겨 다닌다.
 * 종전의 "도착하면 정지"는 문간에 굳어 서 있는 모습을 만들었다.
 */
public class MimicHomeGoal extends Goal {

    /** 도착 판정 반경². 자리 칸에 들어섰다고 볼 거리 — 1.5칸. */
    private static final double ARRIVED_SQR = 2.25;
    /** 실내 배회 간격(틱) — 이만큼마다 다른 칸으로 자리를 옮긴다(밤 대기 구간만). */
    private static final int ROAM_INTERVAL = 100;

    private final MimicEntity mob;
    private BlockPos target;
    private int roamCooldown;

    public MimicHomeGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /** 현재 스케줄 구간(개체 데이터 없으면 null — 구 동작 폴백). */
    private Schedule.Phase phase() {
        return mob.getIndividual() == null ? null
                : Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime());
    }

    /** 내 자리 — 가구원마다 다른 실내 칸. 서버가 아니면 앵커로 폴백. */
    private BlockPos spot() {
        return mob.level() instanceof ServerLevel sl ? mob.homeSpot(sl) : mob.getHomePos();
    }

    @Override
    public boolean canUse() {
        if (mob.isCourtTravel()) {
            return false; // 구혼 여행 중 — 밤에도 타향에 머묾(리시 앵커가 그쪽)
        }
        BlockPos home = mob.getHomePos();
        if (home == null) {
            return false;
        }
        Schedule.Phase ph = phase();
        if (ph == Schedule.Phase.NIGHT) {
            return !mob.isCritical(); // 밤 대기 점유(배회 왕복 방지) — 단 위급이면 R6 채집(6)에 양보
        }
        // 취침 구간도 <b>같은 예외</b>를 쓴다. 종전에는 NIGHT 에만 위급 양보가 있고 SLEEP 에는
        // 없었는데, SLEEP 은 tod 14000~기상으로 하루의 40%가 넘는 가장 긴 구간이다. 이 goal 은
        // 우선순위 4라 밭일(6)·채집(7)보다 먼저 MOVE 를 가져가므로, 위급한 개체가 그 긴 밤 내내
        // 거처로 끌려가 굶어 죽었다(실측: 위기 상태에서 밤에 밭으로 못 감).
        // 저장고에 밥이 있으면 우선순위 3인 MimicReturnGoal 이 먼저 데려가므로 양보해도 안전하다.
        // <b>내 자리</b> 기준으로 판정한다. 앵커 기준 3블록 게이트는 치명적이었다 —
        // 문이 앵커에서 2블록이라, 문에 들어서는 순간 이 goal(4)이 꺼지고 우선순위가 낮은
        // 취침 goal(5)이 켜져 <b>문간에서 그대로 누웠다</b>. 그러면 뒤따라온 가구원은 막힌
        // 문 앞에 서서 밤을 샌다(제보 스크린샷).
        boolean homeTime = ph == null || ph == Schedule.Phase.SLEEP;
        return homeTime && !mob.isCritical() && !arrived(home);
    }

    /**
     * <b>집 안에 들어섰는가</b> — 내 자리가 아니어도 실내 칸 하나에 닿으면 도착이다.
     *
     * <p>종전에는 {@code spot()} 한 칸만 봤다. 그런데 자리 배정은 {@code rank % 칸수} 라
     * 가구원이 칸보다 많으면 <b>같은 칸을 둘이 노리고</b>, 밀려난 쪽은 도착선 안에 영영 못
     * 들어간다 — 이 goal(4)이 MOVE 를 안 놓아 <b>취침(5)이 영영 안 켜졌다</b>(밤새 "귀소").
     *
     * <p>문간에서 눕던 과거 결함은 재발하지 않는다: 문은 실내 칸이 아니므로 문에 서 있는
     * 것으로는 여기가 참이 되지 않는다.
     */
    private boolean arrived(BlockPos home) {
        if (mob.level() instanceof ServerLevel sl) {
            return mob.atHomeInterior(sl, ARRIVED_SQR);
        }
        return mob.blockPosition().distSqr(home) <= ARRIVED_SQR;
    }

    @Override
    public void start() {
        target = spot();
        roamCooldown = ROAM_INTERVAL;
    }

    @Override
    public boolean canContinueToUse() {
        if (mob.isCourtTravel()) {
            return false;
        }
        BlockPos home = mob.getHomePos();
        if (home == null) {
            return false;
        }
        Schedule.Phase ph = phase();
        if (ph == Schedule.Phase.NIGHT) {
            return !mob.isCritical(); // 밤 내내 자리 지킴 — 위급 전이 시 즉시 양보(R6)
        }
        // 취침 구간은 <b>자리에 들어설 때까지만</b> 쥔다. 이 goal(4)이 계속 쥐면 우선순위가 낮은
        // 취침 goal(5)이 영영 못 켜진다(자리 지킴을 밤에만 한정하는 이유).
        boolean homeTime = ph == null || ph == Schedule.Phase.SLEEP;
        return homeTime && !mob.isCritical() && !arrived(home);
    }

    @Override
    public void stop() {
        target = null;
    }

    @Override
    public void tick() {
        BlockPos home = mob.getHomePos();
        if (home == null) {
            return;
        }
        if (target == null) {
            target = spot();
        }
        if (mob.blockPosition().distSqr(target) > ARRIVED_SQR) {
            mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.9);
            return;
        }
        // 자리에 들어섰다. 취침 구간이면 여기 머물러 취침 goal 에 넘긴다.
        if (phase() != Schedule.Phase.NIGHT) {
            if (!mob.getNavigation().isDone()) {
                mob.getNavigation().stop();
            }
            return;
        }
        // 밤 대기 — 이따금 다른 실내 칸으로 옮겨 다닌다(문간에 굳어 서 있지 않게).
        if (--roamCooldown > 0) {
            return;
        }
        roamCooldown = ROAM_INTERVAL;
        if (mob.level() instanceof ServerLevel sl) {
            HomeBlueprint bp = mob.blueprint(sl);
            List<BlockPos> inside = bp == null ? List.of() : bp.interior();
            if (inside.size() > 1) {
                target = inside.get(mob.getRandom().nextInt(inside.size()));
            }
        }
    }
}
