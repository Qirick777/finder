package com.evosim.mod.entity;

import com.evosim.core.LifeStage;
import com.evosim.core.Schedule;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * <b>등하교</b> goal (P5b) — 등록된 소년이 노동 시간에 학교의 <b>제 자리</b>로 걸어가 머문다.
 *
 * <p>계획서 1.8: "채집·밭일 시간 시작 시 학교로, 종료 시 귀가." 시간이 끝나면 이 goal 이
 * 스스로 물러나고 기존 귀가 goal 이 이어받는다 — 돌아가는 길을 따로 만들지 않는다.
 *
 * <p><b>학교에 못 가는 소년은 기존대로 놀이 goal 이 잡는다</b>(계획서 1.8: "눈으로 구분된다").
 * 그래서 우선순위는 놀이보다 앞, 리시·전투보다 뒤다.
 *
 * <p>자리는 학생마다 다르다({@link FarmTicker#seatOf}). 학교 앵커 하나로 보내면 스무 명이
 * 한 칸에 뭉쳐 밀치기만 한다 — 도면의 독서대 앞 자리를 한 명씩 나눠 주면 저절로 흩어져 앉는다.
 */
public class MimicSchoolGoal extends Goal {

    /** 이 거리 안에 들면 도착 — 자리 한 칸에 정확히 서지 않아도 수업으로 친다. */
    private static final double ARRIVE_SQ = 4.0;

    /** 표적 무진전이 이 틱 지속되면 오늘 등교를 포기한다(막힌 자리에서 하루를 버리지 않게). */
    private static final int STUCK_GIVE_UP = 200;

    private final MimicEntity mob;
    private BlockPos seat;
    private BlockPos lastPos;
    private int stuckTicks;
    private long gaveUpDay = Long.MIN_VALUE;

    public MimicSchoolGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.getIndividual() == null || mob.isFastSettle() || mob.isBuilding()
                || mob.isUnderThreat() || mob.getStage() != LifeStage.BOY) {
            return false;
        }
        if (Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime())
                != Schedule.Phase.WORK) {
            return false;
        }
        long today = SimTime.tick(mob.level()) / 24000L;
        if (today == gaveUpDay) {
            return false; // 오늘은 이미 못 갔다 — 놀이 goal 에 넘긴다
        }
        seat = FarmTicker.seatOf(mob);
        return seat != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (mob.getIndividual() == null || mob.isUnderThreat()
                || Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime())
                        != Schedule.Phase.WORK) {
            return false; // 시간이 끝났다 — 귀가는 기존 goal 이 맡는다
        }
        return seat != null && FarmTicker.seatOf(mob) != null;
    }

    @Override
    public void start() {
        lastPos = mob.blockPosition();
        stuckTicks = 0;
    }

    @Override
    public void stop() {
        seat = null;
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return false;
    }

    @Override
    public void tick() {
        if (seat == null) {
            return;
        }
        double d = mob.blockPosition().distSqr(seat);
        if (d <= ARRIVE_SQ) {
            // 도착 — 자리에서 앞을 본다. 여기 머무는 것 자체가 수업이다(출석은 새벽에
            // 이미 장부로 확정됐고, 이 goal 은 그 장부를 눈에 보이게 하는 연출이다).
            mob.getNavigation().stop();
            mob.getLookControl().setLookAt(seat.getX() + 0.5, seat.getY() + 1.0,
                    seat.getZ() + 0.5);
            mob.creditSchoolDay(SimTime.tick(mob.level()) / 24000L); // 하루 한 번만
            stuckTicks = 0;
            return;
        }
        if (mob.getNavigation().isDone()) {
            mob.getNavigation().moveTo(seat.getX() + 0.5, seat.getY(), seat.getZ() + 0.5, 1.0);
        }
        // 무진전 감시 — 길이 막혔는데 하루 종일 벽에 붙어 있으면 그 아이는 굶지도 놀지도 못한다.
        BlockPos now = mob.blockPosition();
        if (now.equals(lastPos)) {
            if (++stuckTicks >= STUCK_GIVE_UP) {
                gaveUpDay = SimTime.tick(mob.level()) / 24000L;
                com.evosim.mod.log.SimEvents.event(mob, "등교", String.format(
                        "포기 — %d틱 무진전 (자리 @%d,%d 까지 %.0f블록)",
                        stuckTicks, seat.getX(), seat.getZ(), Math.sqrt(d)));
                seat = null;
            }
        } else {
            lastPos = now;
            stuckTicks = 0;
        }
    }
}
