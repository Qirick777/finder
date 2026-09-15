package com.evosim.mod.entity;

import com.evosim.core.LifeStage;
import com.evosim.core.Schedule;
import com.evosim.mod.log.SimEvents;
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
    /** 이 goal 이 리시 앵커를 세워 둔 상태 — 선점 정지(stop)에서는 지우지 않고 자연 종료에서만 내린다. */
    private boolean anchored;
    private BlockPos seat;
    private BlockPos lastPos;
    private BlockPos lastGo;
    private int stuck;
    private long gaveUpDay = -1L;
    private long startedDay = -1L;
    private int probe;

    public MimicStudyGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private boolean onDuty() {
        if (mob.getIndividual() == null || mob.getStage() != LifeStage.ADULT || mob.isFastSettle()
                || mob.isBuilding() || mob.isCritical() || mob.isUnderThreat() || !mob.isStudent()
                || mob.isCaregiverBound()) {
            return false; // 돌봄 전담은 그날 결석 — 밤 정산이 중퇴로 정리한다
        }
        return Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime()) == Schedule.Phase.WORK;
    }

    /** 자연 종료 — 근무 밖·자리 없음이면 앵커를 내린다(리시 앵커가 거처/기숙사로 복원). */
    private boolean release() {
        if (anchored) {
            mob.setWorkAnchor(null);
            anchored = false;
        }
        return false;
    }

    @Override
    public boolean canUse() {
        if (!onDuty()) {
            return release();
        }
        long today = SimTime.tick(mob.level()) / 24000L;
        if (today == gaveUpDay) {
            return release(); // 오늘은 결석 — 리시가 기숙사/거처로 데려가게 앵커를 내린다
        }
        seat = FarmTicker.studySeatOf(mob);
        return seat != null || release();
    }

    @Override
    public boolean canContinueToUse() {
        return onDuty() && seat != null && FarmTicker.studySeatOf(mob) != null;
    }

    @Override
    public void start() {
        stuck = 0;
        lastPos = mob.blockPosition();
        lastGo = null;
        mob.setWorkAnchor(seat);
        anchored = true;
        mob.setActivity("수업");
        probe = 0;
        long day = SimTime.tick(mob.level()) / 24000L;
        if (day != startedDay) {
            startedDay = day;
            SimEvents.event(mob, "등교", String.format("좌석 @%d,%d 로 — 거리 %.0f", seat.getX(), seat.getZ(),
                    Math.sqrt(mob.blockPosition().distSqr(seat))));
        }
    }

    /** 왜 못 닿는지를 값으로 남긴다 — 학교 goal 에서 추측이 여러 번 틀렸던 경험 그대로. */
    private String diagnose() {
        var nav = mob.getNavigation();
        var path = nav.createPath(seat, 0);
        String p = path == null ? "경로없음"
                : String.format("%s(종점 @%d,%d 노드 %d)", path.canReach() ? "도달가능" : "부분경로",
                        path.getEndNode() == null ? 0 : path.getEndNode().x,
                        path.getEndNode() == null ? 0 : path.getEndNode().z, path.getNodeCount());
        BlockPos bp = mob.blockPosition();
        return String.format("내 @%d,%d y%d · 좌석 y%d · 거리 %.0f · 네비%s · %s", bp.getX(), bp.getZ(), bp.getY(),
                seat.getY(), Math.sqrt(bp.distSqr(seat)), nav.isDone() ? "끝남" : "진행", p);
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
        // 건물 밖이면 문 앞 칸을 먼저 밟는다 — 담장의 가장 가까운 점에 붙어 굳지 않게. 리시 앵커도 그 칸.
        BlockPos hop = mob.level() instanceof net.minecraft.server.level.ServerLevel sl
                ? FarmTicker.entryFor(sl, mob) : null;
        BlockPos go = hop != null && mob.blockPosition().distSqr(hop) > ARRIVE_SQ ? hop : seat;
        mob.setWorkAnchor(go);
        if (mob.getNavigation().isDone() || !go.equals(lastGo)) {
            mob.getNavigation().moveTo(go.getX() + 0.5, go.getY(), go.getZ() + 0.5, 1.0);
            lastGo = go;
        }
        if (++probe % 400 == 0) {
            SimEvents.event(mob, "등교진단", diagnose());
        }
        if (mob.blockPosition().equals(lastPos)) {
            if (++stuck >= STUCK_GIVE_UP) {
                gaveUpDay = SimTime.tick(mob.level()) / 24000L;
                com.evosim.mod.log.SimEvents.event(mob, "수업", String.format(
                        "좌석 @%d,%d 에 닿지 못해 오늘 결석(%d틱 무진전) — %s", seat.getX(), seat.getZ(), stuck,
                        diagnose()));
                seat = null;
            }
        } else {
            stuck = 0;
            lastPos = mob.blockPosition();
        }
    }
}
