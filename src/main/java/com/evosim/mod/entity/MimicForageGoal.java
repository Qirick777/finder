package com.evosim.mod.entity;

import com.evosim.core.ExpressionResolver;
import com.evosim.core.Individual;
import com.evosim.core.Multipliers;
import com.evosim.core.Schedule;
import com.evosim.core.SurvivalRules;
import com.evosim.core.Trait;
import com.evosim.mod.log.SimEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 채집·사냥 goal (설계서 §4 §16). <b>노동 시간대</b>에 성년·만혼소년이 실제로 식량을 확보한다.
 *
 * <ul>
 *   <li><b>사냥</b>: 인지 범위 안에 동물이 보이면 <b>즉각</b> 추격·타격 → 잡으면 사냥배율만큼 식량.</li>
 *   <li><b>채집</b>: 주변 풀(잔디·고사리)을 찾아가 부숴 채집배율만큼 식량. <b>약초학자</b>는 꽃·버섯도 채집.
 *       채집 사이 <b>쿨타임</b>으로 한 번에 다 밀어버려 즉각 소멸하는 것을 막는다.</li>
 * </ul>
 *
 * <p>부순 블록은 드랍 없이 제거되고, 벌인 채집·사냥량이 그날의 {@code dayHarvest}로 쌓여 밤 정산에 쓰인다.
 */
public class MimicForageGoal extends Goal {

    private static final double HUNT_RANGE = 12.0;   // 이 안의 동물은 즉각 사냥 대상
    private static final int GATHER_COOLDOWN = 100;  // 채집 간 쿨타임(틱) — 즉각 완전소멸 방지
    private static final int ATTACK_COOLDOWN = 20;   // 타격 간격(틱)
    private static final double HUNT_FOOD = 1.5;     // 동물 1마리 = 이 × 사냥배율
    private static final double GATHER_FOOD = 0.06;  // 채집물 1개 = 이 × 채집배율
    private static final double BERRY_FOOD = 0.5;    // 다 익은 베리 1수확 = 이 × 채집배율
    private static final double REACH = 1.9;         // 이 거리 안이면 채집(부수기) 가능

    private final MimicEntity mob;
    private Animal huntTarget;
    private BlockPos gatherTarget;
    private int gatherCooldown;
    private int attackCooldown;

    public MimicForageGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Individual ind = mob.getIndividual();
        if (ind == null || mob.isFastSettle() || mob.isBuilding()) {
            return false; // 무대 검증 시드 통제 / 건축 중엔 채집 정지
        }
        if (!SurvivalRules.canGather(mob.getStage(), ind)) {
            return false; // 유아·일반소년은 자급 불가
        }
        if (mob.isCaregiverBound()) {
            return false; // 육아 중인 어미는 채집하러 안 나감(§4 남편 채집·아내 육아)
        }
        return Schedule.phaseAt(ind, mob.level().getDayTime()) == Schedule.Phase.WORK;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void stop() {
        huntTarget = null;
        gatherTarget = null;
    }

