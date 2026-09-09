package com.evosim.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 활동반경 리시 goal (설계서 §14 활동반경). 개체가 앵커(거처, 없으면 태어난 곳)에서 특성별 활동반경
 * ({@link com.evosim.core.Roaming})을 벗어나면 <b>시간대와 무관하게</b> 앵커로 되돌린다 — 무한 표류·분산
 * (개체군 증발)을 막는 유일한 안쪽 힘. 채집·구애·배회(우선순위 낮음)보다 우선한다.
 *
 * <p>전투·건축 중엔 물러난다(생존·정착 우선). 경계에서 진동하지 않도록 <b>히스테리시스</b>: 반경을 넘으면
 * 작동하고, 반경의 60% 안으로 들어와야 종료한다.
 */
public class MimicLeashGoal extends Goal {

    private static final double INNER_FRACTION = 0.6; // 이 비율 안으로 들어오면 리시 종료
    private static final double CARAVAN_ARRIVE_SQ = 25.0; // 마실 도착 해제(5블록) — ElderVisit 도착(5)과 일치.
    // 천막 우회 네비 종점(실측 최대 4블록)보다 넓어야 함: 좁으면 해제 조건에 영영 못 닿아 리시가
    // 노인을 마당에 무기한 붙잡는다(귀가·취침·채집 전부 차단 — 최악 시 아사).

    private final MimicEntity mob;

    public MimicLeashGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (mob.getIndividual() == null || mob.isBuilding() || mob.isUnderThreat()) {
            return false; // 전투·건축은 우선
        }
        if (onGuardDuty()) {
            return false;
        }
        BlockPos anchor = mob.roamAnchor();
        if (anchor == null) {
            return false;
        }
        double r = mob.roamRadius();
        return mob.blockPosition().distSqr(anchor) > r * r;
    }

    /**
     * <b>근무 중인 경비대원은 리시가 끌지 않는다</b> — 경계 goal 이 스스로 데려간다.
     *
     * <p>경계 goal 은 매 틱 guardAnchor 를 순찰 표적으로 놓는데, 표적이 활동반경 밖이면 이
     * goal(2)이 경계(4)를 밀어내고 "호위"를 맡는다. 표적이 지붕·벽 안처럼 닿을 수 없는 칸이면
     * 5블록 도착선에 영영 못 들어가 밤새 여기 붙들린다 — 경계 goal 의 "못 가면 다음 집"
     * 안전망은 밀려난 뒤라 돌지 않는다. 실측(경계 무대 14, evosim goals): 13명 중 12명이
     * [Leash] · 앵커 7~74블록 · 라벨 없음, 순찰 표본이 밤 초반 2400틱에서 끊김.
     *
     * <p>귀가·취침·채집·밭일과 같은 가름(소속 · 위급 아님 · 군인 아님)이다. 위급이면 경계 goal
     * 이 앵커를 놓고 물러나므로 리시가 다시 집으로 데려간다. 군인은 종전대로 리시가 호위한다.
     */
    private boolean onGuardDuty() {
        return mob.inPoorhouse() && !mob.isCritical() && !FarmTicker.isSoldier(mob);
    }

    @Override
    public boolean canContinueToUse() {
        if (mob.getIndividual() == null || mob.isUnderThreat()) {
            return false;
        }
        if (onGuardDuty()) {
            return false;
        }
        BlockPos anchor = mob.roamAnchor();
        if (anchor == null) {
            return false;
        }
        // 마실(visitAnchor)은 도착까지 끈다 — inner(60%)에서 놓으면 최종 접근을 이어받을 goal이 없어
        // inner↔반경 사이를 왕복하며 배달 미완. 구혼여행은 구애 goal이 최종접근을 마무리하므로 제외.
        // 구걸도 같다 — inner(60%)에서 놓으면 최종 접근을 이어받을 힘이 구걸 goal(3) 하나뿐인데
        // 그 사이 반경 밖으로 다시 나가 리시가 재개되면 경계에서 왕복만 한다. 도착(5블록)까지
        // 리시가 <b>호위로</b> 데려다 놓고, 수령은 goal 이 한다.
        if ((mob.hasVisitAnchor() || mob.isBegging() || mob.hasGuardAnchor())
                && !mob.isCourtTravel()) {
            return mob.blockPosition().distSqr(anchor) > CARAVAN_ARRIVE_SQ;
        }
        double inner = mob.roamRadius() * INNER_FRACTION;
        return mob.blockPosition().distSqr(anchor) > inner * inner; // 60% 안까지 복귀해야 종료
    }

    @Override
    public void tick() {
        BlockPos anchor = mob.roamAnchor();
        if (anchor != null) {
            mob.getLookControl().setLookAt(anchor.getX() + 0.5, anchor.getY() + 1.0, anchor.getZ() + 0.5);
            mob.getNavigation().moveTo(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5, 1.0);
        }
    }
}
