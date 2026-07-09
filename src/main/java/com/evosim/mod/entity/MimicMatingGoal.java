package com.evosim.mod.entity;

import com.evosim.core.DeterministicRng;
import com.evosim.core.Individual;
import com.evosim.core.Kinship;
import com.evosim.core.Mating;
import com.evosim.core.Multipliers;
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
 * 미믹 짝짓기 goal (설계서 §10 §16). 방랑자 성년이 이성 방랑자를 <b>매력 순으로 줄 세워 위에서부터</b>
 * 구애를 시도한다.
 *
 * <ul>
 *   <li><b>배회 시간대</b>: 넓은 범위(48블록)에서 가장 맘에 드는 상대를 찾아 <b>적극적으로 다가가</b> 시도.</li>
 *   <li><b>노동 시간대</b>: 안 쫓아다니고, 아주 가까이(≈3블록) 있는 상대에게만 스치듯 시도.</li>
 *   <li>접촉하면 조우 판정 <b>1회</b> → 성립이면 정착, 아니면 그 상대는 포기(쿨타임)하고 다음 후보로.
 *       <b>졸졸 따라다니지 않는다.</b></li>
 *   <li>거절/컷이 나면 눈을 낮춘다(간격 제한으로 급락 방지) → 반복될수록 까다로움↓ → 결국 성립.</li>
 * </ul>
 */
public class MimicMatingGoal extends Goal {

    private static final double SEEK_RANGE = 48.0;    // 배회: 이 반경까지 이성 찾아 이동
    private static final double WORK_RANGE = 3.5;      // 노동: 이 안일 때만 시도(안 쫓아감)
    private static final double CONTACT = 2.5;         // 이 거리면 조우 판정
    private static final int FAIL_COOLDOWN = 200;      // 실패한 상대 회피(틱)
    private static final int LOWER_COOLDOWN = 80;      // 눈낮춤 최소 간격(틱) — 급락 방지
    private static final int APPROACH_TIMEOUT = 80;    // 못 따라잡으면 포기(틱)

    private final MimicEntity mob;
    private MimicEntity target;
    private int approachTicks;
    private long lastLowerTick = Long.MIN_VALUE;
    private final Map<Integer, Long> failedUntil = new HashMap<>();

    public MimicMatingGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Individual ind = mob.getIndividual();
        if (ind == null || !mob.isWanderer()) {
            return false;
        }
        double range = seekRange(ind);
        if (range <= 0) {
            return false; // 취침·밤엔 구애 안 함
        }
        pruneFailed();
        approachTicks = 0;
        target = bestCandidate(ind, range);
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        Individual ind = mob.getIndividual();
        return target != null && target.isAlive() && target.isWanderer()
                && mob.isWanderer() && ind != null && seekRange(ind) > 0;
    }

    /** 지금 구애 시도 반경 — 배회는 넓게(적극 탐색), 노동은 근접만, 그 외 0(안 함). */
    private double seekRange(Individual ind) {
        if (StageObserver.isActive()) {
            return SEEK_RANGE;
        }
        return switch (Schedule.phaseAt(ind, mob.level().getDayTime())) {
            case WANDER -> SEEK_RANGE;
            case WORK -> WORK_RANGE;
            default -> 0.0;
        };
    }

    @Override
    public void stop() {
        target = null;
        approachTicks = 0;
    }

    /** 이성 방랑자 후보 중 <b>내가 가장 맘에 드는(매력 높은)</b> 상대. 최근 실패/근친은 제외. */
    private MimicEntity bestCandidate(Individual ind, double range) {
        long now = mob.level().getGameTime();
        MimicEntity best = null;
        int bestCharm = Integer.MIN_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (MimicEntity m : mob.level().getEntitiesOfClass(
                MimicEntity.class, mob.getBoundingBox().inflate(range))) {
            if (m == mob || m.getIndividual() == null || !m.isWanderer()
                    || m.isFemale() == mob.isFemale()) {
                continue;
            }
            if (Kinship.isRelated(ind, m.getIndividual())) {
                continue; // 근친 회피 §13-E
            }
            Long until = failedUntil.get(m.getId());
            if (until != null && now < until) {
                continue; // 최근 실패한 상대는 잠시 제외
            }
            int charm = Multipliers.charmScore(ind, m.getIndividual()); // 내가 본 상대 매력
            double d = mob.distanceToSqr(m);
            if (charm > bestCharm || (charm == bestCharm && d < bestDist)) {
                bestCharm = charm;
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
        if (mob.distanceToSqr(target) > CONTACT * CONTACT) {
            if (++approachTicks > APPROACH_TIMEOUT) {
                fail(target); // 못 따라잡음 → 포기하고 다음 후보로
                target = null;
                return;
            }
            mob.getNavigation().moveTo(target, 1.1);
            return;
        }
        // 접촉 → 조우 판정 1회. 성립/실패 무관하게 이 상대는 여기서 끝(안 쫓아다님).
        mob.getNavigation().stop();
        resolve(target);
        target = null;
    }

    private void resolve(MimicEntity other) {
        switch (Mating.encounter(mob.getIndividual(), mob.getMatingBaseline(),
                other.getIndividual(), other.getMatingBaseline())) {
            case PAIR -> {
                if (mob.isWanderer() && other.isWanderer()) {
                    formPair(other);
                }
            }
            case REJECTED, CUT -> {
                lowerEye();  // 거절당함/내가 컷 → 눈 낮춤(간격 제한)
                fail(other); // 이 상대는 잠시 회피
            }
        }
    }

    /** 눈낮춤 — 간격(LOWER_COOLDOWN) 이상 지났을 때만 한 단계. 급락 방지로 "너무 쉬움" 방지. */
    private void lowerEye() {
        long now = mob.level().getGameTime();
        if (now - lastLowerTick >= LOWER_COOLDOWN) {
            mob.setMatingBaseline(Mating.lowerBaseline(mob.getMatingBaseline()));
            lastLowerTick = now;
        }
    }

    private void fail(MimicEntity other) {
        failedUntil.put(other.getId(), mob.level().getGameTime() + FAIL_COOLDOWN);
    }

    private void pruneFailed() {
        long now = mob.level().getGameTime();
        failedUntil.values().removeIf(t -> now >= t);
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