    @Override
    public void tick() {
        if (gatherCooldown > 0) {
            gatherCooldown--;
        }
        if (attackCooldown > 0) {
            attackCooldown--;
        }
        Individual ind = mob.getIndividual();
        if (ind == null) {
            return;
        }

        // 1) 사냥 — 동물을 보면 즉각.
        if (huntTarget != null && (!huntTarget.isAlive()
                || mob.distanceToSqr(huntTarget) > HUNT_RANGE * HUNT_RANGE * 2.0)) {
            huntTarget = null;
        }
        if (huntTarget == null) {
            huntTarget = nearestAnimal();
        }
        if (huntTarget != null) {
            mob.getLookControl().setLookAt(huntTarget, 30.0F, 30.0F);
            if (mob.distanceToSqr(huntTarget) > 4.0) {
                mob.getNavigation().moveTo(huntTarget, 1.2);
            } else if (attackCooldown == 0) {
                mob.swing(InteractionHand.MAIN_HAND);
                mob.doHurtTarget(huntTarget);
                attackCooldown = ATTACK_COOLDOWN;
                if (!huntTarget.isAlive()) {
                    double food = HUNT_FOOD * Multipliers.hunt(ind);
                    mob.addHarvest(food);
                    SimEvents.event(mob, "사냥", String.format("동물 처치 → 식량 +%.2f", food));
                    huntTarget = null;
                }
            }
            return;
        }

        // 2) 채집 — 풀(약초학자는 꽃·버섯도)을 부숴 식량. 쿨타임 중이면 그냥 배회.
        boolean herbalist = ExpressionResolver.isExpressed(ind, Trait.HERBALIST);
        if (gatherCooldown > 0) {
            idleWander();
            return;
        }
        if (gatherTarget != null && !forageable(mob.level().getBlockState(gatherTarget), herbalist)) {
            gatherTarget = null;
        }
        if (gatherTarget == null) {
            gatherTarget = findForage(herbalist);
        }
        if (gatherTarget != null) {
            if (mob.blockPosition().closerThan(gatherTarget, REACH)) {
                BlockState ts = mob.level().getBlockState(gatherTarget);
                if (isRipeBerry(ts)) {
                    // 다 익은 베리는 부수지 않고 수확 → age 1 로 되돌려 재성장(바닐라 수확).
                    double food = BERRY_FOOD * Multipliers.gather(ind);
                    mob.addHarvest(food);
                    mob.level().setBlockAndUpdate(gatherTarget, ts.setValue(SweetBerryBushBlock.AGE, 1));
                    mob.swing(InteractionHand.MAIN_HAND);
                    gatherCooldown = GATHER_COOLDOWN;
                } else if (mob.level().destroyBlock(gatherTarget, false)) {
                    double food = GATHER_FOOD * Multipliers.gather(ind);
                    mob.addHarvest(food);
                    gatherCooldown = GATHER_COOLDOWN;
                }
                gatherTarget = null;
            } else {
                mob.getNavigation().moveTo(gatherTarget.getX() + 0.5, gatherTarget.getY(),
                        gatherTarget.getZ() + 0.5, 1.0);
            }
            return;
        }
        idleWander(); // 채집물도 동물도 없으면 돌아다니며 탐색
    }

    private void idleWander() {
        if (mob.getNavigation().isDone()) {
            Vec3 t = DefaultRandomPos.getPos(mob, 8, 5);
            if (t != null) {
                mob.getNavigation().moveTo(t.x, t.y, t.z, 0.9);
            }
        }
    }

    private Animal nearestAnimal() {
        Animal best = null;
        double bestDist = Double.MAX_VALUE;
        for (Animal a : mob.level().getEntitiesOfClass(
                Animal.class, mob.getBoundingBox().inflate(HUNT_RANGE))) {
            if (!a.isAlive()) {
                continue;
            }
            double d = mob.distanceToSqr(a);
            if (d < bestDist) {
                bestDist = d;
                best = a;
            }
        }
        return best;
    }

    /** 주변에서 부술 채집물 한 칸을 무작위 표본으로 탐색(전체 스캔 회피). */
    private BlockPos findForage(boolean herbalist) {
        BlockPos base = mob.blockPosition();
        for (int i = 0; i < 24; i++) {
            int dx = mob.getRandom().nextInt(11) - 5;
            int dz = mob.getRandom().nextInt(11) - 5;
            int dy = mob.getRandom().nextInt(3) - 1;
            BlockPos p = base.offset(dx, dy, dz);
            if (forageable(mob.level().getBlockState(p), herbalist)) {
                return p;
            }
        }
        return null;
    }

    /** 누구나 채집하는 풀(잔디·고사리) + 다 익은 옆 정원 베리 + 약초학자만 채집하는 꽃·버섯. */
    private static boolean forageable(BlockState s, boolean herbalist) {
        if (s.is(Blocks.GRASS) || s.is(Blocks.TALL_GRASS)
                || s.is(Blocks.FERN) || s.is(Blocks.LARGE_FERN)) {
            return true;
        }
        if (isRipeBerry(s)) {
            return true; // 다 익은 베리는 누구나 수확
        }
        return herbalist && (s.is(BlockTags.FLOWERS)
                || s.is(Blocks.BROWN_MUSHROOM) || s.is(Blocks.RED_MUSHROOM));
    }

    /** 스위트베리 덤불이 다 익었는지(age 3 = 수확 가능). */
    private static boolean isRipeBerry(BlockState s) {
        return s.is(Blocks.SWEET_BERRY_BUSH) && s.getValue(SweetBerryBushBlock.AGE) >= 3;
    }
}
