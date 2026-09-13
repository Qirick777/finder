package com.evosim.mod.entity;

import com.evosim.core.Church;
import com.evosim.core.LifeStage;
import com.evosim.core.Schedule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.List;

/**
 * <b>선교</b>(교회 고도화, 사용자 승인) — 선교사는 평소 제 일(소작·채집)을 하다가 <b>배회 시간</b>에만
 * 교회 반경 {@link Church#MISSION_RANGE} 안, 교회 주인 사슬 밖 가구를 찾아가 신세를 심는다.
 * 하루 {@link Church#MISSION_PER_DAY}곳. 도착이 곧 방문 성립(FarmTicker.recordMission).
 *
 * <p>왜 "사슬 밖"만인가: 이미 주인이 있는 가구도 방문은 하지만, 신세 비교에서 소작·봉토 신세가
 * 더 크면 안 넘어온다. 걸음을 아끼려 표적은 사슬 밖으로 좁힌다(주인 없음 또는 주인의 주인이 교회
 * 주인이 아닌 가구).
 */
public class MimicMissionGoal extends Goal {
    private static final double ARRIVE_SQ = 9.0;
    private static final int STUCK_GIVE_UP = 400;
    private final MimicEntity mob;
    private BlockPos church;
    private BlockPos target;
    private long headId;
    private long churchOwner;
    private int stuck;
    private BlockPos lastPos;
    /** 표적 없음을 하루 한 번만 적기 위한 기억(휘발). */
    private long holdLoggedDay = -1L;
    private int seenInRange;
    private int seenInChain;
    private int seenVisited;
    private int seenNoHead;

    public MimicMissionGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private boolean free() {
        if (mob.getIndividual() == null || mob.getStage() != LifeStage.ADULT || mob.isFastSettle()
                || mob.isBuilding() || mob.isCritical() || mob.isCaregiverBound()
                || mob.isCourtTravel() || mob.getHomePos() == null) {
            return false;
        }
        return Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime()) == Schedule.Phase.WANDER;
    }

    @Override
    public boolean canUse() {
        if (!free()) {
            return false;
        }
        church = FarmTicker.missionChurchOf(mob);
        if (church == null || !FarmTicker.missionQuotaLeft(mob)) {
            return false;
        }
        if (pickTarget()) {
            return true;
        }
        // 조용히 실패하지 않는다 — 하루 한 줄, 왜 표적이 없는지(반경 안 가구 · 사슬 안 · 오늘 방문).
        long day = mob.level().getDayTime() / 24000L;
        if (holdLoggedDay != day) {
            holdLoggedDay = day;
            com.evosim.mod.log.SimEvents.event(mob, "선교보류", String.format(
                    "표적 없음 — 반경 %.0f 안 가구 %d · 사슬 안 %d · 오늘 방문 %d · 대표 없음 %d",
                    Church.MISSION_RANGE, seenInRange, seenInChain, seenVisited, seenNoHead));
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return free() && target != null;
    }

    @Override
    public void start() {
        stuck = 0;
        lastPos = mob.blockPosition();
        mob.setVisitAnchor(target);
        mob.setActivity("선교");
    }

    @Override
    public void stop() {
        mob.setVisitAnchor(null);
        target = null;
    }

    @Override
    public void tick() {
        if (target == null) {
            return;
        }
        if (mob.blockPosition().distSqr(target) > ARRIVE_SQ) {
            if (mob.getNavigation().isDone()) {
                mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
            }
            if (mob.blockPosition().equals(lastPos)) {
                if (++stuck >= STUCK_GIVE_UP) {
                    com.evosim.mod.log.SimEvents.event(mob, "선교", String.format(
                            "가구 @%d,%d 에 닿지 못해 포기(%d틱 무진전)", target.getX(), target.getZ(), stuck));
                    target = null;
                }
            } else {
                stuck = 0;
                lastPos = mob.blockPosition();
            }
            return;
        }
        if (mob.level() instanceof ServerLevel sl) {
            FarmTicker.recordMission(sl, mob, target, churchOwner, headId);
        }
        target = null; // 다음 표적은 canUse 가 다시 고른다(하루 정원 안에서)
    }

    /** 표적: 교회 반경 안, 오늘 안 간 집, 대표의 주인이 교회 주인 사슬 밖. 가까운 순. */
    private boolean pickTarget() {
        if (!(mob.level() instanceof ServerLevel sl)) {
            return false;
        }
        FacilityStore.Entry ch = null;
        for (FacilityStore.Entry e : FacilityStore.get(sl).all()) {
            if (e.pos.equals(church)) {
                ch = e;
                break;
            }
        }
        if (ch == null || ch.ownerId == 0L) {
            return false;
        }
        churchOwner = ch.ownerId;
        FarmStore fs = FarmStore.get(sl);
        List<MimicEntity> adults = new java.util.ArrayList<>(sl.getEntities(
                com.evosim.mod.reg.ModEntities.MIMIC.get(),
                m -> m.isAlive() && m.getIndividual() != null && m.getHomePos() != null
                        && m.getStage() == LifeStage.ADULT));
        BlockPos best = null;
        long bestHead = 0L;
        double bestD = Double.MAX_VALUE;
        double r2 = Church.MISSION_RANGE * Church.MISSION_RANGE;
        seenInRange = 0;
        seenInChain = 0;
        seenVisited = 0;
        seenNoHead = 0;
        for (BlockPos home : HomeStore.get(sl).positions()) {
            if (home.distSqr(church) > r2 || home.equals(mob.getHomePos())) {
                continue;
            }
            seenInRange++;
            if (FarmTicker.missionVisitedToday(home)) {
                seenVisited++;
                continue;
            }
            // 가구 대표 = 그 집 성년 중 밭 최다(없으면 첫 성년)
            long head = 0L;
            int headTiles = -1;
            for (MimicEntity a : adults) {
                if (a.getHomePos().equals(home)) {
                    int t = fs.ownedTiles(a.getIndividual().id());
                    if (t > headTiles) {
                        headTiles = t;
                        head = a.getIndividual().id();
                    }
                }
            }
            if (head == 0L || head == churchOwner) {
                seenNoHead++;
                continue;
            }
            long p = FarmTicker.patronNow(head);
            boolean inChain = p == churchOwner
                    || (p != 0L && FarmTicker.patronNow(p) == churchOwner);
            if (inChain) {
                seenInChain++;
                continue;
            }
            double d = home.distSqr(mob.blockPosition());
            if (d < bestD) {
                bestD = d;
                best = home;
                bestHead = head;
            }
        }
        if (best == null) {
            return false;
        }
        target = best;
        headId = bestHead;
        return true;
    }
}
