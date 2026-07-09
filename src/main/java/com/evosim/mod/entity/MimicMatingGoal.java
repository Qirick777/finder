package com.evosim.mod.entity;

import com.evosim.core.DeterministicRng;
import com.evosim.core.Individual;
import com.evosim.core.Kinship;
import com.evosim.core.Mating;
import com.evosim.core.Schedule;
import com.evosim.core.Settlement;
import com.evosim.mod.log.SimEvents;
import com.evosim.mod.stage.StageObserver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 미믹 짝짓기 goal (설계서 §10 §16). <b>배회 시간대</b>에 방랑자 성년이 돌아다니다 이성 방랑자를 만나
 * 잠시 구애(마주봄)한 뒤 조우 판정을 한다.
 *
 * <ul>
 *   <li>성립 → 겹치지 않는 새 거처(§13-D)를 잡아 둘 다 정착.</li>
 *   <li>거절당함 → <b>내 기준선을 낮추고</b>(눈낮춤 §10) 그 상대는 잠시 회피, 다른 상대를 찾아 배회.</li>
 *   <li>내가 컷 → 그 상대는 잠시 회피, 다른 상대를 찾아 배회.</li>
 * </ul>
 *
 * <p>핵심: 거절/컷이면 그 자리에 붙박이지 않고 <b>딴 짝을 찾아 다시 배회</b>한다. 양쪽이 각자 관점에서
 * 판정하므로(둘 다 거절당하면 둘 다 눈을 낮춤) 척박한 무리도 결국 맺어진다(교착 없음).
 */
public class MimicMatingGoal extends Goal {

    private static final int COURT_TIME = 25;        // 인접 후 구애 지속(틱) — 눈에 보이는 마주봄
    private static final int REJECT_COOLDOWN = 160;   // 거절/컷 상대 재구애 금지(틱) → 딴 짝 찾기
    private static final double SEEK_RANGE = 12.0;

    private final MimicEntity mob;
    private MimicEntity target;
    private int courtTimer;
    private final Map<Integer, Long> avoidUntil = new HashMap<>();

    public MimicMatingGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.getIndividual() == null || !mob.isWanderer() || !courtingTime()) {
            return false;
        }
        this.courtTimer = 0;
        this.target = findPartner();
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && target.isAlive() && target.isWanderer()
                && mob.isWanderer() && courtingTime();
    }

    /** 구애 가능 시간대 — 평상시엔 배회 구간만, 무대 검증 중엔 항상(결정론). */
    private boolean courtingTime() {
        if (StageObserver.isActive()) {
            return true;
        }
        Individual ind = mob.getIndividual();
        return ind != null
                && Schedule.phaseAt(ind, mob.level().getDayTime()) == Schedule.Phase.WANDER;
    }

    @Override
    public void stop() {
        this.target = null;
        this.courtTimer = 0;
    }

    /** 가장 가까운 적격 방랑자(이성·비근친·최근 실패 상대 제외). */
    private MimicEntity findPartner() {
        long now = mob.level().getGameTime();
        MimicEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (MimicEntity m : mob.level().getEntitiesOfClass(
                MimicEntity.class, mob.getBoundingBox().inflate(SEEK_RANGE))) {
            if (m == mob || m.getIndividual() == null || !m.isWanderer()) {
                continue;
            }
            if (m.isFemale() == mob.isFemale()) {
                continue; // 이성만
            }
            if (Kinship.isRelated(mob.getIndividual(), m.getIndividual())) {
                continue; // 근친 회피 §13-E
            }
            Long until = avoidUntil.get(m.getId());
            if (until != null && now < until) {
                continue; // 최근 구애 실패 상대는 잠시 회피
            }
            double d = mob.distanceToSqr(m);
            if (d < bestDist) {
                bestDist = d;
                best = m;
            }
        }
        return best;
    }

    @Override
    public void tick() {
        if (target == null || target.getIndividual() == null) {
            return;
        }
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (mob.distanceToSqr(target) > 4.0) {
            mob.getNavigation().moveTo(target, 1.0);
            courtTimer = 0;
            return;
        }
        // 인접 → 잠시 마주보며 구애, 그 뒤 조우 판정(§10).
        mob.getNavigation().stop();
        if (++courtTimer < COURT_TIME) {
            return;
        }
        courtTimer = 0;
        switch (Mating.encounter(mob.getIndividual(), mob.getMatingBaseline(),
                target.getIndividual(), target.getMatingBaseline())) {
            case PAIR -> {
                if (mob.isWanderer() && target.isWanderer()) {
                    formPair(target);
                }
            }
            case REJECTED -> {
                mob.setMatingBaseline(Mating.lowerBaseline(mob.getMatingBaseline())); // 눈낮춤 §10
                avoid(target);
                target = null; // 딴 짝을 찾아 다시 배회
            }
            case CUT -> {
                avoid(target);
                target = null;
            }
        }
    }

    private void avoid(MimicEntity other) {
        avoidUntil.put(other.getId(), mob.level().getGameTime() + REJECT_COOLDOWN);
    }

    private void formPair(MimicEntity other) {
        List<int[]> existing = new ArrayList<>();
        for (MimicEntity m : mob.level().getEntitiesOfClass(
                MimicEntity.class, mob.getBoundingBox().inflate(64.0))) {
            BlockPos h = m.getHomePos();
            if (h != null) {
                existing.add(new int[] {h.getX(), h.getZ()});
            }
        }
        int dist = Settlement.homeDistance(mob.getIndividual(), other.getIndividual());
        int anchorY = mob.blockPosition().getY();
        int[] anchor = {mob.blockPosition().getX(), mob.blockPosition().getZ()};
        DeterministicRng rng = new DeterministicRng(mob.getRandom().nextLong());
        int[] pos = Settlement.placeHome(anchor, dist, existing, Settlement.MIN_GAP, rng);
        BlockPos home = new BlockPos(pos[0], anchorY, pos[1]);

        mob.setHomePos(home);
        other.setHomePos(home);
        heartEffect(mob);
        heartEffect(other);
        StageObserver.record(mob.getId(), "mating:pair");
        SimEvents.event(mob, "짝성립", "상대 #" + other.getId() + " · 거처 @"
                + home.getX() + "," + home.getY() + "," + home.getZ());
    }

    /** 동물 교배 하트 이펙트 (설계서 관찰 편의) — 짝 성립 순간 양쪽에 표시. */
    private static void heartEffect(MimicEntity m) {
        if (m.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.HEART,
                    m.getX(), m.getY() + m.getBbHeight() * 0.6, m.getZ(),
                    7, m.getBbWidth() * 0.5, m.getBbHeight() * 0.4, m.getBbWidth() * 0.5, 0.02);
        }
    }
}
