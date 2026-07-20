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
            double yield = 0.5 * FoodEconomy.forageYieldMult(mob.getIndividual());
            FarmStore.Plot p = plotOf(target);
            // 관리 효율(하드캡 폐지 대체) — 주인의 관리용량(8+g³) 대비 <b>총소유타일</b> 초과분은
            // 수확이 제곱 감쇠. 지대가 말라 확장·신밭이 스스로 멈춘다: 무능력 소농·Ⅲ 총35(소지주)·
            // Ⅴ 총133(다밭 대지주 — 100명 의존권).
            if (p != null) {
                MimicEntity ownerEnt = null;
                for (MimicEntity o : serverLevel().getEntities(
                        com.evosim.mod.reg.ModEntities.MIMIC.get(),
                        e -> e.isAlive() && e.getIndividual() != null
                                && e.getIndividual().id() == p.ownerId)) {
                    ownerEnt = o;
                }
                if (ownerEnt != null) {
                    yield *= FarmEconomy.manageEfficiency(
                            ownerEnt.getIndividual(), farmStore().ownedTiles(p.ownerId));
                }
            }
            boolean household = p != null && (p.ownerId == mob.getIndividual().id()
                    || (mob.getSpouseId() != 0L && p.ownerId == mob.getSpouseId()));
            if (p != null && !household) {
                // 소작: 규모 누진 분할 — 지주 총소유 타일 기준 지대율 0.45→0.55(100+) —
                // 대지주일수록 지대 몫이 커진다(소지금 격차 5~10배 목표, 회계 항등식 유지).
                int ownerTiles = farmStore().ownedTiles(p.ownerId);
                double tShare = FarmEconomy.tenantShare(yield, ownerTiles);
                double oShare = FarmEconomy.ownerShare(yield, ownerTiles);
                mob.addHarvest(tShare);
                p.account += oShare;
                farmStore().recordHarvest(p, yield, oShare, tShare); // 밭 원장(P3) — setDirty 포함
                com.evosim.mod.log.SimAudit.record(
                        com.evosim.mod.log.SimAudit.Src.FARM_TENANT, tShare);
                com.evosim.mod.log.SimAudit.record(
                        com.evosim.mod.log.SimAudit.Src.RENT, oShare);
                SimEvents.event(mob, "소작수확", String.format("+%.2f (지대 %.2f 적립, 오늘 %d타일)",
                        tShare, oShare, harvestedToday + 1));
            } else {
                mob.addHarvest(yield); // 자기 밭 = 100% 본인 몫
                if (p != null) {
                    farmStore().recordHarvest(p, yield, yield, 0.0); // 자영 수확도 원장에(주인 몫)
                }
                com.evosim.mod.log.SimAudit.record(
                        com.evosim.mod.log.SimAudit.Src.FARM_SELF, yield);
                SimEvents.event(mob, "밭수확", String.format("자영 +%.2f (오늘 %d타일)",
                        yield, harvestedToday + 1));
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

    /** 일할 밭(소유 구획 전부 + 오늘 배정된 소작 구획)에서 가장 가까운 익은 타일. */
    private BlockPos nearestWorkRipe() {
        if (!(mob.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return null;
        }
        long id = mob.getIndividual().id();
        long sid = mob.getSpouseId();
        long assigned = FarmTicker.assignedPlot(mob.getId());
        FarmStore fs = FarmStore.get(sl);
        long newestMine = fs.newestOwnedPlot(id);
        long newestSpouse = sid == 0L ? 0L : fs.newestOwnedPlot(sid);
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (FarmStore.Plot p : fs.all().values()) {
            boolean mine = p.ownerId == id;
            boolean spouses = sid != 0L && p.ownerId == sid;
            if (!mine && !spouses && p.id != assigned) {
                continue; // 무단 수확 금지 — 소유·배우자 소유(가족 노동) 또는 오늘 배정만
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
