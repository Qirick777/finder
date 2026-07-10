package com.evosim.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 거처 건축 goal — 짓는 동안 미믹을 부지로 데려가 머물게 한다(짓는 연출). <b>리더</b>는 플레이어처럼
 * <b>다음 설치할 블록 자리로 직접 걸어가</b> 바짝 붙고, <b>조력자</b>는 거처 중심에 대기한다. 실제 블록 설치는
 * 리더의 {@code buildTick}이 손이 닿았을 때 한다. 공격받으면(hurtTime·타겟) 물러나 전투 goal이 우선한다.
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
        BlockPos home = mob.getHomePos();
        if (home == null) {
            return;
        }
        // 리더는 다음 블록 자리로, 조력자(또는 목표 미정)는 거처 중심으로.
        BlockPos target = mob.isBuildLeader() ? mob.getBuildTargetPos() : null;
        boolean toBlock = target != null;
        BlockPos dest = toBlock ? target : home;

        mob.getLookControl().setLookAt(dest.getX() + 0.5, dest.getY() + 0.5, dest.getZ() + 0.5);
        // 목표 아래 지면으로 이동(높은 지붕 칸을 향해 헛되이 오르지 않게 수평 기준). 블록엔 바짝, 중심엔 느슨.
        double dx = (dest.getX() + 0.5) - mob.getX();
        double dz = (dest.getZ() + 0.5) - mob.getZ();
        double horizSqr = dx * dx + dz * dz;
        double stopSqr = toBlock ? 2.25 : 6.0;
        if (horizSqr > stopSqr) {
            mob.getNavigation().moveTo(dest.getX() + 0.5, home.getY(), dest.getZ() + 0.5, 1.0);
        } else {
            mob.getNavigation().stop();
        }
    }
}
