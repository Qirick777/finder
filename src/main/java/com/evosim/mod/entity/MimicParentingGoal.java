package com.evosim.mod.entity;

import com.evosim.core.LifeStage;
import com.evosim.core.ParentingClass;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 육아 goal (설계서 육아 클래스, §7·§8). 자식이 유아기일 때, 양육자(성년 여성)를 자기 육아 클래스의
 * <b>돌봄 반경</b> 안으로 구속한다. 적극(반경0)=거처에서 안 나옴, 무시(무제한)=구속 안 함(자유 배회).
 *
 * <p>반경이 작을수록 유아 곁에 머물러 유아를 먹여 살림 → 육아 투자와 유아 생존이 직결.
 */
public class MimicParentingGoal extends Goal {

    private final MimicEntity mob;

    public MimicParentingGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!mob.isFemale() || mob.getStage() != LifeStage.ADULT
                || mob.getHomePos() == null || mob.getIndividual() == null) {
            return false;
        }
        ParentingClass pc = mob.getIndividual().parentingCare();
        if (pc == ParentingClass.NEGLECTFUL) {
            return false; // 무시 = 무제한 → 구속하지 않음(자유 배회)
        }
        if (!mob.hasInfantAtHome()) {
            return false; // 유아 자식이 있을 때만 작동
        }
        double r = pc.careRadius();
        return mob.blockPosition().distSqr(mob.getHomePos()) > r * r; // 돌봄 반경 벗어남
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        BlockPos home = mob.getHomePos();
        if (home != null) {
            mob.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1.0);
        }
    }
}
