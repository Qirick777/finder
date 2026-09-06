package com.evosim.mod.entity;

import com.evosim.core.LifeStage;
import com.evosim.core.Schedule;
import com.evosim.mod.log.SimEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 경비 goal — 경비대원은 <b>낮에 경비대에서 쉬고 밤에 동네를 돈다</b>.
 *
 * <p>소속·봉급·입퇴소는 {@link FarmTicker#runPoorhouses} 가 하루 1회 정한다. 이 goal 은 그
 * 소속을 읽어 행동만 한다 — 돈을 만지는 곳을 한 군데로 모으는 기존 규칙 그대로다.
 *
 * <p><b>군인({@link MimicGarrisonGoal})과 무엇이 다른가.</b> 군인은 주인의 추종 가구를 돌며
 * 전쟁과 압박을 맡고, 경비대는 <b>시설 둘레의 집</b>을 돌며 밤 경계만 맡는다. 순찰 경로를
 * 추종 가구가 아니라 시설 반경 안의 등기된 집에서 뽑는 이유가 그것이다 — 경비대는 세력의
 * 무력이 아니라 동네의 조직이고, 주인이 누구든 이웃을 지킨다.
 *
 * <p><b>낮은 진짜로 쉰다.</b> 채집·사냥·정원은 {@link MimicForageGoal#canUse} 가 소속을 보고
 * 통째로 끊는다. 그래서 이들의 수입은 봉급 하나뿐이고, 봉급은 성인 하루 소모와 같은 수로
 * 맞춰 두었다({@link Facilities#POORHOUSE_STIPEND}). 지주가 봉급을 못 내면 그대로 굶는다 —
 * 부양력이 정원을 제한한다는 규칙이 실제로 물리게 하는 자리다.
 */
public class MimicWatchGoal extends Goal {

    /** 순찰 경로를 뽑는 시설 둘레 반경 — 이 안의 등기된 집이 곧 경계 구역이다. */
    private static final double WATCH_RADIUS = 48.0;

    /** 한 순찰 지점에 머무는 틱 — 도착한 뒤에만 센다(주둔과 같은 리듬). */
    private static final int STAND_TICKS = 60;

    /** 도착 판정 — 설 수 있는 좌표에 쓴다(주둔과 같은 눈금). */
    private static final double ARRIVE = 2.5;

    /**
     * 도착에 못 닿은 채 흐를 수 있는 최대 틱 — 넘으면 표적을 놓고 다음 것을 고른다.
     *
     * <p>{@link MimicGarrisonGoal} 의 같은 장치와 <b>같은 수</b>를 쓴다.
     *
     * <p>처음에 300 으로 줄였다가 실측으로 되돌렸다. "경계 구역이 반경 48 이라 군인(96)보다
     * 좁으니 한도도 짧게"라고 봤는데, 틀렸다 — 좁은 것은 <b>시설에서 표적까지</b>이지
     * <b>대원에서 표적까지</b>가 아니다. 대원은 밤에 제 집에서 시작하고 그 집이 시설에서
     * 48 이면, 반대편 표적까지는 96 이 된다. 이동이 52블록당 190틱(≈3.65틱/블록)이므로
     * 96블록은 ~350틱이고, 300 에서는 <b>닿기 직전에 매번 표적을 버린다</b>.
     *
     * <p>실측(경비대 첫 런 d6·d7): 두 밤 모두 순찰 지점 <b>0곳</b>. 군인 쪽 주석이 이미
     * "먼 표적도 350틱이면 닿는다 · 600 은 그 위의 여유"라고 적어 둔 것을 읽고도 줄인 것이
     * 실수였다.
     */
    private static final int TRAVEL_LIMIT = 600;

    private final MimicEntity mob;
    private BlockPos post;   // 경비대 등기 좌표
    private BlockPos spot;   // 지금 가는 곳(낮=시설, 밤=순찰 지점)
    private int stand;
    private int travel;
    private boolean night;
    private int cursor = -1; // 순찰 경로 커서 — id 기준 시작점(대원마다 다른 구역)
    private int visits;      // 이 밤에 실제로 닿은 순찰 지점 수(계측 — 아침에 한 줄로 뱉는다)

    public MimicWatchGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.getIndividual() == null || mob.getStage() != LifeStage.ADULT
                || mob.isBuilding() || mob.isFastSettle() || mob.isCourtTravel()) {
            return false;
        }
        if (!mob.inPoorhouse()) {
            post = null;
            mob.setGuardAnchor(null); // 소속이 풀렸다 — <b>여기서만</b> 앵커를 놓는다(주둔과 같다)
            return false;
        }
        // <b>군인이 이긴다.</b> 경비대원 중 능력이 되는 자는 군인으로 승격되는데, 그때 두 goal 이
        // 같은 우선순위(4)에서 같은 guardAnchor 를 두고 다툰다. 무장도 이미 군인 쪽이 이기게
        // 되어 있다(setPauperGear 는 isSoldier 를 제외한다) — 같은 손을 여기서도 들어 준다.
        if (FarmTicker.isSoldier(mob)) {
            return false;
        }
        post = mob.getPoorhouse();
        if (post == null) {
            return false;
        }
        // 위급이면 물러난다 — 주둔과 같다. 봉급이 끊겨 굶는 상태이므로 경계를 시킬 자리가
        // 아니고, 구걸 goal 이 시설로 데려간다.
        if (mob.isCritical()) {
            return false;
        }
        boolean sleep = Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime())
                == Schedule.Phase.SLEEP;
        if (sleep != night) {
            // <b>밤이 끝날 때 한 줄 남긴다.</b> setActivity 는 머리 위 라벨만 바꾸고 로그를
            // 남기지 않는다 — 그대로 두면 "경계가 실제로 도는가"를 로그로 판정할 수 없다.
            // 지금까지 야간 순찰이 한 번도 작동한 적 없었다는 사실(MimicGarrisonGoal 주석)이
            // 오래 드러나지 않은 것도 재는 눈이 없었기 때문이다. 하룻밤에 한 줄이라 시끄럽지도
            // 않고, 0 이면 0 이라고 말한다 — 침묵은 진단이 아니다.
            if (night && !sleep && mob.level() instanceof net.minecraft.server.level.ServerLevel) {
                SimEvents.event(mob, "경계", String.format(
                        "밤 근무 끝 — 순찰 지점 %d곳 · 경비대 @%d,%d 에서 %.0f블록",
                        visits, post.getX(), post.getZ(),
                        Math.sqrt(mob.blockPosition().distSqr(post))));
            }
            visits = 0;
            night = sleep;
            spot = null; // 근무가 바뀌면 표적을 새로 고른다
            stand = 0;
            travel = 0;
        }
        if (spot == null) {
            spot = night ? watchSpot() : post;
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
        // 시설을 출근 앵커로 — 리시(우선순위 2)가 거처로 되끌지 않고 여기까지 데려다 준다.
        // 밤에 실제로 리시를 붙드는 것은 guardAnchor 쪽이다(tick 참조).
        mob.setWorkAnchor(post);
        mob.setGuardAnchor(spot != null ? spot : post);
        mob.setActivity(night ? "경계" : "대기");
    }

    @Override
    public void stop() {
        spot = null;
        stand = 0;
        travel = 0;
        mob.setWorkAnchor(null);
        // <b>guardAnchor 는 여기서 놓지 않는다</b>(주둔과 같은 규칙). stop 은 전투 같은 일시
        // 선점에서도 불리는데, 그때 앵커를 지우면 리시가 곧장 거처로 끌고 가 근무지에 다시
        // 못 온다. 해제는 소속이 실제로 풀렸을 때(canUse) 한 곳에서만 한다.
    }

    @Override
    public void tick() {
        if (spot == null) {
            return;
        }
        mob.setWorkAnchor(post);
        // <b>리시가 지금 가는 곳을 보게 한다.</b> 이것이 없으면 야간 경계가 영영 안 돈다:
        // {@link MimicEntity#roamAnchor} 는 guardAnchor 를 먼저 보고, workAnchor 는
        // <b>WORK 시간대에만</b> 쓰인다. 밤은 SLEEP 이라 workAnchor 가 통째로 무시되고,
        // 앵커가 거처로 떨어져 리시(우선순위 2)가 대원을 집으로 끌고 간다 — 그러면 경비
        // goal(4)은 start() 조차 못 하므로 앵커를 고칠 기회도 없다(자기참조 교착).
        //
        // 실측(경비대 첫 런 d6): 실행 goal 이 [Leash] 였고 순찰 지점 0곳. 군인 쪽은 매 틱
        // setGuardAnchor(spot) 으로 같은 문제를 이미 풀어 두었는데, 새 goal 을 쓰면서 그
        // 한 줄을 빠뜨렸다.
        mob.setGuardAnchor(spot);
        mob.setActivity(night ? "경계" : "대기");
        double d2 = mob.blockPosition().distSqr(spot);
        if (d2 > ARRIVE * ARRIVE) {
            mob.getLookControl().setLookAt(spot.getX() + 0.5, spot.getY() + 1.0, spot.getZ() + 0.5);
            mob.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.0);
            if (++travel >= TRAVEL_LIMIT) {
                spot = night ? watchSpot() : post; // 못 닿는 자리 — 놓고 다음
                travel = 0;
                stand = 0;
            }
            return;
        }
        travel = 0;
        if (!night) {
            stand = 0;
            return; // 낮 — 시설에 그대로 머문다(쉬는 것이 근무다)
        }
        if (++stand >= STAND_TICKS) {
            visits++; // 여기 왔다는 것은 도착 판정을 통과했다는 뜻이다(계측)
            spot = watchSpot();
            stand = 0;
        }
    }

    /**
     * <b>순찰 표적 — 시설 둘레의 집들을 돈다.</b>
     *
     * <p>{@link MimicGarrisonGoal#patrolSpot} 과 같은 원리다. 무작위 들판 점을 돌면 아무것도
     * 없는 곳을 돌게 되고 지형에 처박힌다 — 지킬 대상이 곧 경로여야 한다. 다만 목록을
     * 뽑는 기준이 다르다: 군인은 <b>주인의 추종 가구</b>, 경비대는 <b>시설 반경 안의 집</b>
     * 전부다(주인이 누구든 이웃을 지킨다).
     *
     * <p>집이 하나도 없으면 시설 자리를 그대로 돌려준다 — 돌 곳이 없으면 안 돈다.
     */
    private BlockPos watchSpot() {
        if (post == null || !(mob.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return null;
        }
        java.util.List<BlockPos> route = new java.util.ArrayList<>();
        for (BlockPos h : HomeStore.get(sl).positions()) {
            if (h.distSqr(post) <= WATCH_RADIUS * WATCH_RADIUS) {
                route.add(h);
            }
        }
        if (route.isEmpty()) {
            return post;
        }
        if (cursor < 0) {
            cursor = (int) Math.floorMod(mob.getIndividual().id(), route.size());
        }
        BlockPos home = route.get(Math.floorMod(cursor, route.size()));
        cursor++;
        // 집 안이 아니라 문간에 선다 — 남의 거처 한가운데 서 있는 그림을 피한다.
        int y = sl.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                home).getY();
        return new BlockPos(home.getX(), y, home.getZ());
    }
}
