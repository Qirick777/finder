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
        target = nearestOwnRipe();
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
            double food = 0.5 * FoodEconomy.forageYieldMult(mob.getIndividual());
            mob.addHarvest(food); // 자기 밭 = 100% 본인 몫(지대는 M3의 소작 경로에서만)
            resetTimer(target);
            harvestedToday++;
            cooldown = HARVEST_COOLDOWN;
            SimEvents.event(mob, "밭수확", String.format("자영 +%.2f (오늘 %d타일)", food, harvestedToday));
        }
        target = null;
    }

    /** 소유 구획들에서 가장 가까운 익은 타일. */
    private BlockPos nearestOwnRipe() {
        if (!(mob.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return null;
        }
        long id = mob.getIndividual().id();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (FarmStore.Plot p : FarmStore.get(sl).all().values()) {
            if (p.ownerId != id) {
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
                    p.planted[i] = sl.getGameTime();
                    FarmStore.get(sl).setDirty();
                    return;
                }
            }
        }
    }
}
