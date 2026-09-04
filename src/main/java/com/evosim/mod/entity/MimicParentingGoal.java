package com.evosim.mod.entity;

import com.evosim.core.Individual;

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

    /**
     * 돌봄자의 <b>노동 반경</b> — 밭·채집이 표적을 고를 수 있는 범위. 육아·밭·채집이 각자
     * 같은 식을 따로 쓰다 보면 어긋나기 쉬워 한 곳으로 모았다.
     */
    public static double workRadius(Individual ind) {
        return Math.max(ind.parentingCare().careRadius(), CARE_SLACK);
    }

    /**
     * 발동/해제 문턱 — 노동 반경의 배수. <b>발동이 해제보다 바깥</b>이라야 경계에서 진동하지 않는다.
     *
     * <p>없으면(종전 {@code canContinueToUse = canUse}) 무한 왕복이 된다. 밭일(우선순위 6)·채집(7)은
     * 노동 반경 안 표적을 잡는데 육아(1)는 같은 반경을 넘는 순간 선점하므로, 길이 장애물을 돌아
     * 한 발 부풀기만 해도 집으로 끌려오고, 들어오면 다시 같은 표적으로 향한다 — 육안 관측된
     * "육아와 밭일을 계속 왔다갔다"의 정체다. {@code Satisfaction.RESUME_FACTOR} 와 같은 장치다.
     *
     * <p>고치는 방향은 <b>노동 반경을 좁히는 게 아니라 견인 문턱을 넓히는 것</b>이다. 표적 반경을
     * 줄이면(0.6× 시안) 적극 돌봄자의 노동 반경이 3.9블록이 되어 {@link #CARE_SLACK} 이 지키려던
     * 정원 최원 그루(≈5.4블록)가 사정권 밖으로 나간다 — 정원 수확 봉쇄가 재발한다. 표적은 반경
     * 그대로 두고, 견인은 1.35× 밖에서 걸어 해제는 반경 안(1.0×)에서 푼다.
     */
    public static final double ENGAGE_FACTOR = 1.35;

    /** 해제 문턱 — 노동 반경 안으로 되돌아오면 푼다(그 자리에서 바로 다시 일할 수 있다). */
    private static final double RELEASE_FACTOR = 1.0;

    /**
     * A/B 스위치 — 끄면 발동·해제가 모두 1.0× 가 되어 <b>종전(문턱 하나)</b> 거동을 그대로
     * 재현한다. 같은 런 안에서 번갈아 켜고 끄며 재려고 둔다(끼임 가설을 기각했던 방식과 동일).
     */
    private static boolean hysteresis = true;

    public static void setHysteresis(boolean on) {
        hysteresis = on;
    }

    public static boolean hysteresis() {
        return hysteresis;
    }

    private static double engageFactor() {
        return hysteresis ? ENGAGE_FACTOR : 1.0;
    }

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
        double r = workRadius(mob.getIndividual()) * engageFactor();
        return mob.blockPosition().distSqr(mob.getHomePos()) > r * r; // 견인 문턱 이탈
    }

    @Override
    public boolean canContinueToUse() {
        if (!mob.isCaregiverBound() || mob.getHomePos() == null || mob.getIndividual() == null) {
            return false;
        }
        double r = workRadius(mob.getIndividual()) * RELEASE_FACTOR;
        return mob.blockPosition().distSqr(mob.getHomePos()) > r * r;
    }

    @Override
    public void tick() {
        BlockPos home = mob.getHomePos();
        if (home != null) {
            mob.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1.0);
        }
    }
}
