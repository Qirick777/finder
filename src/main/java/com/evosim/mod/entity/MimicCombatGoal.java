package com.evosim.mod.entity;

import com.evosim.core.Combat;
import com.evosim.core.Individual;
import com.evosim.core.SurvivalRules;
import com.evosim.mod.log.SimEvents;
import com.evosim.mod.stage.StageObserver;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
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
        if (ind == null || !SurvivalRules.canFight(mob.getStage())) {
            return false; // 유아·소년은 전투 goal 미사용(권한 점유로 얼어붙지 않도록)
        }
        Monster m = nearestMonster(Combat.detectionRange(ind));
        if (m == null) {
            return false;
        }
        // 실제로 진입/도망할 때만 goal 점유 — IGNORE(중립·비인접)면 배회 유지.
        boolean adjacent = mob.distanceToSqr(m) < 4.0;
        if (Combat.entry(ind, adjacent, true) == Combat.Entry.IGNORE) {
            return false;
        }
        this.target = m;
        return true;
    }

    /** 어그로 풀림 해제 기준(위협 판정 반경과 동일) — 이 밖이고 좀비가 날 안 노리면 종료. */
    private static final double SOFT_RELEASE_SQ = 12.0 * 12.0;
    /** 강제 해제(추격 여부 무관) — 이 밖까지 끌려가면 끊고 리시에 맡김(개체군 분산 방지). */
    private static final double HARD_RELEASE_SQ = 48.0 * 48.0;

    @Override
    public boolean canContinueToUse() {
        if (target == null || !target.isAlive() || mob.getIndividual() == null
                || !SurvivalRules.canFight(mob.getStage())) {
            return false;
        }
        // 영구 도주 차단: 좀비가 살아있는 한 무한히 도망치던 결함 — 어그로가 풀리면(좀비 추격 한계
        // ~35블록에서 자연 발생) 종료해 리시가 회수한다. 쫓기는 동안엔 계속 도망(해제가 너무 이르면
        // 도주→해제→따라잡혀 피격→재도주 카이팅이 생기므로 "안 쫓아올 때만" 끊는다).
        double d = mob.distanceToSqr(target);
        if (d > HARD_RELEASE_SQ) {
            return false;
        }
        return d <= SOFT_RELEASE_SQ || target.getTarget() == mob;
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

        // ① 진입: 접근 + 공격. 몬스터도 반격하도록 타겟 지정(§13-B 상호 교전).
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        mob.getNavigation().moveTo(target, 1.15);
        if (target.getTarget() != mob) {
            target.setTarget(mob);
        }
        record("combat:engage");
        if (adjacent) {
            mob.swing(InteractionHand.MAIN_HAND); // 근접 공격 팔 스윙(애니메이션)
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
            SimEvents.event(mob, "전투", combatLabel(tag)
                    + (target != null ? " (상대 " + target.getName().getString() + " #"
                    + target.getId() + ")" : ""));
        }
    }

    private static String combatLabel(String tag) {
        return switch (tag) {
            case "combat:engage" -> "교전 진입";
            case "combat:flee" -> "도주(겁많음)";
            case "combat:retreat" -> "퇴각(체력↓)";
            case "combat:return" -> "전열 복귀";
            default -> tag;
        };
    }

    /** 교전 대상은 <b>좀비만</b>. 스켈레톤은 건드리지 않아 어그로를 끌지 않는다(설계 조정). */
    private Monster nearestMonster(double range) {
        List<Monster> list = mob.level().getEntitiesOfClass(
                Monster.class, mob.getBoundingBox().inflate(range));
        Monster best = null;
        double bestDist = Double.MAX_VALUE;
        for (Monster m : list) {
            if (!(m instanceof Zombie)) {
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
