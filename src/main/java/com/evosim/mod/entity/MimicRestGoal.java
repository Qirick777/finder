package com.evosim.mod.entity;

import com.evosim.core.Individual;
import com.evosim.core.Schedule;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 취침 goal (설계서 §16 취침 구간). 취침 시간대에 거처(또는 방랑자면 제자리) 근처에 있으면 이동을 멈추고
 * 쉰다 — 밤새 돌아다니지 않고 집에 머물러 밤 정산(§4)에 가족이 모여 있게 한다.
 */
public class MimicRestGoal extends Goal {

    private static final double NEAR_HOME_SQR = 36.0; // 집 6블록 이내면 취침(멀면 귀가 goal이 먼저)

    private final MimicEntity mob;

    public MimicRestGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        Individual ind = mob.getIndividual();
        if (ind == null) {
            return false;
        }
        if (Schedule.phaseAt(ind, mob.level().getDayTime()) != Schedule.Phase.SLEEP) {
            return false;
        }
        BlockPos home = mob.getHomePos();
        return home == null || mob.blockPosition().distSqr(home) <= NEAR_HOME_SQR;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        mob.getNavigation().stop();
    }
}
