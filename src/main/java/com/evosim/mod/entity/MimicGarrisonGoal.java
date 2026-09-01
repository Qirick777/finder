package com.evosim.mod.entity;

import com.evosim.core.LifeStage;
import com.evosim.core.Schedule;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 주둔 goal — 군인은 <b>낮에 막사에서 쉬고 밤에 둘레를 경계한다</b>.
 *
 * <p>배속은 {@link FarmTicker#runBarracks} 가 하루 1회 정한다. 이 goal 은 그 배속을 읽어
 * 자리로 데려가고, 밤에는 막사 둘레를 돈다. 봉급·세금·이탈은 전부 정산이 맡고 여기는
 * <b>행동만</b> 한다 — 돈을 만지는 곳을 한 군데로 모으는 기존 규칙 그대로다.
 *
 * <p><b>길을 잃지 않는 장치</b>가 둘이다. 막사를 출근 앵커로 삼아 리시(우선순위 2)가
 * 방해자가 아니라 호위자가 되게 하고(밭 출근에서 검증된 패턴), 순찰 표적은 막사 둘레
 * {@link #PATROL_RADIUS} 안에서만 고른다. 좀비를 쫓다 반경을 넘는 경우는 리시가 교전 중
 * 물러나므로(MimicEntity.computeUnderThreat 에 추격을 더했다) 끌려 돌아오지 않는다.
 */
public class MimicGarrisonGoal extends Goal {

    /** 순찰 표적을 고르는 막사 둘레 반경 — 경계 구역이 곧 이 원이다. */
    private static final double PATROL_RADIUS = 20.0;

    /** 한 순찰 지점에 머무는 틱 — 도착한 뒤에만 센다(밭 손질과 같은 리듬). */
    private static final int STAND_TICKS = 60;

    /** 도착 판정. */
    private static final double ARRIVE = 2.5;

    private final MimicEntity mob;
    private BlockPos post;      // 막사 등기 좌표
    private BlockPos spot;      // 지금 가는 곳(낮=제 자리, 밤=순찰 지점)
    private int stand;
    private boolean night;

    public MimicGarrisonGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.getIndividual() == null || mob.getStage() != LifeStage.ADULT
                || mob.isBuilding() || mob.isFastSettle() || mob.isCourtTravel()) {
            return false;
        }
        post = FarmTicker.postOf(mob);
        if (post == null) {
            return false; // 배속 없음 — 군인이 아니다
        }
        // 위급이면 물러난다 — 먹는 것이 먼저다. 고용주 구휼이 새벽에 채워 준다.
        if (mob.isCritical()) {
            return false;
        }
        boolean sleep = Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime())
                == Schedule.Phase.SLEEP;
        if (sleep != night) {
            night = sleep;
            spot = null; // 근무가 바뀌면 표적을 새로 고른다
            stand = 0;
        }
        if (spot == null) {
            spot = night ? patrolSpot() : FarmTicker.guardSeatOf(mob);
            if (spot == null) {
                spot = post;
            }
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        // 막사를 출근 앵커로 — 리시가 거처로 되끌지 않고 오히려 여기까지 데려다 준다.
        mob.setWorkAnchor(post);
        mob.setActivity(night ? "경계" : "주둔");
    }

    @Override
    public void stop() {
        spot = null;
        stand = 0;
    }

    @Override
    public void tick() {
        if (spot == null) {
            return;
        }
        mob.setWorkAnchor(post);
        mob.setActivity(night ? "경계" : "주둔");
        if (!mob.blockPosition().closerThan(spot, ARRIVE)) {
            mob.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5,
                    night ? 1.0 : 0.9);
            return;
        }
        mob.getNavigation().stop();
        if (night) {
            // 경계 — 둘레를 둘러본다. 다 서 있으면 다음 지점으로.
            mob.getLookControl().setLookAt(post.getX() + 0.5, post.getY() + 1.0, post.getZ() + 0.5);
            if (++stand >= STAND_TICKS) {
                spot = patrolSpot();
                stand = 0;
            }
            return;
        }
        // 휴식 — 제 자리에 머문다. 밤이 오면 canUse 가 표적을 바꾼다.
        stand = 0;
    }

    /** 막사 둘레 반경 안의 순찰 지점 — 결정론 난수라 병사마다 다른 곳을 돈다. */
    private BlockPos patrolSpot() {
        if (post == null || !(mob.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return null;
        }
        double ang = mob.getRandom().nextDouble() * Math.PI * 2.0;
        double rad = PATROL_RADIUS * (0.4 + 0.6 * mob.getRandom().nextDouble());
        int x = post.getX() + (int) Math.round(Math.cos(ang) * rad);
        int z = post.getZ() + (int) Math.round(Math.sin(ang) * rad);
        int y = sl.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new BlockPos(x, post.getY(), z)).getY();
        return new BlockPos(x, y, z);
    }
}
