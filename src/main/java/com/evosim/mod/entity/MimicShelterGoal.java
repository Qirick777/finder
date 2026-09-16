package com.evosim.mod.entity;

import com.evosim.core.LifeStage;
import com.evosim.core.Schedule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.List;

/**
 * <b>소작농 쉼터에서 쉬기</b> — 하루 수확 한도를 다 쓴 소작이 밭 옆 오두막에 앉아 한도를 되찾는다.
 *
 * <h3>왜</h3>
 * 실측(런 38): "코앞에 익은 타일이 있는데 안 딴다 — 사유: 하루 수확 용량 소진"이 455건, d16 이후에
 * 몰렸고 멈춘 지점은 전부 13/13·14/14 상한이었다. 후반에 밭이 비는 것은 사람이 없어서만이 아니라
 * 와 있는 일꾼이 한도를 다 써서이기도 하다. 하루 한도는 체력의 대리값이므로, 쉬면 조금 더 딴다.
 *
 * <h3>규칙</h3>
 * 쉼터가 돌보는 구획({@link Facilities#SHELTER_REACH})에 배정된 소작만, 한도를 다 쓴 뒤,
 * 근무 시간에, {@link Facilities#SHELTER_REST_TICKS} 마다 한도 1을 되찾는다. 하루 상한은
 * {@link Facilities#SHELTER_RECOVER_MAX}. 자리(카펫 두 칸에 하나)를 차지하므로 정원을 넘으면 못 쉰다 —
 * 그 경우 종전처럼 관리로 넘어간다.
 *
 * <p>자리 점유는 <b>좌표</b>로 본다(그 칸에 다른 미믹이 서 있나). 시설 장부에 사람을 적지 않으므로
 * 죽거나 나가도 정리할 것이 없다.
 */
public class MimicShelterGoal extends Goal {

    private final MimicEntity mob;
    @javax.annotation.Nullable
    private BlockPos seat;
    private int restTicks;

    public MimicShelterGoal(MimicEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.getIndividual() == null || !(mob.level() instanceof ServerLevel sl)
                || mob.getStage() == LifeStage.INFANT || mob.getStage() == LifeStage.BOY) {
            return false;
        }
        if (!mob.isHarvestCapped() || mob.isCritical() || mob.isUnderThreat() || mob.isBuilding()) {
            return false;
        }
        if (mob.shelterRestsToday() >= Facilities.SHELTER_RECOVER_MAX) {
            return false;
        }
        if (Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime()) != Schedule.Phase.WORK) {
            return false;
        }
        FacilityStore.Entry sh = FarmTicker.shelterForWorker(sl, mob);
        if (sh == null) {
            return false;
        }
        seat = freeSeat(sl, sh);
        return seat != null;
    }

    @Override
    public boolean canContinueToUse() {
        return seat != null && mob.isHarvestCapped()
                && mob.shelterRestsToday() < Facilities.SHELTER_RECOVER_MAX
                && !mob.isCritical() && !mob.isUnderThreat()
                && mob.getIndividual() != null
                && Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime()) == Schedule.Phase.WORK;
    }

    @Override
    public void start() {
        restTicks = 0;
        mob.setActivity("쉼터");
    }

    @Override
    public void stop() {
        seat = null;
        restTicks = 0;
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (seat == null) {
            return;
        }
        double dx = (seat.getX() + 0.5) - mob.getX();
        double dz = (seat.getZ() + 0.5) - mob.getZ();
        if (dx * dx + dz * dz > 2.25) {
            restTicks = 0;
            if (mob.getNavigation().isDone()) {
                mob.getNavigation().moveTo(seat.getX() + 0.5, seat.getY(), seat.getZ() + 0.5, 1.0);
            }
            return;
        }
        mob.getNavigation().stop();
        if (++restTicks < Facilities.SHELTER_REST_TICKS) {
            return;
        }
        restTicks = 0;
        if (mob.noteShelterRest() && mob.level() instanceof ServerLevel sl) {
            com.evosim.mod.log.SimEvents.event(mob, "휴식", String.format(
                    "쉼터 @%d,%d — 수확 한도 +1 (오늘 %d/%d · 구획 %d)", seat.getX(), seat.getZ(),
                    mob.shelterRestsToday(), Facilities.SHELTER_RECOVER_MAX,
                    FarmTicker.assignedPlot(mob.getId())));
            // 상한까지 채우고 나간다 — 1 회복할 때마다 돌아가면 한 타일 따러 왕복하게 된다.
            if (sl.getRandom().nextInt(4) == 0) {
                sl.playSound(null, mob.blockPosition(), net.minecraft.sounds.SoundEvents.VILLAGER_CELEBRATE,
                        net.minecraft.sounds.SoundSource.NEUTRAL, 0.3F, 1.2F);
            }
        }
    }

    /** 쉼터 자리 중 아무도 서 있지 않은 칸 — 없으면 null. */
    @javax.annotation.Nullable
    private BlockPos freeSeat(ServerLevel sl, FacilityStore.Entry sh) {
        var tpl = FacilityTemplate.of(sl, sh.kind, sh.rotation, sh.mirrored);
        if (tpl.isEmpty()) {
            return null;
        }
        List<BlockPos> seats = tpl.get().seats();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos rel : seats) {
            BlockPos p = sh.pos.offset(rel);
            boolean taken = false;
            for (MimicEntity o : sl.getEntitiesOfClass(MimicEntity.class,
                    new net.minecraft.world.phys.AABB(p).inflate(0.6))) {
                if (o != mob) {
                    taken = true;
                    break;
                }
            }
            if (taken) {
                continue;
            }
            double d = mob.blockPosition().distSqr(p);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }
}
