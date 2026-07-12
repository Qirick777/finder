package com.evosim.mod.entity;

import com.evosim.core.FoodEconomy;
import com.evosim.mod.log.SimEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 가족 나눔 goal (식량 경제 v2, B 연출). 근처 <b>위급(H<0.3)</b> 식구에게 여유 있는(H≥1.5) 식구가
 * 다가가 소지분 일부를 건넨다 — "food flows to whoever needs it most"의 보이는 겉모습.
 * 저장고를 거치지 않는 순수 소지분 재분배라 정수 불변식과 무관하다.
 *
 * <p>안전망: 집에 못 돌아온 위급 식구·저장고 소진 뒤 굶는 가장을 가족이 배달로 살린다(④·⑤ 완충).
 */
public class MimicShareGoal extends Goal {

    private static final double SHARE_RANGE = 16.0;  // 이 안의 가족만 도움
    private static final int COOLDOWN = 200;         // 전달 간 쿨타임(틱)
    // 전달량·자격 문턱은 특성 노브: FoodEconomy.shareAmount(관대0.75/기본0.5/인색0.25) ·
    // shareThreshold(이타1.0/기본1.5/이기∞=나눔 안 함) — 순수 함수라 evotest로 값 검증.

    private final MimicEntity mob;
    private MimicEntity needy;
    private int cooldown;

    public MimicShareGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        if (mob.getIndividual() == null || mob.isBuilding() || mob.isFastSettle()
                || mob.getHolding() < FoodEconomy.shareThreshold(mob.getIndividual())) {
            return false; // 자격 문턱(이타 1.0 / 기본 1.5 / 이기 ∞ = 절대 안 나눔) 미달
        }
        needy = findNeedy();
        return needy != null;
    }

    @Override
    public boolean canContinueToUse() {
        return needy != null && needy.isAlive() && needy.isCritical()
                && mob.getIndividual() != null
                && mob.getHolding() >= FoodEconomy.shareThreshold(mob.getIndividual())
                        - FoodEconomy.shareAmount(mob.getIndividual());
    }

    @Override
    public void stop() {
        needy = null;
    }

    @Override
    public void tick() {
        if (needy == null) {
            return;
        }
        mob.getLookControl().setLookAt(needy, 30.0F, 30.0F);
        if (mob.distanceToSqr(needy) > 2.25) {
            mob.getNavigation().moveTo(needy, 1.1);
            return;
        }
        double given = mob.shareFoodTo(needy,
                FoodEconomy.shareAmount(mob.getIndividual())); // 실제 전달량(0이면 못 준 것)
        if (given > 0.0) {
            mob.swing(InteractionHand.MAIN_HAND);
            SimEvents.event(mob, "나눔", String.format("굶는 식구 #%d 에게 식량 %.2f 건넴",
                    needy.getId(), given)); // 결과값만 기록 — 시도·고정값 금지
        }
        cooldown = COOLDOWN;
        needy = null;
    }

    /** 같은 거처 가족(또는 방랑 배우자) 중 가장 가까운 위급 식구. */
    private MimicEntity findNeedy() {
        MimicEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (MimicEntity m : mob.level().getEntitiesOfClass(
                MimicEntity.class, mob.getBoundingBox().inflate(SHARE_RANGE))) {
            if (m == mob || !m.isAlive() || m.getIndividual() == null || !m.isCritical()) {
                continue;
            }
            boolean family = mob.getHomePos() != null && mob.getHomePos().equals(m.getHomePos());
            if (!family) {
                continue;
            }
            double d = mob.distanceToSqr(m);
            if (d < bestDist) {
                bestDist = d;
                best = m;
            }
        }
        return best;
    }
}
