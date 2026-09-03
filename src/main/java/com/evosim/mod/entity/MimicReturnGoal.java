package com.evosim.mod.entity;

import com.evosim.core.FoodEconomy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 식량 귀가 goal (식량 경제 v2). 입금도 인출도 <b>거처에서만</b> — 하나의 밴드 [1,2)로 통일:
 *
 * <ul>
 *   <li><b>넣으러</b>: 소지 H ≥ 2(여분 정수 생김) → 귀가해 저장고에 정수 입금.</li>
 *   <li><b>꺼내러</b>: H < 0.8(귀가 히스테리시스, C-1) 이고 저장고에 밥이 있으면 → 귀가해 1개 인출.
 *       위급(H<0.3)이면서 저장고에 밥이 있으면 이 goal이 채집보다 우선한다(A-3).</li>
 * </ul>
 *
 * <p>도착 즉시 {@link MimicEntity#selfSettle}로 입출금(가족틱 대기 없음) — 회계는 순수 settleHome.
 */
public class MimicReturnGoal extends Goal {

    private static final double ARRIVE_DIST_SQ = 6.25; // 2.5블록 안 = 도착

    private final MimicEntity mob;

    public MimicReturnGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    private boolean wantsTrip() {
        if (mob.getHomePos() == null || mob.getIndividual() == null
                || mob.isBuilding() || mob.isFastSettle() || mob.isCourtTravel()) {
            return false; // 구혼 여행 중엔 귀가로 끌지 않음(노상 자급 — H 상한 컷)
        }
        // <b>전투불가 병사는 귀가로 끌지 않는다 — 후송이 먼저다.</b>
        //
        // 귀가(3)는 주둔(4)보다 우선이고, 다친 병사는 소지가 바닥나 있기 마련이라
        // "소지 < 0.8 이고 제 집에 밥이 있음"이 거의 항상 참이 된다. 그러면 부상병은
        // 아군 막사가 아니라 <b>제 집</b>으로 가서 <b>제</b> 저장고를 헐어 먹는다.
        // 급양은 MimicGarrisonGoal.tick 안에만 있으므로, 그 goal 이 밀리는 한 영주
        // 저장고는 영영 열리지 않는다 — 설계가 세우려던 "지갑 = 전투지속력"이 끊긴다.
        //
        // 실측(P5): 스티븐 셔우드 소지 0.0 · 체력 20% 로 goal Return(3) 을 물고 제 집
        // 방향(13,-9)으로 갔다. 제 저장고가 14.7 → 12.7 로 깎였고 후송은 0건이었다.
        //
        // 굶어 죽는 것을 막는 안전판은 그대로다: 위급(H<0.3)은 주둔 goal 이 스스로
        // 물러나므로(canUse 의 isCritical 분기) 그때는 이 예외가 걸려도 귀가가 살아난다.
        if (mob.isUnderTreatment() && FarmTicker.isSoldier(mob) && !mob.isCritical()) {
            return false;
        }
        // <b>근무 중인 병사는 입금하러 집에 가지 않는다.</b>
        //
        // 전시 배급이 소지를 운반 상한까지 채우는데, 바로 아래 첫 조건이
        // {@code holding >= carryCap} 이라 배급을 받은 순간 "여분 정수 생김 → 넣으러"가
        // 참이 된다. 그래서 병사가 배급을 받자마자 집으로 <b>입금하러</b> 돌아섰다.
        //
        // 실측: 전선 막사(107블록 밖)에 배속된 병사가 제 집 근처에서 리시에 끌려다니다
        // 소지 0.0 · 위급이 됐다 — 먹으라고 준 것을 은행에 넣으러 가다 굶은 것이다.
        //
        // 인출(아래 마지막 줄)은 막지 않는다: 그것은 굶주림이고, 위급이면 주둔 goal 이
        // 스스로 물러나므로 귀가가 살아나야 한다.
        if (FarmTicker.isSoldier(mob) && mob.getHolding() >= mob.carryCap()) {
            return false;
        }
        if (mob.getHolding() >= mob.carryCap()) {
            return true; // 여분 정수 → 넣으러 (수확 세션 중엔 운반 상한 6.0까지 미룸 — 소작 루프 v2)
        }
        return mob.getHolding() < FoodEconomy.RETURN_LOW && mob.larderHasFood(); // 꺼내러
    }

    @Override
    public boolean canUse() {
        if (!wantsTrip()) {
            return false;
        }
        if (mob.blockPosition().distSqr(mob.getHomePos()) <= ARRIVE_DIST_SQ) {
            // 이미 집 안 — 이동 goal 없이 즉석 입출금(집에 서 있으면 배고파도 인출을 못 하던 결함 방지).
            if (mob.level() instanceof ServerLevel sl) {
                mob.selfSettle(sl);
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return wantsTrip()
                && mob.blockPosition().distSqr(mob.getHomePos()) > ARRIVE_DIST_SQ;
    }

    @Override
    public void start() {
        // 입금·인출 왕복은 거처가 앵커 — 출근 앵커가 남아 있으면 리시가 밭으로 되끌어 귀가와
        // 줄다리기를 벌인다(F1 보완). 입금 후 밭일 goal 이 표적을 다시 잡으며 앵커를 재설정한다.
        mob.setWorkAnchor(null);
        // <b>마실 앵커도 같은 이유로 놓는다.</b> 종전에는 마실 goal 이 선점당할 때 스스로 지웠기에
        // 여기서 할 일이 없었는데, "선점돼도 목적지를 놓지 않는다"로 바꾸면서 귀가 중에도 마실
        // 앵커가 남게 됐다. 그러면 리시(2)가 그 앵커를 보고 마실 목적지로 끌고 귀가(3)는 집으로
        // 끌어 똑같은 줄다리기가 된다 — 실측(야생 D6): 짧게 머물다 갈아탄 전이 45회 중 40회가
        // Visit↔Return 이었다.
        //
        // 목적지 자체는 마실 goal 이 그대로 들고 있으므로(dest), 귀가가 끝나면 같은 집으로 다시
        // 나선다. 표적이 매번 바뀌던 종전 결함은 재발하지 않는다.
        mob.setVisitAnchor(null);
    }

    @Override
    public void tick() {
        BlockPos home = mob.getHomePos();
        if (home == null) {
            return;
        }
        mob.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5,
                mob.isCritical() ? 1.2 : 1.0); // 위급하면 서둘러 귀가
    }

    @Override
    public void stop() {
        // 도착으로 종료됐으면 즉석 입출금(밴드 [1,2)로 복귀) — 가족틱을 기다리지 않는다.
        if (mob.getHomePos() != null && mob.level() instanceof ServerLevel sl
                && mob.blockPosition().distSqr(mob.getHomePos()) <= ARRIVE_DIST_SQ) {
            mob.selfSettle(sl);
        }
    }
}
