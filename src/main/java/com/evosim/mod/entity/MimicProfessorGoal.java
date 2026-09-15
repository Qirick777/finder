package com.evosim.mod.entity;

import com.evosim.core.LifeStage;
import com.evosim.core.Schedule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * <b>교수</b>(지식인 P2) — 근무 시간은 강단({@link FarmTicker#podiumOf}), 배회 시간은 연구실
 * ({@link FarmTicker#labOf}). 밭·채집은 안 하고 끼니는 대학 계정이 댄다(feedProfessor).
 */
public class MimicProfessorGoal extends Goal {
    private static final double ARRIVE_SQ = 2.25;
    private final MimicEntity mob;
    /** 이 goal 이 리시 앵커를 세워 둔 상태 — 선점 정지(stop)에서는 지우지 않고 자연 종료에서만 내린다. */
    private boolean anchored;
    private BlockPos lastGo;
    private BlockPos spot;
    private boolean lecture;
    private long startedDay = -1L;
    private int probe;

    public MimicProfessorGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private Schedule.Phase phase() {
        return Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime());
    }

    private boolean onDuty() {
        if (mob.getIndividual() == null || mob.getStage() != LifeStage.ADULT || mob.isFastSettle()
                || mob.isBuilding() || mob.isCritical() || !FarmTicker.isProfessor(mob)) {
            return false;
        }
        Schedule.Phase ph = phase();
        return ph == Schedule.Phase.WORK || ph == Schedule.Phase.WANDER;
    }

    private BlockPos pick() {
        boolean lec = phase() == Schedule.Phase.WORK;
        BlockPos p = lec ? FarmTicker.podiumOf(mob) : FarmTicker.labOf(mob);
        if (p == null) {
            p = lec ? FarmTicker.labOf(mob) : FarmTicker.podiumOf(mob); // 자리가 모자라면 남은 쪽
        }
        lecture = lec;
        return p;
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
        spot = pick();
        return spot != null || release();
    }

    @Override
    public boolean canContinueToUse() {
        if (!onDuty() || spot == null) {
            return false;
        }
        if (lecture != (phase() == Schedule.Phase.WORK)) {
            spot = pick(); // 강의 ↔ 연구 전환
            if (spot == null) {
                return false;
            }
            mob.setVisitAnchor(spot);
            mob.setActivity(lecture ? "강의" : "연구");
        }
        return true;
    }

    @Override
    public void start() {
        // 리시 앵커는 문 앞부터(학생 goal 과 같은 이유 — 건물 안 자리를 앵커로 두면 리시가 담장에 붙인다)
        BlockPos hop0 = mob.level() instanceof ServerLevel sl0 ? FarmTicker.entryFor(sl0, mob) : null;
        mob.setVisitAnchor(hop0 != null ? hop0 : spot);
        anchored = true;
        mob.setActivity(lecture ? "강의" : "연구");
        probe = 0;
        long day = SimTime.tick(mob.level()) / 24000L;
        if (day != startedDay) {
            startedDay = day;
            com.evosim.mod.log.SimEvents.event(mob, lecture ? "강의" : "연구", String.format(
                    "자리 @%d,%d 로 — 거리 %.0f", spot.getX(), spot.getZ(), Math.sqrt(mob.blockPosition().distSqr(spot))));
        }
    }

    @Override
    public void stop() {
        // 앵커는 여기서 지우지 않는다 — 리시(2)가 호위를 인수하는 순간 stop 이 불리는데 그때 지우면 앵커가
        // 거처로 돌아가 리시가 되끌고, 다시 이 goal 이 서는 줄다리기가 된다(밭일·등교 goal 의 stop 과 같은 경고).
        // 실측(무대 4, d19): 학생이 집 근처(−22,−25)와 좌석 사이를 오가기만 하고 하루 종일 착석 0.
        spot = null;
    }


    /** 문 앞 칸에 닿았나 — <b>수평</b> 2.5블록. 문 앞 칸은 도면 기준 높이라 바깥 땅과 한 칸 어긋날 수 있어
     *  거리제곱에 높이를 넣으면 영영 못 닿는다(실측 무대 7: 현관 앞 2칸 @-32,-65 에서 하루 종일 정지). */
    private boolean nearHop(BlockPos hop) {
        double dx = mob.getX() - (hop.getX() + 0.5);
        double dz = mob.getZ() - (hop.getZ() + 0.5);
        return dx * dx + dz * dz <= 9.0; // 문 칸 기준 3블록 — 경유/직행이 문턱에서 뒤집히지 않게
    }

    @Override
    public void tick() {
        if (spot == null) {
            return;
        }
        if (mob.blockPosition().distSqr(spot) > ARRIVE_SQ) {
            // 건물 밖이면 문 앞 칸을 먼저 밟는다(학생 goal 과 같은 이유). 리시 앵커도 그 칸.
            BlockPos hop = mob.level() instanceof ServerLevel sl0 ? FarmTicker.entryFor(sl0, mob) : null;
            BlockPos go = hop != null && !nearHop(hop) ? hop : spot;
            mob.setVisitAnchor(go);
            if (mob.getNavigation().isDone() || !go.equals(lastGo)) {
                mob.getNavigation().moveTo(go.getX() + 0.5, go.getY(), go.getZ() + 0.5, 1.0);
                lastGo = go;
            }
            if (++probe % 600 == 0) {
                var path = mob.getNavigation().createPath(spot, 0);
                BlockPos bp = mob.blockPosition();
                com.evosim.mod.log.SimEvents.event(mob, "강단진단", String.format(
                        "내 @%d,%d y%d · 자리 @%d,%d y%d · 거리 %.0f · 네비%s · %s", bp.getX(), bp.getZ(), bp.getY(),
                        spot.getX(), spot.getZ(), spot.getY(), Math.sqrt(bp.distSqr(spot)),
                        mob.getNavigation().isDone() ? "끝남" : "진행",
                        path == null ? "경로없음" : path.canReach() ? "도달가능" : "부분경로"));
            }
            return;
        }
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(spot.getX() + 0.5, spot.getY() + 1.0, spot.getZ() + 0.5);
        if (mob.level() instanceof ServerLevel sl && mob.tickCount % 40 == 0) {
            FarmTicker.feedProfessor(sl, mob);
        }
    }
}
