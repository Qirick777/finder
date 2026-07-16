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
        BlockPos anchor = mob.roamAnchor();
        if (anchor == null) {
            return false;
        }
        double r = mob.roamRadius();
        return mob.blockPosition().distSqr(anchor) > r * r;
    }

    @Override
    public boolean canContinueToUse() {
        if (mob.getIndividual() == null || mob.isUnderThreat()) {
            return false;
        }
        BlockPos anchor = mob.roamAnchor();
        if (anchor == null) {
            return false;
        }
        // 마실(visitAnchor)은 도착까지 끈다 — inner(60%)에서 놓으면 최종 접근을 이어받을 goal이 없어
        // inner↔반경 사이를 왕복하며 배달 미완. 구혼여행은 구애 goal이 최종접근을 마무리하므로 제외.
        if (mob.hasVisitAnchor() && !mob.isCourtTravel()) {
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
