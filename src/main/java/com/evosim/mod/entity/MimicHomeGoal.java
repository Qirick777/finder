package com.evosim.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 거처 귀환 goal (설계서 §3). 거처가 있는 미믹은 거처 좌표 근처로 수렴 → 가족이 한 곳에 뭉친다
 * (밤에 집 좌표로 수렴 = 가정, §14 관찰). 겹치지 않는 거처들이라 가족별로 흩어져 모인다.
 */
public class MimicHomeGoal extends Goal {

    private final MimicEntity mob;

    public MimicHomeGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        BlockPos home = mob.getHomePos();
        return home != null && mob.blockPosition().distSqr(home) > 9.0; // 3블록 밖이면 귀환
    }

    @Override
    public boolean canContinueToUse() {
        BlockPos home = mob.getHomePos();
        return home != null && mob.blockPosition().distSqr(home) > 4.0;
    }

    @Override
    public void tick() {
        BlockPos home = mob.getHomePos();
        if (home != null) {
            mob.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0.9);
        }
    }
}
