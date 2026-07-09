package com.evosim.mod.entity;

import com.evosim.core.Combat;
import com.evosim.core.Individual;
import com.evosim.mod.stage.StageObserver;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * 미믹 전투 goal — 순수 {@link Combat} 판정(진입/도망)을 읽어 실제 행동으로 옮기고, 무대 검증용으로
 * 그 행동을 {@link StageObserver}에 기록한다(설계서 §13-B, §17).
 *
 * <p>대상 몹은 좀비·스켈레톤만(설계서: 거미·크리퍼·엔더맨 무시).
 */
public class MimicCombatGoal extends Goal {

    private final MimicEntity mob;
    private Monster target;
    private boolean recorded;

    public MimicCombatGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Individual ind = mob.getIndividual();
        if (ind == null) {
            return false;
        }
        this.target = nearestMonster(Combat.detectionRange(ind));
        return this.target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && target.isAlive() && mob.getIndividual() != null;
    }

    @Override
    public void start() {
        this.recorded = false;
    }

    @Override
    public void stop() {
        this.target = null;
        this.recorded = false;
    }

    @Override
    public void tick() {
        Individual ind = mob.getIndividual();
        if (ind == null || target == null) {
            return;
        }
        boolean adjacent = mob.distanceToSqr(target) < 4.0; // ~2블록
        Combat.Entry entry = Combat.entry(ind, adjacent, true);

        switch (entry) {
            case ENGAGE -> {
                mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
                mob.getNavigation().moveTo(target, 1.15);
                record("combat:engage");
                if (adjacent) {
                    mob.doHurtTarget(target);
                }
            }
            case FLEE -> {
                Vec3 away = mob.position()
                        .add(mob.position().subtract(target.position()).normalize().scale(8.0));
                mob.getNavigation().moveTo(away.x, away.y, away.z, 1.25);
                record("combat:flee");
            }
            case IGNORE -> {
                // 온 것만 처치하는 중립인데 인접이 아니면 무시.
            }
        }
    }

    private void record(String tag) {
        if (!recorded) {
            StageObserver.record(mob.getId(), tag);
            recorded = true;
        }
    }

    private Monster nearestMonster(double range) {
        List<Monster> list = mob.level().getEntitiesOfClass(
                Monster.class, mob.getBoundingBox().inflate(range));
        Monster best = null;
        double bestDist = Double.MAX_VALUE;
        for (Monster m : list) {
            if (!(m instanceof Zombie) && !(m instanceof Skeleton)) {
                continue;
            }
            double d = mob.distanceToSqr(m);
            if (d < bestDist) {
                bestDist = d;
                best = m;
            }
        }
        return best;
    }
}
