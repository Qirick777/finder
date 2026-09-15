package com.evosim.mod.entity;

import com.evosim.core.LifeStage;
import com.evosim.core.Schedule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * <b>목사 전업</b>(교회 고도화, 사용자 승인) — 낮(근무)·배회 시간에 제 교회 자리에 상주한다.
 *
 * <p>예배는 배회 시간에 열리므로(MimicVisitGoal) 그때 교회에 있어야 하고, 낮에도 밭·채집 대신
 * 교회를 지킨다(전업 — MimicFarmGoal·MimicForageGoal 이 목사를 거른다). 밤·취침은 귀가.
 * 리시는 마실 앵커를 빌려 쓴다 — 마실 goal 은 목사를 뽑지 않으니 충돌이 없다.
 */
public class MimicPastorGoal extends Goal {
    private static final double ARRIVE_SQ = 2.25;
    private final MimicEntity mob;
    /** 이 goal 이 리시 앵커를 세워 둔 상태 — 선점 정지(stop)에서는 지우지 않고 자연 종료에서만 내린다. */
    private boolean anchored;
    private BlockPos seat;

    public MimicPastorGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private boolean onDuty() {
        if (mob.getIndividual() == null || mob.getStage() != LifeStage.ADULT || mob.isFastSettle()
                || mob.isBuilding() || mob.isCritical() || !FarmTicker.isPastor(mob)) {
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
        seat = pastorSeat();
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
        mob.setActivity("목사");
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
        // 자리에서 끼니 — 헌금(없으면 주인 보전). 전업이라 이것 말고는 낮에 먹을 길이 없다.
        if (mob.level() instanceof ServerLevel sl && mob.tickCount % 40 == 0) {
            FarmTicker.feedPastor(sl, mob);
        }
    }

    /** 제 교회(staffId == 나)의 첫 자리 — 없으면 앵커. */
    private BlockPos pastorSeat() {
        if (!(mob.level() instanceof ServerLevel sl)) {
            return null;
        }
        long id = mob.getIndividual().id();
        for (FacilityStore.Entry e : FacilityStore.get(sl).all()) {
            if (e.kind == FacilityTemplate.Kind.CHURCH && e.staffId == id) {
                var tpl = FacilityTemplate.of(sl, e.kind, e.rotation, e.mirrored);
                if (tpl.isPresent() && !tpl.get().seats().isEmpty()) {
                    return e.pos.offset(tpl.get().seats().get(0));
                }
                return e.pos;
            }
        }
        return null;
    }
}
