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
    private boolean wounded;
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
            mob.setGuardAnchor(null); // 배속이 풀렸다 — <b>여기서만</b> 앵커를 놓는다
            return false;
        }
        // 위급이면 물러난다 — 먹는 것이 먼저다. 고용주 구휼이 새벽에 채워 준다.
        if (mob.isCritical()) {
            return false;
        }
        // 다치거나 나으면 근무지를 다시 고른다 — 후송 ↔ 근무 전환점.
        // <b>치료 상태는 히스테리시스다</b>(퇴각선 30% 에서 켜지고 복귀선 70% 에서 꺼진다).
        // isWounded 하나로 보면 31% 에서 곧장 근무로 돌아가 야전병원 체류가 10%p 뿐이고,
        // 막사 문 앞에서 30% 를 오르내리며 후송↔근무를 갈아타는 진동이 생긴다.
        if (wounded != mob.isUnderTreatment()) {
            wounded = mob.isUnderTreatment();
            spot = null;
            stand = 0;
        }
        boolean sleep = Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime())
                == Schedule.Phase.SLEEP;
        if (sleep != night) {
            night = sleep;
            spot = null; // 근무가 바뀌면 표적을 새로 고른다
            stand = 0;
        }
        if (spot == null) {
            // <b>낮에는 압박만, 밤에는 전 구역 순찰.</b>
            //
            // 종전에는 낮이면 무조건 제 자리에 앉았다. 그런데 야간 순찰은 밤 스킵과 정면으로
            // 부딪힌다: night 는 tod ≥ 14000(SLEEP)인데 밤 스킵이 tod 14100 에서 기상으로
            // 점프하므로 순찰 창이 <b>100틱</b>뿐이다(실측). 52블록을 걷는 데 편도 190틱이
            // 필요하니 표적에 닿을 수가 없다 — 관측 런은 전부 스킵을 켜므로, 야간 순찰은
            // 사실상 한 번도 작동한 적이 없다.
            //
            // 압박을 거기 묶어 두면 같은 이유로 영영 발동하지 않는다. 그래서 <b>표적이 있으면
            // 낮에도 나간다</b>. 시위는 오히려 대낮에 보여야 뜻이 맞고, 눈으로 확인하기도 쉽다.
            // 표적이 없으면 종전대로 제 자리에 앉는다 — 평시 막사가 비지 않는다.
            // <b>부상병은 근무보다 후송이 먼저다.</b> 회복은 소지 식량에 물려 있으므로
            // (regenTick), 아군 막사에서 급양을 받아야 다시 싸울 수 있다. 복귀선(70%)까지
            // 나으면 아래 평소 근무지로 돌아간다.
            spot = mob.isUnderTreatment()
                    ? FarmTicker.nearestFriendlyBarracks(sl0(), mob)
                    : (night ? patrolSpot() : dayPost());
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
        // <b>앵커는 여기서 지우지 않는다.</b> 이 stop 의 대부분은 리시(2)가 MOVE 를 탈취해
        // 생기는 선점이고, 그때 앵커를 지우면 리시가 볼 목적지가 사라져 병사를 막사로
        // 되끌어 버린다 — 출발 → 선점 → 되끌림 → 재출발의 무한 왕복이 된다.
        //
        // 실측(압박 시험): 앵커 24,20(표적)으로 출발 → Leash(2) 선점 · 주둔앵커 없음 ·
        // 앵커 -10,-20(막사) → 다시 Garrison(4) · 앵커 24,20. 최근접이 38~61 을 오갈 뿐
        // 표적에 영영 못 닿았다.
        //
        // ElderVisitGoal 이 같은 함정을 이미 주석으로 남겨 뒀고 구걸에서도 지켰는데
        // 여기서 되풀이했다. 해제는 배속이 풀렸을 때(canUse)만 한다.
    }

    @Override
    public void tick() {
        if (spot == null) {
            return;
        }
        mob.setWorkAnchor(post);
        // 리시가 <b>지금 가는 곳</b>을 보게 한다. 순찰·압박 표적은 막사에서 최대
        // COMMUTE_RANGE(96)까지 떨어져 있는데 활동반경은 32 라, 막사를 앵커로 두면
        // 리시가 병사를 도로 끌어 근무지에 영영 못 닿는다.
        mob.setGuardAnchor(spot);
        mob.setActivity(night ? "경계" : "주둔");
        // 압박은 <b>몸이 어디 있는가</b>로 센다 — 목적지 도착 판정에 기대지 않는다.
        // 거처 좌표는 천막 구조물 안쪽이라 도착(2.5블록)이 영영 성립하지 않을 수 있다.
        FarmTicker.reportPressureNear(post, mob.blockPosition());
        if (!mob.blockPosition().closerThan(spot, ARRIVE)) {
            mob.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5,
                    night ? 1.0 : 0.9);
            return;
        }
        mob.getNavigation().stop();
        // 후송 도착 — 아군 막사에서 급양을 받는다(배부르면 안에서 곧바로 돌아온다).
        if (mob.isUnderTreatment() && mob.level() instanceof net.minecraft.server.level.ServerLevel ms) {
            FarmTicker.medicate(ms, mob, spot);
        }
        if (night) {
            // 경계 — 둘레를 둘러본다. 다 서 있으면 다음 지점으로.
            mob.getLookControl().setLookAt(post.getX() + 0.5, post.getY() + 1.0, post.getZ() + 0.5);
            if (++stand >= STAND_TICKS) {
                spot = patrolSpot();
                stand = 0;
            }
            return;
        }
        // 낮 — 압박 표적 앞이면 잠시 서 있다가 다음 표적으로. 제 자리면 그대로 머문다.
        if (!FarmTicker.pressureHomesOf(post).isEmpty()) {
            if (++stand >= STAND_TICKS) {
                spot = dayPost();
                stand = 0;
            }
            return;
        }
        stand = 0;
    }

    /**
     * 낮 근무지 — 압박 표적이 있으면 그 집 앞, 없으면 제 자리.
     *
     * <p>표적을 도는 순서는 {@link #cursor} 를 그대로 쓴다. 병사마다 다른 표적에서 시작해
     * 여럿이 한 집에 몰리지 않는다.
     */
    private net.minecraft.server.level.ServerLevel sl0() {
        return (net.minecraft.server.level.ServerLevel) mob.level();
    }

    private BlockPos dayPost() {
        if (post == null || !(mob.level() instanceof net.minecraft.server.level.ServerLevel)) {
            return FarmTicker.guardSeatOf(mob);
        }
        // <b>출격이 먼저다.</b> 겹친 세력권은 압박이 아니라 전투로 갈린다 — 그런데 압박 표적에서
        // 무장 세력이 빠지므로, 이 줄이 없으면 병사는 제 막사에 앉은 채 평생 적을 만나지
        // 않는다(실측 P6: 12명 대 8명이 30블록 거리에서 전투 0건). 압박이 표적 집 앞에 서는
        // 것으로 성립하듯, 전쟁은 적 막사 앞에 서는 것으로 성립한다.
        BlockPos foe = FarmTicker.sortieOf(mob);
        if (foe != null) {
            return foe;
        }
        java.util.List<BlockPos> targets = FarmTicker.pressureHomesOf(post);
        if (targets.isEmpty()) {
            return FarmTicker.guardSeatOf(mob);
        }
        if (cursor < 0) {
            cursor = (int) Math.floorMod(mob.getIndividual().id(), targets.size());
        }
        BlockPos home = targets.get(Math.floorMod(cursor, targets.size()));
        cursor++;
        return home;
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
        // 지킬 집 + <b>압박 표적</b>. 표적을 경로에 얹는 것이 압박의 전부다 — 병사가 실제로
        // 그 집 앞에 서고, 그 사실이 그날의 신세가 된다(FarmTicker.reportPressureVisit).
        // 새 순찰 종류를 만들지 않고 같은 순회에 넣는 이유: 따로 두면 병사가 둘 사이를
        // 오가며 목표를 갈아탄다 — 이 프로젝트가 거듭 데인 그 진동이다.
        java.util.List<BlockPos> route = new java.util.ArrayList<>(guardedHomes(sl));
        route.addAll(FarmTicker.pressureHomesOf(post));
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
