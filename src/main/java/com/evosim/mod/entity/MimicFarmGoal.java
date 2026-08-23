package com.evosim.mod.entity;

import com.evosim.core.FarmEconomy;
import com.evosim.core.FoodEconomy;
import com.evosim.core.LifeStage;
import com.evosim.core.Schedule;
import com.evosim.mod.log.SimEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;

import java.util.EnumSet;

/**
 * 자기 밭 수확 goal (M1 — 자영만, 소작 배정은 M2). 노동 시간에 소유 구획의 익은 타일을 순회
 * 수확 — 하루 용량 C(FarmEconomy.capacity)까지만(전담창 추상화). 수확 시 익음 타이머 리셋.
 * 우선순위는 채집(Forage)보다 앞 — 틱당 수익 우위(밭 0.0037 > 들풀 0.0011)를 행동으로 반영.
 */
public class MimicFarmGoal extends Goal {

    private static final int HARVEST_COOLDOWN = 100; // 기존 채집과 동일 리듬

    private final MimicEntity mob;
    private int cooldown;
    private int harvestedToday;
    private long day = -1;
    private BlockPos target;

    public MimicFarmGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.getIndividual() == null || mob.isFastSettle() || mob.isBuilding()
                || mob.getStage() == LifeStage.INFANT || mob.getStage() == LifeStage.BOY) {
            return false;
        }
        if (Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime()) != Schedule.Phase.WORK) {
            return idle();
        }
        long today = com.evosim.mod.entity.SimTime.tick(mob.level()) / 24000L;
        if (today != day) {
            day = today;
            harvestedToday = 0; // 일일 용량 리셋
        }
        if (harvestedToday >= FarmEconomy.capacity(mob.getIndividual(), mob.getStage())) {
            return idle(); // 전담창 소진 — 나머지 시간은 기존 채집/배회
        }
        if (mob.getStage() == LifeStage.ELDER && mob.elderQuotaMet()) {
            // 노년 노동의 단일 상한 = 쿼터(노년 확장 산출 ㉵) — 밭 수확도 addHarvest 로 dayGathered 에
            // 누적되므로 여기서 막지 않으면 용량(6타일=4.5/일)까지 뚫려 자식 지원 누수가 재발한다.
            // 잔여 익은 타일은 부족분 게시 → 소작(2세대 일자리)으로 자연 이관.
            return idle();
        }
        if (mob.isSatisfiedToday() && FarmTicker.assignedPlot(mob.getId()) == 0L) {
            return idle(); // 만족(M7) — 자기 밭 노동 정지. 소작 출근(배정)은 계약 의무라 유지
        }
        target = nearestWorkRipe();
        return target != null || idle();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void stop() {
        // 주의: 여기서 workAnchor 를 지우면 안 된다 — 리시(2)가 출근을 인수하는 순간 이 goal 이
        // 선점 정지되며 stop 이 불리는데, 그때 앵커를 지우면 리시가 거처로 되끌어 자기파괴
        // 루프가 된다(실측: act=복귀 진동). 해제는 자연 종료 지점(canUse 의 idle 경로)·입금
        // 귀가(MimicReturnGoal.start)·노동시간 종료(roamAnchor 의 WORK 게이트)가 맡는다.
        target = null;
    }

    /** 밭일 자연 종료 — 출근 앵커 해제 후 비활성(리시 앵커가 거처로 복원). */
    private boolean idle() {
        mob.setWorkAnchor(null);
        return false;
    }

    @Override
    public void tick() {
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (target == null) {
            return;
        }
        // 출근 앵커(F1) — 표적 보유 동안 리시 앵커를 작업 타일로: 활동반경(기본 32·애향 16) 밖
        // 통근 밭(≤48)에서 리시가 밭일을 선점해 출발↔강제귀환 줄다리기로 재배가 막히던 결함 수정.
        mob.setWorkAnchor(target);
        if (!mob.blockPosition().closerThan(target, 1.9)) {
            mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
            return;
        }
        var st = mob.level().getBlockState(target);
        if (st.is(Blocks.SWEET_BERRY_BUSH) && st.getValue(SweetBerryBushBlock.AGE) >= 3) {
            mob.level().setBlockAndUpdate(target, st.setValue(SweetBerryBushBlock.AGE, 1));
            // 기본 수확 = <b>실제로 일한 개체</b>의 채집 능력. 관리 효율 E 는 여기 곱하지 않는다.
            double base = 0.5 * FoodEconomy.forageYieldMult(mob.getIndividual());
            FarmStore.Plot p = plotOf(target);
            // 관리 효율(회차 S2 — 관리 바닥값): 마름 밭 E = max(마름 E, 지주 재흡수 E) — 지주
            // 오버사이트가 바닥이라 무능 마름 조기 임명해도 붕괴 없음, 지주가 캡 초과로 얇아지면
            // 마름 전담 E가 바닥 넘어 캡 돌파. 무마름 밭은 지주의 무마름 타일 합 기준. (plotEfficiency)
            double e = p != null ? farmStore().plotEfficiency(serverLevel(), p) : 1.0;
            boolean household = p != null && (p.ownerId == mob.getIndividual().id()
                    || (mob.getSpouseId() != 0L && p.ownerId == mob.getSpouseId()));
            if (p != null && !household) {
                // 소작 분할 — <b>E 는 지주 몫에만</b> 곱한다(5규칙 정합):
                //   규칙4·5(밭 무한 성장·자산 무한 누적) + 규칙3(소작 출산 2~3)이 동시에 서려면
                //   소작 수취가 밭 크기에 반비례해서는 안 된다. 그런데 종전에는 E(=(용량/타일)²)와
                //   누진 fee 가 <b>둘 다</b> 소작 몫을 규모에 반비례로 깎아, 193타일 실측에서 소작이
                //   자기 노동의 16.5%만 받았다(E 0.475 × 소작몫 0.347). 규칙4가 성공할수록 규칙3이
                //   깨지는 구조였다. 소작은 자기가 딴 만큼 받고, 관리 실패의 손실은 지주가 진다.
                //   (fee 누진은 FarmEconomy.fee 에서 함께 평탄화 — 그쪽 주석 참조.)
                //   새 회계 항등식: tShare + ownerCut + waste == base, waste = base×fee×(1−E).
                int ownerTiles = farmStore().ownedTiles(p.ownerId);
                double tShare = FarmEconomy.tenantShare(base, ownerTiles);   // E 미적용
                double baseShare = FarmEconomy.baseOwnerShare(base) * e;     // E 적용
                double excessShare = FarmEconomy.excessOwnerShare(base, ownerTiles) * e;
                mob.addHarvest(tShare);
                p.account += baseShare;
                p.excessHoard += excessShare; // 잠금 축장(밤 정산 때 지주 저장고로, 확장 무관)
                if (mob.getIndividual().id() != p.stewardId) {
                    // 마름 수당의 입력(소작 1인 평균 일수취) — 마름 본인의 노동 모드 수확은 제외
                    FarmTicker.recordTenantPay(p.id, tShare, mob.getId());
                }
                // 원장: totalYield 는 <b>실분배 합</b>(낭비 제외) — totalToOwner+totalToTenant 와 항등.
                farmStore().recordHarvest(p, tShare + baseShare + excessShare,
                        baseShare + excessShare, tShare);
                com.evosim.mod.log.SimAudit.record(
                        com.evosim.mod.log.SimAudit.Src.FARM_TENANT, tShare);
                com.evosim.mod.log.SimAudit.record(
                        com.evosim.mod.log.SimAudit.Src.RENT, baseShare + excessShare);
                SimEvents.event(mob, "소작수확", String.format(
                        "+%.2f (지대 계정 %.2f + 축장 %.2f, E%.2f, 오늘 %d타일)",
                        tShare, baseShare, excessShare, e, harvestedToday + 1));
            } else {
                // 자영 = 전액 지주 몫이므로 E 적용(확장 제동 유지 — 자영 지주만 예외가 되지 않게).
                double own = base * e;
                mob.addHarvest(own); // 자기 밭 = 100% 본인 몫
                if (p != null) {
                    farmStore().recordHarvest(p, own, own, 0.0); // 자영 수확도 원장에(주인 몫)
                }
                com.evosim.mod.log.SimAudit.record(
                        com.evosim.mod.log.SimAudit.Src.FARM_SELF, own);
                SimEvents.event(mob, "밭수확", String.format("자영 +%.2f (E%.2f, 오늘 %d타일)",
                        own, e, harvestedToday + 1));
            }
            resetTimer(target);
            harvestedToday++;
            cooldown = HARVEST_COOLDOWN;
        }
        target = null;
    }

    private net.minecraft.server.level.ServerLevel serverLevel() {
        return (net.minecraft.server.level.ServerLevel) mob.level();
    }

    private FarmStore farmStore() {
        return FarmStore.get(serverLevel());
    }

    /** 이 타일이 속한 구획. */
    private FarmStore.Plot plotOf(BlockPos pos) {
        for (FarmStore.Plot p : farmStore().all().values()) {
            for (long l : p.tiles) {
                if (l == pos.asLong()) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * 일할 밭(소유 구획 전부 + 오늘 배정된 소작 구획)에서 가장 가까운 익은 타일.
     *
     * <p><b>돌봄 반경 밖 타일은 후보에서 제외</b>한다 — {@link MimicForageGoal}의 boundMode 와 대칭.
     * 종전엔 이 goal 만 육아 구속을 전혀 보지 않아, 구속된 부모가 12블록 밖 밭 타일을 표적으로
     * 잡고 밖으로 걸어나가면 우선순위 1인 {@link MimicParentingGoal}(반경 r 이탈 시 발동)이
     * 즉시 되끌었다. 두 판정이 같은 경계 r 을 히스테리시스 없이 공유하므로 r 에서 무한 진동한다:
     * 6.6 → 육아 발동(안으로) → 6.4 → 육아 해제 → 밭일 발동(밖으로) → 6.6 … 실측 증상은
     * "밭일/육아가 1초 간격으로 바뀌며 제자리에서 움찔거림", 결과는 <b>노동 시간 전체 수입 0</b>.
     * 밭 부지는 거처 12블록을 회피하므로(findFarmSite) 실질적으로 구속자는 밭일을 하지 않게 되며,
     * 이는 채집 쪽에 이미 있던 사양("집에만 있는 쪽이 정원을 맡는다")과 같은 규칙이다.
     */
    private BlockPos nearestWorkRipe() {
        if (!(mob.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return null;
        }
        double careR = careRadius();
        long id = mob.getIndividual().id();
        long sid = mob.getSpouseId();
        long assigned = FarmTicker.assignedPlot(mob.getId());
        FarmStore fs = FarmStore.get(sl);
        long newestMine = fs.newestOwnedPlot(id);
        long newestSpouse = sid == 0L ? 0L : fs.newestOwnedPlot(sid);
        long stewardPlot = fs.stewardOf(id);
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (FarmStore.Plot p : fs.all().values()) {
            boolean mine = p.ownerId == id;
            boolean spouses = sid != 0L && p.ownerId == sid;
            // 마름 노동 모드(v1.3) — 소작 0인 자기 위임 구획은 직접 일군다(분배는 소작식).
            // 소작이 1명이라도 배정되면 관리 모드(수당) — 밭일 대신 본업(채집)으로 복귀.
            boolean stewardLabor = p.id == stewardPlot && p.id != assigned
                    && FarmTicker.assignedToPlot(p.id) == 0;
            if (!mine && !spouses && p.id != assigned && !stewardLabor) {
                continue; // 무단 수확 금지 — 소유·배우자 소유(가족 노동)·오늘 배정·마름 노동만
            }
            // 직영지 원칙(소작 루프 v2): 다구획 주인 가족의 자가 노동은 최신 구획만 —
            // 구 구획은 100% 소작 몫(신규 개간과 동시에 인계). 배정 소작 출근은 그대로.
            if (mine && p.id != newestMine && p.id != assigned) {
                continue;
            }
            if (spouses && p.id != newestSpouse && p.id != assigned) {
                continue;
            }
            for (long l : p.tiles) {
                BlockPos pos = BlockPos.of(l);
                if (!sl.isLoaded(pos)) {
                    continue;
                }
                if (careR >= 0.0 && pos.distSqr(mob.getHomePos()) > careR * careR) {
                    continue; // 돌봄 반경 밖 — 표적으로 잡으면 육아 goal 과 경계 진동(위 주석)
                }
                var st = sl.getBlockState(pos);
                if (st.is(Blocks.SWEET_BERRY_BUSH) && st.getValue(SweetBerryBushBlock.AGE) >= 3) {
                    double d = mob.blockPosition().distSqr(pos);
                    if (d < bd) {
                        bd = d;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    /** 돌봄 구속 중이면 반경(careRadius, 최소 {@link MimicParentingGoal#CARE_SLACK}), 아니면 -1(무제한). */
    private double careRadius() {
        if (!mob.isCaregiverBound() || mob.getHomePos() == null || mob.getIndividual() == null) {
            return -1.0;
        }
        return Math.max(mob.getIndividual().parentingCare().careRadius(),
                MimicParentingGoal.CARE_SLACK);
    }

    /** 수확한 타일의 익음 타이머 리셋(재성장 기점). */
    private void resetTimer(BlockPos pos) {
        if (!(mob.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return;
        }
        for (FarmStore.Plot p : FarmStore.get(sl).all().values()) {
            for (int i = 0; i < p.tiles.length; i++) {
                if (p.tiles[i] == pos.asLong()) {
                    p.planted[i] = com.evosim.mod.entity.SimTime.tick(sl);
                    FarmStore.get(sl).setDirty();
                    return;
                }
            }
        }
    }
}
