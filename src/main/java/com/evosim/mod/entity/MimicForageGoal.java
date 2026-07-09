package com.evosim.mod.entity;

import com.evosim.core.Individual;
import com.evosim.core.Schedule;
import com.evosim.core.SurvivalRules;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 채집/사냥 배회 goal (설계서 §16 노동 구간, §4 수확). 노동 시간대에 성년·만혼소년이 주변을 돌아다니며
 * 먹이를 구한다 — 실제 수확량 누적은 엔티티 tick({@code forageTick})이 배율대로 처리하고, 이 goal 은
 * 그 시간에 "일하러 돌아다니는" 눈에 보이는 행동을 준다.
 */
public class MimicForageGoal extends Goal {

    private final MimicEntity mob;

    public MimicForageGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        Individual ind = mob.getIndividual();
        if (ind == null || !SurvivalRules.canGather(mob.getStage(), ind)) {
            return false;
        }
        return Schedule.phaseAt(ind, mob.level().getDayTime()) == Schedule.Phase.WORK;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        if (mob.getNavigation().isDone()) {
            Vec3 target = DefaultRandomPos.getPos(mob, 10, 7);
            if (target != null) {
                mob.getNavigation().moveTo(target.x, target.y, target.z, 1.0);
            }
        }
    }
}
