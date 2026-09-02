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
    private int cursor = -1;    // 순찰 경로 커서 — id 기준 시작점(병사마다 다른 구역)

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

    /**
     * <b>순찰 표적 — 지키는 집들을 돈다.</b>
     *
     * <p>종전에는 막사 둘레 반경 20의 무작위 점이었다. 그래서 병사가 막사 근처만 어슬렁거렸다
     * (육안 관측). 반경 숫자를 키우는 것으로도 넓힐 수는 있지만, 그러면 <b>아무것도 없는
     * 들판</b>을 돌게 되고 지형에 처박힐 자리도 늘어난다.
     *
     * <p>지킬 대상이 곧 순찰 경로다. 막사 정원을 정할 때 이미 세는 그 목록
     * ({@code FarmTicker.followerHomesOf} 를 통근 한계로 거른 것)을 그대로 순회한다:
     *
     * <ul>
     *   <li>범위가 <b>추종 가구가 퍼진 만큼</b> 넓어진다 — 세력이 크면 순찰도 넓다.</li>
     *   <li>경계 한계는 따로 둘 필요가 없다. 그 목록이 이미
     *       {@link Facilities#COMMUTE_RANGE} 안이라 "멀리 갔다 길을 잃는" 경우가 없다.</li>
     *   <li>집과 길 위로 다니므로 헤매지 않는다.</li>
     *   <li>병사마다 <b>다른 집에서 시작</b>해(id 기준 커서) 서로 다른 구역을 맡는다 —
     *       밭 손질 순회와 같은 방식이다.</li>
     * </ul>
     *
     * <p>추종 가구가 하나도 없으면 종전대로 막사 둘레를 돈다(폴백).
     */
    private BlockPos patrolSpot() {
        if (post == null || !(mob.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return null;
        }
        java.util.List<BlockPos> route = guardedHomes(sl);
        if (!route.isEmpty()) {
            if (cursor < 0) {
                cursor = (int) Math.floorMod(mob.getIndividual().id(), route.size());
            }
            BlockPos home = route.get(Math.floorMod(cursor, route.size()));
            cursor++;
            // 집 안이 아니라 <b>문간</b>에 선다 — 남의 거처 한가운데 서 있는 그림을 피한다.
            int y = sl.getHeightmapPos(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    home).getY();
            return new BlockPos(home.getX(), y, home.getZ());
        }
        double ang = mob.getRandom().nextDouble() * Math.PI * 2.0;
        // 경계가 좋은 병사가 넓은 구역을 맡는다 — 용감(인지 8→14)이면 반경 ×1.32.
        // <b>√로 누른다</b>: 반경에 그대로 비례시키면 덮는 면적이 인지거리의 제곱으로 늘어
        // 한 명이 마을을 다 도는 그림이 된다. 순찰 반경은 보호세·정원과 무관한(그쪽은 막사
        // 기준 통근반경으로 센다) 순수 거동이지만, 그래도 눈에 그럴듯해야 한다.
        double keen = Math.sqrt(Math.max(0.25,
                com.evosim.core.Combat.detectionRange(mob.getIndividual()) / 8.0));
        double rad = PATROL_RADIUS * keen * (0.4 + 0.6 * mob.getRandom().nextDouble());
        int x = post.getX() + (int) Math.round(Math.cos(ang) * rad);
        int z = post.getZ() + (int) Math.round(Math.sin(ang) * rad);
        int y = sl.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new BlockPos(x, post.getY(), z)).getY();
        return new BlockPos(x, y, z);
    }

    /** 이 막사가 지키는 추종 가구의 집 — 가구 단위(중복 제거), 통근 한계 안. */
    private java.util.List<BlockPos> guardedHomes(net.minecraft.server.level.ServerLevel sl) {
        FacilityStore reg = FacilityStore.get(sl);
        long ownerId = 0L;
        for (FacilityStore.Entry e : reg.all()) {
            if (e.kind.group == FacilityTemplate.Group.BARRACKS && e.pos.equals(post)) {
                ownerId = e.ownerId;
                break;
            }
        }
        if (ownerId == 0L) {
            return java.util.List.of();
        }
        // 순서를 고정한다 — 좌표 정렬. 그래야 커서가 매일 같은 경로를 돌고, 병사마다 다른
        // 시작점이 실제로 다른 구역이 된다(순서가 흔들리면 커서가 뜻을 잃는다).
        java.util.List<BlockPos> out = new java.util.ArrayList<>(
                new java.util.LinkedHashSet<>(FarmTicker.followerHomesOf(ownerId)));
        out.removeIf(h -> h.distSqr(post)
                > Facilities.COMMUTE_RANGE * Facilities.COMMUTE_RANGE);
        out.sort(java.util.Comparator.comparingInt((BlockPos h) -> h.getX())
                .thenComparingInt((BlockPos h) -> h.getZ()));
        return out;
    }
}
