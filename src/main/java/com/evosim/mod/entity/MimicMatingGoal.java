package com.evosim.mod.entity;

import com.evosim.core.DeterministicRng;
import com.evosim.core.Kinship;
import com.evosim.core.Mating;
import com.evosim.core.Settlement;
import com.evosim.mod.stage.StageObserver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * 미믹 짝짓기 goal (설계서 §10). 방랑자 성년이 인지 반경 내 이성 방랑자에게 다가가 조우 판정을 하고,
 * 짝이 성립하면 <b>겹치지 않는 새 거처</b>(§13-D)를 잡아 둘 다 그 좌표로 정착시킨다.
 */
public class MimicMatingGoal extends Goal {

    private final MimicEntity mob;
    private MimicEntity partner;

    public MimicMatingGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.getIndividual() == null || !mob.isWanderer()) {
            return false;
        }
        this.partner = findWanderer();
        return partner != null;
    }

    @Override
    public boolean canContinueToUse() {
        return partner != null && partner.isAlive() && mob.isWanderer() && partner.isWanderer();
    }

    @Override
    public void stop() {
        this.partner = null;
    }

    private MimicEntity findWanderer() {
        MimicEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (MimicEntity m : mob.level().getEntitiesOfClass(
                MimicEntity.class, mob.getBoundingBox().inflate(10.0))) {
            if (m == mob || m.getIndividual() == null || !m.isWanderer()) {
                continue;
            }
            if (m.isFemale() == mob.isFemale()) {
                continue; // 이성만
            }
            if (Kinship.isRelated(mob.getIndividual(), m.getIndividual())) {
                continue; // 근친(형제·부모자식) 회피 §13-E
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
        if (partner == null || partner.getIndividual() == null) {
            return;
        }
        mob.getLookControl().setLookAt(partner, 30.0F, 30.0F);
        if (mob.distanceToSqr(partner) > 4.0) {
            mob.getNavigation().moveTo(partner, 1.0);
            return;
        }
        // 인접 → 조우 판정. 낮은 id가 주도(양쪽 동시 처리 방지).
        if (mob.getId() > partner.getId()) {
            return;
        }
        switch (Mating.encounter(mob.getIndividual(), mob.getMatingBaseline(),
                partner.getIndividual(), partner.getMatingBaseline())) {
            case PAIR -> formPair(partner);
            case REJECTED -> mob.setMatingBaseline(Mating.lowerBaseline(mob.getMatingBaseline()));
            case CUT -> { /* 유지 */ }
        }
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

