package com.evosim.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 거처 건축 goal — 짓는 동안 두 미믹을 부지(거처 좌표)로 데려가 머물게 한다(짓는 연출). 실제 블록 설치는
 * 리더의 {@code buildTick}이 한다. 공격받으면(hurtTime·타겟) 물러나 전투 goal이 우선한다.
 */
public class MimicBuildGoal extends Goal {

    private final MimicEntity mob;

    public MimicBuildGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return mob.isBuilding() && mob.getHomePos() != null
                && mob.hurtTime <= 0 && mob.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        BlockPos h = mob.getHomePos();
        if (h == null) {
            return;
        }
        mob.getLookControl().setLookAt(h.getX() + 0.5, h.getY() + 1.0, h.getZ() + 0.5);
        if (mob.blockPosition().distSqr(h) > 6.0) {
            mob.getNavigation().moveTo(h.getX() + 0.5, h.getY(), h.getZ() + 0.5, 1.0);
        } else {
            mob.getNavigation().stop();
        }
    }
}
