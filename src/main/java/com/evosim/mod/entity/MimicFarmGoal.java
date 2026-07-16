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
            return false;
        }
        long today = mob.level().getGameTime() / 24000L;
        if (today != day) {
            day = today;
            harvestedToday = 0; // 일일 용량 리셋
        }
        if (harvestedToday >= FarmEconomy.capacity(mob.getIndividual(), mob.getStage())) {
            return false; // 전담창 소진 — 나머지 시간은 기존 채집/배회
        }
        if (mob.getStage() == LifeStage.ELDER && mob.elderQuotaMet()) {
            // 노년 노동의 단일 상한 = 쿼터(노년 확장 산출 ㉵) — 밭 수확도 addHarvest 로 dayGathered 에
            // 누적되므로 여기서 막지 않으면 용량(6타일=4.5/일)까지 뚫려 자식 지원 누수가 재발한다.
            // 잔여 익은 타일은 부족분 게시 → 소작(2세대 일자리)으로 자연 이관.
            return false;
        }
        if (mob.isSatisfiedToday() && FarmTicker.assignedPlot(mob.getId()) == 0L) {
            return false; // 만족(M7) — 자기 밭 노동 정지. 소작 출근(배정)은 계약 의무라 유지
        }
        target = nearestWorkRipe();
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
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
        if (!mob.blockPosition().closerThan(target, 1.9)) {
            mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
            return;
        }
        var st = mob.level().getBlockState(target);
        if (st.is(Blocks.SWEET_BERRY_BUSH) && st.getValue(SweetBerryBushBlock.AGE) >= 3) {
            mob.level().setBlockAndUpdate(target, st.setValue(SweetBerryBushBlock.AGE, 1));
            double yield = 0.5 * FoodEconomy.forageYieldMult(mob.getIndividual());
            FarmStore.Plot p = plotOf(target);
            boolean household = p != null && (p.ownerId == mob.getIndividual().id()
                    || (mob.getSpouseId() != 0L && p.ownerId == mob.getSpouseId()));
            if (p != null && !household) {
                // 소작: 70% 본인 H, 30% 밭 계정(밤 정산에서 정수 유닛만 주인 저장고 — M3)
                mob.addHarvest(FarmEconomy.tenantShare(yield));
                p.account += FarmEconomy.ownerShare(yield);
                farmStore().setDirty();
                SimEvents.event(mob, "소작수확", String.format("+%.2f (지대 %.2f 적립, 오늘 %d타일)",
                        FarmEconomy.tenantShare(yield), FarmEconomy.ownerShare(yield), harvestedToday + 1));
            } else {
                mob.addHarvest(yield); // 자기 밭 = 100% 본인 몫
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
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (FarmStore.Plot p : FarmStore.get(sl).all().values()) {
            if (p.ownerId != id && (sid == 0L || p.ownerId != sid) && p.id != assigned) {
                continue; // 무단 수확 금지 — 소유·배우자 소유(가족 노동) 또는 오늘 배정만
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
                    p.planted[i] = sl.getGameTime();
                    FarmStore.get(sl).setDirty();
                    return;
                }
            }
        }
    }
}
