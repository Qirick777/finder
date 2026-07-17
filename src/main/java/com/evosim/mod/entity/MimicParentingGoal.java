package com.evosim.mod.entity;

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

    /** 돌봄 반경의 작업 여유 — 적극(반경 0)도 거처 옆 정원(최원 그루 ≈5.4블록)까지는 "반경 안"으로
     *  본다. 이 여유가 없으면 반경 0은 어떤 위치에서도 "이탈"이라 이 goal(우선순위 1)이 영구 발동해
     *  정원 수확(적극 예외 — 지시 사양)이 원천 봉쇄된다(carex 실측). ForageGoal.withinCare 와 동일값. */
    public static final double CARE_SLACK = 6.5;

    private final MimicEntity mob;

    public MimicParentingGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        // 지정 돌봄자만(돌봄 충분성) — 커버리지로 해제된 부모·무시·무자녀는 자유. 종전엔 자체
        // 판정(비무시+유아)이라 해제된 부모까지 우선순위 1로 붙잡아 채집이 전면 봉쇄됐다(carex 실측).
        if (!mob.isCaregiverBound() || mob.getHomePos() == null || mob.getIndividual() == null) {
            return false;
        }
        double r = Math.max(mob.getIndividual().parentingCare().careRadius(), CARE_SLACK);
        return mob.blockPosition().distSqr(mob.getHomePos()) > r * r; // 돌봄 반경(+정원 여유) 이탈
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
