package com.evosim.mod.entity;

import com.evosim.core.LifeStage;
import com.evosim.core.Schedule;
import com.evosim.mod.encounter.Encounter;
import com.evosim.mod.encounter.EncounterContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 자녀 놀아주기 goal (배회 생활 v1 — 설계 계획서 v2 §4). 배회 시간의 유휴 부모가 자기
 * 유아·소년 자식 곁으로 가 잠시 놀아준다. 도착 시 {@link Encounter#begin} 경유(단일 관문 —
 * 추후 신분·요청 등도 같은 순간에 얹힘). 실효: 체류 = 성년 근접 → 급식 출석·방치 샘플 충족
 * (식량 수치 무풍 — 생존 확률만 개선).
 *
 * <p>부모 교대: 짝수 날 부친·홀수 날 모친(dayTime 일 기준 — 무대 제어 가능). 쿨다운 1일
 * (gameTime 일 기준 — 단조). 지정 돌봄자는 제외(이미 육아 goal 이 곁에 있음).
 */
public class MimicPlayGoal extends Goal {

    private static final int PLAY_TICKS = 240;     // 체류 예산(12초) — WANDER 창의 4%
    private static final double FIND_RANGE = 48.0; // 자식 탐색 반경
    private static final double ARRIVE = 2.5;      // 이 거리면 놀이 시작
    private static final double LOSE = 8.0;        // 자식이 이만큼 벗어나면 종료

    private final MimicEntity mob;
    private MimicEntity child;
    private int playLeft;
    private boolean began;

    public MimicPlayGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.getStage() != LifeStage.ADULT || mob.getIndividual() == null
                || mob.getHomePos() == null || mob.isBuilding() || mob.isFastSettle()
                || mob.isCourtTravel() || mob.isCritical() || mob.isCaregiverBound()) {
            return false;
        }
        if (Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime())
                != Schedule.Phase.WANDER) {
            return false;
        }
        long gameDay = com.evosim.mod.entity.SimTime.tick(mob.level()) / 24000L;
        if (mob.lastPlayDay() >= gameDay) {
            return false; // 쿨다운 1일(단조 시계)
        }
        // 부모 교대 — 짝수 날 부친·홀수 날 모친(무대가 제어 가능한 dayTime 일 기준).
        long todDay = mob.level().getDayTime() / 24000L;
        if ((todDay % 2 == 0) != !mob.isFemale()) {
            return false;
        }
        child = nearestOwnChild();
        return child != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (child == null || !child.isAlive() || mob.distanceToSqr(child) > LOSE * LOSE) {
            return false;
        }
        if (Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime())
                != Schedule.Phase.WANDER) {
            return false;
        }
        return !began || playLeft > 0;
    }

    @Override
    public void start() {
        began = false;
        playLeft = PLAY_TICKS;
    }

    @Override
    public void tick() {
        if (child == null) {
            return;
        }
        mob.getLookControl().setLookAt(child, 30.0F, 30.0F);
        if (mob.distanceToSqr(child) > ARRIVE * ARRIVE) {
            if (!began) {
                mob.getNavigation().moveTo(child, 1.0);
            }
            return;
        }
        if (!began) {
            began = true;
            // 도착 = 놀이 시작 — 오늘 완료 마킹(중단돼도 재접근 반복 안 함) + 조우 관문 경유.
            mob.setLastPlayDay(com.evosim.mod.entity.SimTime.tick(mob.level()) / 24000L);
            if (mob.level() instanceof ServerLevel sl) {
                Encounter.begin(sl, mob, child, EncounterContext.Place.HOME,
                        EncounterContext.Occasion.PLAY, PLAY_TICKS);
            }
        }
        playLeft--;
        child.getLookControl().setLookAt(mob, 30.0F, 30.0F); // 자식도 부모를 봄(AI 비침습)
        if (playLeft % 20 == 0) {
            mob.swing(InteractionHand.MAIN_HAND);
        }
    }

    @Override
    public void stop() {
        child = null;
        began = false;
    }

    /** 같은 거처 소속·48블록 내의 내 유아·소년 자식 중 최근접. */
    private MimicEntity nearestOwnChild() {
        long myId = mob.getIndividual().id();
        MimicEntity best = null;
        double bd = Double.MAX_VALUE;
        for (MimicEntity m : mob.level().getEntitiesOfClass(MimicEntity.class,
                mob.getBoundingBox().inflate(FIND_RANGE))) {
            if (m == mob || !m.isAlive() || m.getIndividual() == null) {
                continue;
            }
            if (m.getStage() != LifeStage.INFANT && m.getStage() != LifeStage.BOY) {
                continue;
            }
            if (!mob.getHomePos().equals(m.getHomePos())) {
                continue;
            }
            if (m.getIndividual().parentAId() != myId && m.getIndividual().parentBId() != myId) {
                continue;
            }
            double d = mob.distanceToSqr(m);
            if (d < bd) {
                bd = d;
                best = m;
            }
        }
        return best;
    }
}
