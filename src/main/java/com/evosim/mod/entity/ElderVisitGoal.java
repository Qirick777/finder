package com.evosim.mod.entity;

import com.evosim.core.FoodEconomy;
import com.evosim.core.LifeStage;
import com.evosim.core.Schedule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * 노인 방문 goal (노년기 §5). 부모 링크(자식.parentA/B == 내 id)만으로 자식의 거처를 찾아:
 *
 * <ul>
 *   <li><b>배달</b>: 책임/기본 노인의 잉여(H≥2)를 자식 집 저장고에 정수 입금([노인공유] — 결과값 로그).
 *       가드② — 자기 저장고가 부부 하루소모(1일치) 미만이면 배달 대신 자기 입금(ReturnGoal 폴백).</li>
 *   <li><b>마실 육아</b>: 유아 있는 자식 집에 머물러(loiter) "곁에 성인" 급식 판정을 채움 — 부모가
 *       채집 나간 사이 손자 방치 아사 방지.</li>
 * </ul>
 *
 * <p>가드① — 배우자 위급 구조(나눔 goal, 우선순위 2)가 항상 이 goal(3)보다 먼저다.
 */
public class ElderVisitGoal extends Goal {

    private static final double ARRIVE_DIST_SQ = 6.25;  // 2.5블록 안 = 도착
    private static final double SCAN_RANGE = 96.0;      // 자식 탐색 반경
    private static final int TARGET_REFRESH = 200;      // 대상 재탐색 주기(틱) — 스캔 비용 억제
    private static final int LOITER_TICKS = 600;        // 유아 곁 머묾(≈30초)

    private final MimicEntity mob;
    private BlockPos target;
    private boolean targetHasInfant;
    private int refreshCooldown;
    private int loiter;

    public ElderVisitGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (mob.getStage() != LifeStage.ELDER || mob.getIndividual() == null
                || mob.isFastSettle() || mob.isBuilding()
                || mob.getHolding() < FoodEconomy.RETURN_LOW // 배고픔 — 마실보다 귀가·인출 먼저
                || !(mob.level() instanceof ServerLevel sl)) {
            return false;
        }
        Schedule.Phase phase = Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime());
        if (phase != Schedule.Phase.WORK && phase != Schedule.Phase.WANDER) {
            return false; // 낮에만 마실·배달(밤엔 귀가·취침)
        }
        if (refreshCooldown > 0) {
            refreshCooldown--;
            return false;
        }
        refreshCooldown = TARGET_REFRESH;
        boolean deliver = mob.canDeliverSurplus(sl); // 잉여+공유형+가드②
        findTarget(deliver);
        return target != null;
    }

    @Override
    public void start() {
        // 자식 집이 활동반경(리시) 밖일 수 있으므로 구혼 여행과 같은 앵커 패턴 — 리시가 목적지로 끌게 한다.
        mob.setVisitAnchor(target);
    }

    @Override
    public boolean canContinueToUse() {
        if (target == null || mob.getStage() != LifeStage.ELDER || mob.getIndividual() == null) {
            return false;
        }
        if (mob.getHolding() < FoodEconomy.RETURN_LOW) {
            return false; // 배고픔 — 머묾을 끊고 귀가·인출(ReturnGoal)에 양보(마실 중 아사 방지)
        }
        Schedule.Phase phase = Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime());
        if (phase != Schedule.Phase.WORK && phase != Schedule.Phase.WANDER) {
            return false; // 저녁 — 귀가(HomeGoal)
        }
        return loiter > 0 || !arrived() || targetHasInfant; // 유아 곁은 낮 동안 계속 머묾
    }

    @Override
    public void stop() {
        // 선점(리시가 MOVE 탈취)으로 인한 stop 이면 앵커 유지 — 리시가 목적지까지 끌어야 하므로.
        // 방문이 진짜 끝났을 때(target 소거·배고픔·밤·배달후 무유아)만 해제 = !canContinueToUse().
        // (종전엔 선점마다 앵커를 지워 ElderVisit↔Leash 무한 진동·이동 0 이던 것을 해소.)
        if (!canContinueToUse()) {
            mob.setVisitAnchor(null); // 리시 앵커 원복(거처)
            target = null;
            targetHasInfant = false;
            loiter = 0;
        }
    }

    @Override
    public void tick() {
        if (target == null) {
            return;
        }
        if (loiter > 0) {
            loiter--; // 유아 곁 머묾 — "곁에 성인" 급식 판정이 이 체류로 충족됨
            if (loiter == 0 && targetHasInfant) {
                loiter = LOITER_TICKS; // 낮 동안 계속(방치 공백 방지) — 저녁엔 canContinueToUse가 끊음
            }
            return;
        }
        if (!arrived()) {
            mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.9);
            return;
        }
        // 도착: 배달 가능하면 정수 입금(결과값 로그는 deliverSurplusTo가 남김), 유아 있으면 머묾.
        if (mob.level() instanceof ServerLevel sl && mob.canDeliverSurplus(sl)) {
            mob.deliverSurplusTo(sl, target);
        }
        if (targetHasInfant) {
            loiter = LOITER_TICKS;
        } else {
            target = null; // 배달만 — 종료(저녁 HomeGoal이 귀가)
        }
    }

    private boolean arrived() {
        return target != null && mob.blockPosition().distSqr(target) <= ARRIVE_DIST_SQ;
    }

    /** 자식(부모 링크)의 거처 수집 — 유아 있는 집 우선, 없으면(배달할 때만) 아무 자식 집. */
    private void findTarget(boolean deliver) {
        target = null;
        targetHasInfant = false;
        long myId = mob.getIndividual().id();
        Map<Long, Boolean> childHomes = new HashMap<>(); // homePos → 유아 존재
        var all = mob.level().getEntitiesOfClass(MimicEntity.class,
                mob.getBoundingBox().inflate(SCAN_RANGE));
        for (MimicEntity m : all) {
            if (m.getIndividual() == null || m.getHomePos() == null) {
                continue;
            }
            if ((m.getIndividual().parentAId() == myId || m.getIndividual().parentBId() == myId)
                    && !m.getHomePos().equals(mob.getHomePos())) {
                childHomes.putIfAbsent(m.getHomePos().asLong(), false);
            }
        }
        if (childHomes.isEmpty()) {
            return;
        }
        for (MimicEntity m : all) { // 그 집들에 유아가 있는지 대조(혈통 추적 불필요 — 지시 방식)
            if (m.getStage() == LifeStage.INFANT && m.getHomePos() != null
                    && childHomes.containsKey(m.getHomePos().asLong())) {
                childHomes.put(m.getHomePos().asLong(), true);
            }
        }
        long best = 0L;
        for (Map.Entry<Long, Boolean> e : childHomes.entrySet()) {
            if (e.getValue()) {
                best = e.getKey(); // 유아 있는 집 우선(마실 육아)
                break;
            }
        }
        if (best == 0L) {
            if (!deliver) {
                return; // 유아도 없고 배달할 것도 없음 — 갈 이유 없음
            }
            best = childHomes.keySet().iterator().next();
        }
        target = BlockPos.of(best);
        targetHasInfant = Boolean.TRUE.equals(childHomes.get(best));
    }
}
