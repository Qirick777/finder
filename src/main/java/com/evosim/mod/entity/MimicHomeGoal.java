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
            return !mob.isCritical(); // 밤 대기 점유(배회 왕복 방지) — 단 위급이면 R6 채집(6)에 양보
        }
        // 취침 구간도 <b>같은 예외</b>를 쓴다. 종전에는 NIGHT 에만 위급 양보가 있고 SLEEP 에는
        // 없었는데, SLEEP 은 tod 14000~기상으로 하루의 40%가 넘는 가장 긴 구간이다. 이 goal 은
        // 우선순위 4라 밭일(6)·채집(7)보다 먼저 MOVE 를 가져가므로, 위급한 개체가 그 긴 밤 내내
        // 거처로 끌려가 굶어 죽었다(실측: 위기 상태에서 밤에 밭으로 못 감). 게다가 3블록 경계에서
        // 왕복이 생긴다 — 3블록 밖이면 이 goal 이 집으로 끌고, 안으로 들어오면 해제되어 밭일 goal 이
        // 12블록 밖 밭으로 출발시키고, 다시 3블록을 벗어나면 되끌린다. 밤새 제자리 왕복이다.
        // 저장고에 밥이 있으면 우선순위 3인 MimicReturnGoal 이 먼저 데려가므로 양보해도 안전하다.
        boolean homeTime = ph == null || ph == Schedule.Phase.SLEEP;
        return homeTime && !mob.isCritical()
                && mob.blockPosition().distSqr(home) > 9.0; // 3블록 밖이면 귀환
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
            return !mob.isCritical(); // 밤 내내 자리 지킴 — 위급 전이 시 즉시 양보(R6)
        }
        // 취침 구간은 종전대로 2블록 안에서 물러남 — 이 goal(4)이 계속 쥐면 우선순위가 낮은
        // 취침 goal(5)이 영영 못 켜진다(자리 지킴을 밤에만 한정하는 이유). 위급 양보는 canUse
        // 와 동일 — 진행 중에 위급으로 전이해도 즉시 손을 떼야 밭일·채집이 인수할 수 있다.
        boolean homeTime = ph == null || ph == Schedule.Phase.SLEEP;
        return homeTime && !mob.isCritical() && mob.blockPosition().distSqr(home) > ARRIVED_SQR;
    }

    /**
     * 도착 판정 반경². 종전 4.0(=2블록)은 천막 시절 값이다 — 천막은 앵커가 곧 실내였다.
     * 스키메틱은 <b>문이 앵커에서 정확히 2블록</b>이라, 2블록에서 손을 떼면 개체가 문간에
     * 그대로 서서 밤을 보낸다(실측 스크린샷). 앵커 칸까지 들어가야 집 안에 선다.
     */
    private static final double ARRIVED_SQR = 2.0;

    @Override
    public void tick() {
        BlockPos home = mob.getHomePos();
        if (home == null) {
            return;
        }
        if (mob.blockPosition().distSqr(home) > ARRIVED_SQR) {
            mob.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0.9);
        } else if (!mob.getNavigation().isDone()) {
            mob.getNavigation().stop(); // 도착 — 밤 대기(왕복 없음)
        }
    }
}
