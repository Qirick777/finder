package com.evosim.mod.entity;

import com.evosim.core.Individual;
import com.evosim.core.Schedule;
import com.evosim.mod.stage.StageObserver;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 구애 이동·시도 goal (구애 사양서 v2 §2 COURTING). 인식·후보 등록은 엔티티 tick({@code mateTick})이
 * 노동·배회 내내 하고, 이 goal 은 <b>배회 시간(구애 시간)</b>에만 후보를 매력 순으로 찾아가 구애한다.
 *
 * <p>졸졸 안 따라다님: 접촉하면 판정 1회 → 거절이면 그 상대를 하루 쿨다운(눈낮춤 재시도)하고 다음 후보로.
 * 못 따라잡으면(APPROACH_TIMEOUT) 역시 포기. 후보 소진 시 SEARCHING 복귀(탐색 타이머 리셋).
 */
public class MimicCourtshipGoal extends Goal {

    private static final double CONTACT = 2.5;
    private static final int APPROACH_TIMEOUT = 80;

    private final MimicEntity mob;
    private int approachTicks;

    public MimicCourtshipGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Individual ind = mob.getIndividual();
        if (ind == null || !mob.isSingleAdult() || !courtTime(ind)) {
            return false;
        }
        // 사별·미혼 1인 가구에 유아가 있으면 육아 구속이 걸린다. 구애는 짝을 찾아 멀리
        // 나가는 일이라 육아(우선순위 1)에 반드시 선점돼 왕복만 한다 — 아이를 두고는 못
        // 나선다는 뜻이기도 하다. 구속이 풀리면(유아가 자라거나 공동 돌봄자가 생기면) 다시 열린다.
        if (mob.careLeashed()) {
            return false;
        }
        return mob.isSearchReady() && mob.hasCandidate();
    }

    @Override
    public boolean canContinueToUse() {
        Individual ind = mob.getIndividual();
        return ind != null && mob.isSingleAdult() && courtTime(ind) && mob.hasCandidate();
    }

    /** 구애 시간대 — 평상시 배회, 무대 검증 중엔 항상. */
    private boolean courtTime(Individual ind) {
        return StageObserver.isActive()
                || Schedule.phaseAt(ind, mob.level().getDayTime()) == Schedule.Phase.WANDER;
    }

    @Override
    public void start() {
        mob.setMateState(MateState.COURTING);
        approachTicks = 0;
    }

    @Override
    public void stop() {
        mob.setCourtTargetId(-1);
        if (!mob.hasCandidate()) {
            mob.resetSearchTimer();          // 후보 소진 → 다시 탐색
        }
        if (mob.getMateState() == MateState.COURTING) {
            mob.setMateState(MateState.SEARCHING); // 후보가 남아도 COURTING 채 잔류하지 않게
        } // (성사 시엔 pairWith 가 먼저 PAIRED 로 바꿔 이 복귀가 건드리지 않음)
    }

    @Override
    public void tick() {
        MimicEntity target = mob.currentBestCandidate();
        if (target == null) {
            return; // 유효 후보 없음 → canContinue 가 종료
        }
        mob.setCourtTargetId(target.getId());
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (mob.distanceToSqr(target) > CONTACT * CONTACT) {
            if (++approachTicks > APPROACH_TIMEOUT) {
                mob.backOffFrom(target.getId()); // 일시 회피(쿨다운 후 재시도) — 영구 거절 아님
                approachTicks = 0;
            } else {
                mob.getNavigation().moveTo(target, 1.1);
            }
            return;
        }
        // 접촉 → 구애 요청 1회.
        approachTicks = 0;
        mob.getNavigation().stop();
        if (!target.receiveCourtship(mob)) {
            mob.giveUpOn(target.getId());      // 거절 → 하루 쿨다운(눈낮춤 §10 재시도), 다음 후보
        }
        // 수락이면 pairWith 로 양쪽 PAIRED → canContinue 종료
    }
}
