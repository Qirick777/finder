package com.evosim.mod.entity;

import com.evosim.core.Schedule;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 거처 귀환 goal (설계서 §3 §16). 거처가 있는 미믹은 <b>밤(귀가)·취침</b> 구간에 거처 좌표로 수렴한다
 * → 밤에 가족이 한 곳에 뭉쳐 정산(§4). 낮(노동·배회)에는 풀려나 채집·구애하러 돌아다닌다.
 */
public class MimicHomeGoal extends Goal {

    private final MimicEntity mob;

    public MimicHomeGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /** 지금이 귀가/취침 시간인가 (개체 스케줄 기준). 개체 데이터 없으면 항상 귀가. */
    private boolean homeTime() {
        if (mob.getIndividual() == null) {
            return true;
        }
        Schedule.Phase phase = Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime());
        return phase == Schedule.Phase.NIGHT || phase == Schedule.Phase.SLEEP;
    }

    @Override
    public boolean canUse() {
        if (mob.isCourtTravel()) {
            return false; // 구혼 여행 중 — 밤에도 타향에 머묾(리시 앵커가 그쪽)
        }
        BlockPos home = mob.getHomePos();
        return home != null && homeTime() && mob.blockPosition().distSqr(home) > 9.0; // 3블록 밖이면 귀환
    }

    @Override
    public boolean canContinueToUse() {
        if (mob.isCourtTravel()) {
            return false;
        }
        BlockPos home = mob.getHomePos();
        return home != null && homeTime() && mob.blockPosition().distSqr(home) > 4.0;
    }

    @Override
    public void tick() {
        BlockPos home = mob.getHomePos();
        if (home != null) {
            mob.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0.9);
        }
    }
}
