package com.evosim.mod.entity;

import com.evosim.core.LifeStage;
import com.evosim.core.Schedule;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * <b>대학생</b>(지식인 P2) — 근무 시간에 제 강의실 좌석({@link FarmTicker#studySeatOf})에 앉는다.
 * 앉은 날만 출석이 적립된다(MimicSchoolGoal 과 같은 규칙). 배회·밤은 자유(독립 학생은 채집).
 * 교수가 없는 날(휴강)은 자리가 없어 오지 않는다.
 */
public class MimicStudyGoal extends Goal {
    private static final double ARRIVE_SQ = 2.25;
    private static final int STUCK_GIVE_UP = 600;
    private final MimicEntity mob;
    private BlockPos seat;
    private BlockPos lastPos;
    private int stuck;
    private long gaveUpDay = -1L;

    public MimicStudyGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private boolean onDuty() {
        if (mob.getIndividual() == null || mob.getStage() != LifeStage.ADULT || mob.isFastSettle()
                || mob.isBuilding() || mob.isCritical() || mob.isUnderThreat() || !mob.isStudent()) {
            return false;
        }
        return Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime()) == Schedule.Phase.WORK;
    }

    @Override
    public boolean canUse() {
        if (!onDuty()) {
            return false;
        }
        long today = SimTime.tick(mob.level()) / 24000L;
        if (today == gaveUpDay) {
            return false;
        }
        seat = FarmTicker.studySeatOf(mob);
        return seat != null;
    }

    @Override
    public boolean canContinueToUse() {
        return onDuty() && seat != null && FarmTicker.studySeatOf(mob) != null;
    }

    @Override
    public void start() {
        stuck = 0;
        lastPos = mob.blockPosition();
        mob.setWorkAnchor(seat);
        mob.setActivity("수업");
    }

    @Override
    public void stop() {
        mob.setWorkAnchor(null);
        seat = null;
    }

    @Override
    public void tick() {
        if (seat == null) {
            return;
        }
        if (mob.blockPosition().distSqr(seat) <= ARRIVE_SQ) {
            mob.getNavigation().stop();
            mob.getLookControl().setLookAt(seat.getX() + 0.5, seat.getY() + 1.0, seat.getZ() + 0.5);
            long day = SimTime.tick(mob.level()) / 24000L;
            if (!mob.satInClassToday(day)) {
                mob.creditStudyDay(day);
                com.evosim.mod.log.SimEvents.event(mob, "수업", String.format(
                        "착석 @%d,%d — 출석 %.0f/%d일 (%s 과정)", seat.getX(), seat.getZ(), mob.getStudyCredit(),
                        com.evosim.core.University.courseDays(mob.getStudyTarget()),
                        com.evosim.core.Degree.name(mob.getStudyTarget())));
            }
            stuck = 0;
            return;
        }
        if (mob.getNavigation().isDone()) {
            mob.getNavigation().moveTo(seat.getX() + 0.5, seat.getY(), seat.getZ() + 0.5, 1.0);
        }
        if (mob.blockPosition().equals(lastPos)) {
            if (++stuck >= STUCK_GIVE_UP) {
                gaveUpDay = SimTime.tick(mob.level()) / 24000L;
                com.evosim.mod.log.SimEvents.event(mob, "수업", String.format(
                        "좌석 @%d,%d 에 닿지 못해 오늘 결석(%d틱 무진전)", seat.getX(), seat.getZ(), stuck));
                seat = null;
            }
        } else {
            stuck = 0;
            lastPos = mob.blockPosition();
        }
    }
}
