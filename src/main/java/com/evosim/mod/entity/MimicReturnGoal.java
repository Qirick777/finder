package com.evosim.mod.entity;

import com.evosim.core.FoodEconomy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 식량 귀가 goal (식량 경제 v2). 입금도 인출도 <b>거처에서만</b> — 하나의 밴드 [1,2)로 통일:
 *
 * <ul>
 *   <li><b>넣으러</b>: 소지 H ≥ 2(여분 정수 생김) → 귀가해 저장고에 정수 입금.</li>
 *   <li><b>꺼내러</b>: H < 0.8(귀가 히스테리시스, C-1) 이고 저장고에 밥이 있으면 → 귀가해 1개 인출.
 *       위급(H<0.3)이면서 저장고에 밥이 있으면 이 goal이 채집보다 우선한다(A-3).</li>
 * </ul>
 *
 * <p>도착 즉시 {@link MimicEntity#selfSettle}로 입출금(가족틱 대기 없음) — 회계는 순수 settleHome.
 */
public class MimicReturnGoal extends Goal {

    private static final double ARRIVE_DIST_SQ = 6.25; // 2.5블록 안 = 도착

    private final MimicEntity mob;

    public MimicReturnGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    private boolean wantsTrip() {
        if (mob.getHomePos() == null || mob.getIndividual() == null
                || mob.isBuilding() || mob.isFastSettle() || mob.isCourtTravel()) {
            return false; // 구혼 여행 중엔 귀가로 끌지 않음(노상 자급 — H 상한 컷)
        }
        if (mob.getHolding() >= FoodEconomy.BAND_HIGH) {
            return true; // 여분 정수 → 넣으러
        }
        return mob.getHolding() < FoodEconomy.RETURN_LOW && mob.larderHasFood(); // 꺼내러
    }

    @Override
    public boolean canUse() {
        if (!wantsTrip()) {
            return false;
        }
        if (mob.blockPosition().distSqr(mob.getHomePos()) <= ARRIVE_DIST_SQ) {
            // 이미 집 안 — 이동 goal 없이 즉석 입출금(집에 서 있으면 배고파도 인출을 못 하던 결함 방지).
            if (mob.level() instanceof ServerLevel sl) {
                mob.selfSettle(sl);
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return wantsTrip()
                && mob.blockPosition().distSqr(mob.getHomePos()) > ARRIVE_DIST_SQ;
    }

    @Override
    public void tick() {
        BlockPos home = mob.getHomePos();
        if (home == null) {
            return;
        }
        mob.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5,
                mob.isCritical() ? 1.2 : 1.0); // 위급하면 서둘러 귀가
    }

    @Override
    public void stop() {
        // 도착으로 종료됐으면 즉석 입출금(밴드 [1,2)로 복귀) — 가족틱을 기다리지 않는다.
        if (mob.getHomePos() != null && mob.level() instanceof ServerLevel sl
                && mob.blockPosition().distSqr(mob.getHomePos()) <= ARRIVE_DIST_SQ) {
            mob.selfSettle(sl);
        }
    }
}
