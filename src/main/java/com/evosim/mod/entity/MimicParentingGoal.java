package com.evosim.mod.entity;

import com.evosim.core.LifeStage;
import com.evosim.core.ParentingClass;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 육아 goal (설계서 육아 클래스, §7·§8). 자식이 유아기일 때, 양육 성향의 <b>성년(성별 무관 — 자기
 * 육아 클래스가 결정)</b>을 돌봄 반경 안으로 구속한다. 적극(반경0)=거처에서 안 나옴, 무시=자유 배회.
 *
 * <p>성별 강제 없음 — 육아 클래스는 남/여 슬롯이 따로 유전되므로, 어떤 성별-클래스 조합이 살아남는지는
 * <b>경제가 선택</b>한다(육아 구속 = 채집 포기: 남성 채집 1.5×를 버리는 아빠 육아는 자연 벌점,
 * 부부 둘 다 적극이면 수입 0 → 도태). 예상: 남무심·여적극만 자연선택 — census 육아성향 통계로 관측.
 */
public class MimicParentingGoal extends Goal {

    private final MimicEntity mob;

    public MimicParentingGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (mob.getStage() != LifeStage.ADULT
                || mob.getHomePos() == null || mob.getIndividual() == null) {
            return false; // 성별 게이트 없음 — 자기 육아 클래스(성별 슬롯 발동)가 전부 결정
        }
        ParentingClass pc = mob.getIndividual().parentingCare();
        if (pc == ParentingClass.NEGLECTFUL) {
            return false; // 무시 = 무제한 → 구속하지 않음(자유 배회)
        }
        if (!mob.hasInfantAtHome()) {
            return false; // 유아 자식이 있을 때만 작동
        }
        double r = pc.careRadius();
        return mob.blockPosition().distSqr(mob.getHomePos()) > r * r; // 돌봄 반경 벗어남
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        BlockPos home = mob.getHomePos();
        if (home != null) {
            mob.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1.0);
        }
    }
}
