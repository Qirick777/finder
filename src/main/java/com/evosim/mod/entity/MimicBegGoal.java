package com.evosim.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * <b>구걸</b> — 일자리를 못 얻은 자가 남의 집 문간까지 걸어가 하루치를 얻는다.
 *
 * <p>이 goal 은 <b>목적지를 고르지 않는다.</b> 고르는 것은 {@link FarmTicker} 의 새벽 정산이고
 * (긴급고용에서 밭 자리가 하나도 안 나온 사람만 여기로 온다), 결과는 개체에
 * {@link MimicEntity#setBegTarget} 로 못박혀 있다. 그래서 이 클래스가 하는 일은 두 가지뿐이다 —
 * <b>걷는 것</b>과 <b>도착해서 받는 것</b>.
 *
 * <p><b>왜 목적지를 goal 이 안 고르는가.</b> 후보 저장고는 남의 가구 살림이라 하루에도 여러 번
 * 출렁인다. goal 이 매번 다시 고르면 한 걸음 뗄 때마다 최적해가 갈려 제자리에서 몸만 튼다 —
 * 마실·복귀가 초 간격으로 갈아타며 움찔거리던 것과 똑같은 병이다. 하루 한 번 밖에서 정하면
 * 그 진동이 <b>구조적으로</b> 생길 수 없다.
 *
 * <p><b>리시와의 관계.</b> 목적지는 {@link MimicEntity#roamAnchor()} 가 그대로 돌려주므로
 * 활동반경 리시(우선순위 2)는 방해자가 아니라 <b>호위</b>가 된다 — 반경 밖 은인 집이어도
 * 리시가 도착 5블록까지 끌어다 놓고({@link MimicLeashGoal} 의 캐러밴 절), 이 goal 은 마지막
 * 접근과 수령만 맡는다. 구혼 여행·노인 마실이 쓰는 바로 그 패턴이다.
 */
public class MimicBegGoal extends Goal {

    /** 도착 판정(5블록) — {@link MimicLeashGoal} 의 캐러밴 해제 거리와 <b>같아야</b> 한다.
     *  좁으면 리시가 놓은 자리에서 이 goal 이 도착으로 못 읽어 문간에 붙박인다. */
    private static final double ARRIVE_SQ = 25.0;

    private final MimicEntity mob;

    public MimicBegGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return mob.isBegging() && mob.getIndividual() != null
                && !mob.isBuilding() && !mob.isUnderThreat()
                && mob.level() instanceof ServerLevel;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void stop() {
        // 앵커를 <b>건드리지 않는다.</b> 여기서 끊기는 대부분은 리시(2)의 선점이고, 그때 앵커를
        // 지우면 리시가 볼 목적지가 사라져 그 자리에서 거처로 되끌린다. 해제는 수령·허탕
        // (FarmTicker.receiveAlms)과 해질녘(isBegging 의 시간대 관문)만이 한다.
    }

    @Override
    public void tick() {
        BlockPos t = mob.getBegAnchor();
        if (t == null) {
            return;
        }
        if (mob.blockPosition().distSqr(t) > ARRIVE_SQ) {
            mob.getLookControl().setLookAt(t.getX() + 0.5, t.getY() + 1.0, t.getZ() + 0.5);
            mob.getNavigation().moveTo(t.getX() + 0.5, t.getY(), t.getZ() + 0.5, 1.0);
            return;
        }
        FarmTicker.receiveAlms((ServerLevel) mob.level(), mob); // 수령·기록·해제 전부 여기서
    }
}
