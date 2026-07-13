package com.evosim.mod.entity;

import com.evosim.core.Schedule;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 거처 귀환 goal (설계서 §3 §16). 거처가 있는 미믹은 <b>밤(귀가)·취침</b> 구간에 거처 좌표로 수렴한다
 * → 밤에 가족이 한 곳에 뭉쳐 정산(§4). 낮(노동·배회)에는 풀려나 채집·구애하러 돌아다닌다.
 */
public class MimicHomeGoal extends Goal {

    private final MimicEntity mob;

    public MimicHomeGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /** 현재 스케줄 구간(개체 데이터 없으면 null — 구 동작 폴백). */
    private Schedule.Phase phase() {
        return mob.getIndividual() == null ? null
                : Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime());
    }

    @Override
    public boolean canUse() {
        if (mob.isCourtTravel()) {
            return false; // 구혼 여행 중 — 밤에도 타향에 머묾(리시 앵커가 그쪽)
        }
        BlockPos home = mob.getHomePos();
        if (home == null) {
            return false;
        }
        Schedule.Phase ph = phase();
        if (ph == Schedule.Phase.NIGHT) {
            return true; // 밤엔 도착해 있어도 점유 — 배회(7)가 집 밖 2~3블록으로 끌어내던 왕복 방지
        }
        boolean homeTime = ph == null || ph == Schedule.Phase.SLEEP;
        return homeTime && mob.blockPosition().distSqr(home) > 9.0; // 3블록 밖이면 귀환
    }

    @Override
    public boolean canContinueToUse() {
        if (mob.isCourtTravel()) {
            return false;
        }
        BlockPos home = mob.getHomePos();
        if (home == null) {
            return false;
        }
        Schedule.Phase ph = phase();
        if (ph == Schedule.Phase.NIGHT) {
            return true; // 밤 내내 자리 지킴
        }
        // 취침 구간은 종전대로 2블록 안에서 물러남 — 이 goal(4)이 계속 쥐면 우선순위가 낮은
        // 취침 goal(5)이 영영 못 켜진다(자리 지킴을 밤에만 한정하는 이유).
        boolean homeTime = ph == null || ph == Schedule.Phase.SLEEP;
        return homeTime && mob.blockPosition().distSqr(home) > 4.0;
    }

    @Override
    public void tick() {
        BlockPos home = mob.getHomePos();
        if (home == null) {
            return;
        }
        if (mob.blockPosition().distSqr(home) > 4.0) {
            mob.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0.9);
        } else if (!mob.getNavigation().isDone()) {
            mob.getNavigation().stop(); // 도착 — 밤 대기(왕복 없음)
        }
    }
}
