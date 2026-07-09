package com.evosim.mod.entity;

import com.evosim.core.Combat;
import com.evosim.core.Individual;
import com.evosim.core.SurvivalRules;
import com.evosim.mod.stage.StageObserver;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 미믹 전투 goal — 순수 {@link Combat} 판정(진입/도망)을 읽어 실제 행동으로 옮기고, 무대 검증용으로
 * 그 행동을 {@link StageObserver}에 기록한다(설계서 §13-B, §17).
 *
 * <p>대상 몹은 좀비·스켈레톤만(설계서: 거미·크리퍼·엔더맨 무시).
 */
public class MimicCombatGoal extends Goal {

    private final MimicEntity mob;
    private Monster target;
    private boolean retreating;
    private final Set<String> recordedTags = new HashSet<>();

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
        this.retreating = false;
        this.recordedTags.clear();
    }

    @Override
    public void stop() {
        this.target = null;
        this.retreating = false;
        this.recordedTags.clear();
    }

    @Override
    public void tick() {
        Individual ind = mob.getIndividual();
        if (ind == null || target == null) {
            return;
        }
        // 유아·소년은 전투 불가(무방비, 설계서 §7) — 몬스터를 봐도 못 싸움.
        if (!SurvivalRules.canFight(mob.getStage())) {
            record("combat:tooyoung");
            return;
        }

        double maxHp = mob.getMaxHealth();
        double hp = maxHp > 0 ? mob.getHealth() / maxHp : 1.0;
        boolean adjacent = mob.distanceToSqr(target) < 4.0; // ~2블록

        // ③ 복귀: 퇴각 중이면 회복 시 복귀(신중만), 아니면 계속 도망.
        if (retreating) {
            if (Combat.returnsToCombat(ind, hp)) {
                retreating = false;
                record("combat:return");
            } else {
                fleeFrom();
                return;
            }
        }

        Combat.Entry entry = Combat.entry(ind, adjacent, true);
        if (entry == Combat.Entry.FLEE) {
            fleeFrom();
            record("combat:flee");
            return;
        }
        if (entry == Combat.Entry.IGNORE) {
            return; // 중립인데 인접 아님 → 무시.
        }

        // ② 퇴각: 체력 하한이면 물러남(가족 근처 개념은 Phase 4 → false).
        if (Combat.retreat(ind, hp, false) == Combat.Retreat.RETREAT) {
            retreating = true;
            fleeFrom();
            record("combat:retreat");
            return;
        }

        // ① 진입: 접근 + 공격.
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        mob.getNavigation().moveTo(target, 1.15);
        record("combat:engage");
        if (adjacent) {
            mob.doHurtTarget(target);
        }
    }

    private void fleeFrom() {
        Vec3 away = mob.position()
                .add(mob.position().subtract(target.position()).normalize().scale(8.0));
        mob.getNavigation().moveTo(away.x, away.y, away.z, 1.25);
    }

    private void record(String tag) {
        if (recordedTags.add(tag)) {
            StageObserver.record(mob.getId(), tag);
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
