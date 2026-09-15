package com.evosim.mod.entity;

import com.evosim.core.LifeStage;
import com.evosim.core.Schedule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** <b>의사</b>(병원 P6) — 낮·배회 시간에 진료 자리(양조기 옆 의자)에 상주. 끼니는 병원 계정. */
public class MimicDoctorGoal extends Goal {
    private static final double ARRIVE_SQ = 2.25;
    private final MimicEntity mob;
    /** 이 goal 이 리시 앵커를 세워 둔 상태 — 선점 정지(stop)에서는 지우지 않고 자연 종료에서만 내린다. */
    private boolean anchored;
    private BlockPos seat;

    public MimicDoctorGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private boolean onDuty() {
        if (mob.getIndividual() == null || mob.getStage() != LifeStage.ADULT || mob.isFastSettle()
                || mob.isBuilding() || mob.isCritical() || !FarmTicker.isDoctor(mob)) {
            return false;
        }
        Schedule.Phase ph = Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime());
        return ph == Schedule.Phase.WORK || ph == Schedule.Phase.WANDER;
    }

    /** 자연 종료 — 근무 밖·자리 없음이면 앵커를 내린다(리시 앵커가 거처/기숙사로 복원). */
    private boolean release() {
        if (anchored) {
            mob.setVisitAnchor(null);
            anchored = false;
        }
        return false;
    }

    @Override
    public boolean canUse() {
        if (!onDuty()) {
            return release();
        }
        seat = FarmTicker.clinicOf(mob);
        return seat != null || release();
    }

    @Override
    public boolean canContinueToUse() {
        return onDuty() && seat != null;
    }

    @Override
    public void start() {
        mob.setVisitAnchor(seat);
        anchored = true;
        mob.setActivity("진료");
    }

    @Override
    public void stop() {
        // 앵커는 여기서 지우지 않는다 — 리시(2)가 호위를 인수하는 순간 stop 이 불리는데 그때 지우면 앵커가
        // 거처로 돌아가 리시가 되끌고, 다시 이 goal 이 서는 줄다리기가 된다(밭일·등교 goal 의 stop 과 같은 경고).
        // 실측(무대 4, d19): 학생이 집 근처(−22,−25)와 좌석 사이를 오가기만 하고 하루 종일 착석 0.
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
            FarmTicker.feedDoctor(sl, mob);
        }
    }
}
