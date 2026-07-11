package com.evosim.mod.entity;

import com.evosim.core.Individual;
import com.evosim.core.Schedule;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 취침 goal (설계서 §16 취침 구간). 취침 시간대에 거처(방랑자면 제자리) 근처에 있으면 <b>진짜 바닥에 누워
 * 잔다</b>(SLEEPING 포즈) — 이동을 멈추고 밤새 집에 머물러 밤 정산(§4)에 가족이 모여 있게 한다.
 *
 * <p><b>공격이 들어오면 즉시 일어난다</b>: 피격(hurtTime)·가해자 기억이 있으면 취침을 중단한다(그때 전투
 * goal이 우선순위로 대응). 취침 종료 시 포즈를 STANDING 으로 되돌린다.
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
        // 공격받는 중이면 취침 불가 → 즉시 일어남.
        if (mob.hurtTime > 0 || mob.getLastHurtByMob() != null || mob.getTarget() != null) {
            return false;
        }
        // R6: 위급(소지 고갈)이면 자지 않는다 — 저장고에 밥 있으면 귀가·인출, 없으면 밤샘 채집.
        if (mob.isCritical()) {
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
        if (mob.getHomePos() != null) {
            float yr = mob.getHomeFacingDir().toYRot(); // 천막 방향으로 눕기
            mob.setYRot(yr);
            mob.yBodyRot = yr;
            mob.yBodyRotO = yr;
            mob.setYHeadRot(yr);
        }
        mob.setPose(Pose.SLEEPING); // 바닥에 눕는 포즈(렌더러가 눕혀 그림)
    }

    @Override
    public void tick() {
        mob.getNavigation().stop();
        if (mob.getPose() != Pose.SLEEPING) {
            mob.setPose(Pose.SLEEPING);
        }
    }

    @Override
    public void stop() {
        if (mob.getPose() == Pose.SLEEPING) {
            mob.setPose(Pose.STANDING); // 일어남
        }
    }
}
