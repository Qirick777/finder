package com.evosim.mod.entity;

import com.evosim.core.LifeStage;
import com.evosim.core.Schedule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * <b>전업 교사</b>(지식인 P3, 계획서 1.7) — 학위자 교사는 낮(근무)에 제 학교 강단(앵커)에 상주한다.
 * 밭·채집은 하지 않는다(MimicFarmGoal·MimicForageGoal 이 거른다). 배회·밤은 자유.
 * 끼니는 학교 주인 곳간이 댄다(FarmTicker.feedTeacher — 목사 급식과 같은 규칙).
 */
public class MimicTeacherGoal extends Goal {
    private static final double ARRIVE_SQ = 2.25;
    private final MimicEntity mob;
    private BlockPos seat;

    public MimicTeacherGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private boolean onDuty() {
        if (mob.getIndividual() == null || mob.getStage() != LifeStage.ADULT || mob.isFastSettle()
                || mob.isBuilding() || mob.isCritical() || !FarmTicker.isFullTimeTeacher(mob)) {
            return false;
        }
        return Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime()) == Schedule.Phase.WORK;
    }

    @Override
    public boolean canUse() {
        if (!onDuty()) {
            return false;
        }
        FacilityStore.Entry e = FarmTicker.teacherSchoolOf(mob);
        seat = e == null ? null : e.pos;
        return seat != null;
    }

    @Override
    public boolean canContinueToUse() {
        return onDuty() && seat != null;
    }

    @Override
    public void start() {
        mob.setVisitAnchor(seat);
        mob.setActivity("강단");
    }

    @Override
    public void stop() {
        mob.setVisitAnchor(null);
        seat = null;
    }

    @Override
    public void tick() {
        if (seat == null) {
            return;
        }
        if (mob.blockPosition().distSqr(seat) > ARRIVE_SQ) {
            if (mob.getNavigation().isDone()) {
                mob.getNavigation().moveTo(seat.getX() + 0.5, seat.getY(), seat.getZ() + 0.5, 1.0);
            }
            return;
        }
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(seat.getX() + 0.5, seat.getY() + 1.0, seat.getZ() + 0.5);
        if (mob.level() instanceof ServerLevel sl && mob.tickCount % 40 == 0) {
            FarmTicker.feedTeacher(sl, mob);
        }
    }
}
