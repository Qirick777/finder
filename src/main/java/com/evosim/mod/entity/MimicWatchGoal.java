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
 * 통째로 끊는다. 그래서 이들의 수입은 봉급 하나뿐이고, 봉급의 바닥은 본인 이동 하루소모에
 * 배급 배율을 곱한 값이다({@link Facilities#GUARD_RATION_MULT}) — 근무 직전 손에 들어온다.
 * 지주가 봉급을 못 내면 그대로 굶는다 — 부양력이 정원을 제한한다는 규칙이 실제로 물리게
 * 하는 자리다.
 */
public class MimicWatchGoal extends Goal {

    /** 순찰 경로를 뽑는 시설 둘레 반경 — 이 안의 등기된 집이 곧 경계 구역이다. */
    private static final double WATCH_RADIUS = 48.0;

    /** 한 순찰 지점에 머무는 틱 — 도착한 뒤에만 센다(주둔과 같은 리듬). */
    private static final int STAND_TICKS = 60;

    /** 도착 판정 — 시설처럼 <b>설 수 있는</b> 좌표에 쓴다(주둔과 같은 눈금). */
    private static final double ARRIVE = 2.5;

    /**
     * <b>집 표적의 도착 판정</b> — 순찰 표적은 남의 거처라 좌표가 구조물 안쪽이다.
     *
     * <p>{@link MimicGarrisonGoal} 이 압박 표적에서 이미 겪고 적어 둔 문제다: 2.5 로는 영영
     * 도착이 성립하지 않아 체류 카운터가 안 올라가고, 다음 표적으로 못 넘어가 그 집 앞에
     * 하염없이 서 있게 된다. 실측(경계 무대): goal 은 돌았는데 순찰 지점 0곳이었고 대원이
     * 시설에서 22~30블록 지점에 흩어져 있었다.
     *
     * <p>군인이 쓰는 {@link FarmTicker#PRESSURE_NEAR} 를 그대로 읽는다 — "문 앞에 섰다"가
     * 두 뜻이 되면 안 된다.
     */
    private static final double ARRIVE_HOME = FarmTicker.PRESSURE_NEAR;

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
        post = mob.getPoorhouse();
        if (post == null) {
            return false;
        }
        // <b>경계는 NIGHT 부터다.</b> 저녁은 WANDER → NIGHT → SLEEP 순인데 SLEEP 만 밤으로
        // 보면, NIGHT 에 먼저 켜지는 귀가 goal(같은 순위 4)이 MOVE 를 잡은 뒤라 SLEEP 에
        // 들어와도 끼어들 수 없다. 귀가·취침 쪽에 대원 예외를 뒀지만, 창 자체도 귀가와
        // 같은 시각에 열어 두 goal 이 같은 밤을 보게 한다.
        Schedule.Phase ph = Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime());
        boolean sleep = ph == Schedule.Phase.SLEEP || ph == Schedule.Phase.NIGHT;
        if (sleep != night) {
            // <b>밤이 끝날 때 한 줄 남긴다.</b> setActivity 는 머리 위 라벨만 바꾸고 로그를
            // 남기지 않는다 — 그대로 두면 "경계가 실제로 도는가"를 로그로 판정할 수 없다.
            // 지금까지 야간 순찰이 한 번도 작동한 적 없었다는 사실(MimicGarrisonGoal 주석)이
            // 오래 드러나지 않은 것도 재는 눈이 없었기 때문이다. 하룻밤에 한 줄이라 시끄럽지도
            // 않고, 0 이면 0 이라고 말한다 — 침묵은 진단이 아니다.
            if (night && !sleep && mob.level() instanceof net.minecraft.server.level.ServerLevel) {
                // <b>실행 중인 goal 을 같이 찍는다.</b> 이 로그는 canUse 에서 나오는데 canUse 는
                // goal 이 선택되지 않아도 평가된다 — 단, <b>MOVE 를 쥔 더 높은 순위가 있으면
                // 평가조차 안 된다</b>(GoalSelector 는 깃발을 뺏을 수 있는 goal 만 canUse 를
                // 부른다. 실측 무대 12: 귀가(3)가 밤새 MOVE 를 쥐자 이 줄이 0개). 즉 "로그가
                // 있다 = 순찰이 돌았다"도 아니고, "로그가 없다 = 밤이 안 왔다"도 아니다 —
                // 없으면 더 높은 순위가 밤새 몸을 쥐고 있었다는 뜻이다.
                StringBuilder gs = new StringBuilder();
                mob.goalSelector.getRunningGoals().forEach(w -> {
                    if (gs.length() > 0) {
                        gs.append('+');
                    }
                    gs.append(w.getGoal().getClass().getSimpleName()
                            .replace("Mimic", "").replace("Goal", ""));
                });
                SimEvents.event(mob, "경계", String.format(
                        "밤 근무 끝 — 순찰 지점 %d곳 · 경비대 @%d,%d 에서 %.0f블록 · 실행 goal [%s]%s",
                        visits, post.getX(), post.getZ(),
                        Math.sqrt(mob.blockPosition().distSqr(post)),
                        gs.length() == 0 ? "없음" : gs.toString(),
                        mob.isCritical() ? " · 새벽 위급(소지 고갈)" : ""));
            }
            visits = 0;
            night = sleep;
            spot = null; // 근무가 바뀌면 표적을 새로 고른다
            stand = 0;
            travel = 0;
        }
        // <b>물러나는 조건은 전이 감지 뒤에 본다.</b> 종전에는 이 두 줄이 위에 있어서, 새벽에
        // 위급해진 대원은 밤→낮 전이를 만나기 전에 return 되어 <b>근무 보고가 통째로 사라졌다</b>
        // (실측 런9: 방문 1 이상을 찍고 순찰하던 #2·#34 가 새벽 위급 → "밤 근무 끝" 줄 0개).
        // 침묵을 "안 돌았다"로 읽어 헛다리를 짚었다 — 물러나더라도 보고는 남긴다.
        //
        // 군인이 이긴다: 경비대원 중 능력이 되는 자는 군인으로 승격되는데, 그때 두 goal 이 같은
        // 우선순위(4)에서 같은 guardAnchor 를 두고 다툰다. 무장도 이미 군인 쪽이 이기게 되어
        // 있다(setPauperGear 는 isSoldier 를 제외한다) — 같은 손을 여기서도 들어 준다.
        if (FarmTicker.isSoldier(mob)) {
            return false;
        }
        // 위급이면 물러난다 — 주둔과 같다. 봉급이 끊겨 굶는 상태이므로 경계를 시킬 자리가
        // 아니고, 구걸 goal 이 시설로 데려간다. <b>앵커도 놓는다</b>: 리시는 근무 중엔 대원을
        // 끌지 않지만(MimicLeashGoal.onGuardDuty) 위급이면 다시 끌기 시작하는데, 그때 앵커가
        // 순찰 표적으로 남아 있으면 굶는 자를 남의 집 앞으로 끌고 간다. 집으로 가야 한다.
        if (mob.isCritical()) {
            mob.setGuardAnchor(null);
            return false;
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
        // <b>라벨을 비운다.</b> 라벨을 찍는 goal 은 밭일·경계·주둔뿐이라, 물러난 뒤 귀가·인출·
        // 구걸 중에도 머리 위에 "경계"가 남았다 — 관측자에게 "낮에도 경계 중"으로 보였다
        // (실측: 새벽 위급으로 물러난 대원이 낮 내내 그 라벨로 왕복). 다시 켜지면 start 가 찍는다.
        mob.setActivity(null);
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
        // 밤 표적은 남의 거처(구조물 안쪽), 낮 표적은 시설(설 수 있는 자리) — 반경이 다르다.
        //
        // <b>집 표적은 수평으로 잰다.</b> 표적 Y 는 heightmap(지붕 위)이고 대원은 지면에 서므로
        // 3차원 거리로는 수평 0블록이어도 수직 차이만으로 반경을 넘긴다. 실측(경계 무대 7):
        // 실행 goal [Watch] 로 밤새 걸어 새벽 위치가 @12,0 · @23,0 · @32,0 — 표적 집 좌표
        // 그대로였는데 순찰 지점 0곳. "문 앞에 섰다"는 수평 판정이어야 한다.
        double arrive = night ? ARRIVE_HOME : ARRIVE;
        double dx = mob.getX() - (spot.getX() + 0.5);
        double dz = mob.getZ() - (spot.getZ() + 0.5);
        double d2 = night ? dx * dx + dz * dz : mob.blockPosition().distSqr(spot);
        // <b>밤 근무 중 400틱마다 상태를 남긴다.</b> 새벽의 "순찰 지점 0곳" 한 줄로는 도착
        // 실패인지 길찾기 실패인지 표적 선정 오류인지 가릴 수 없다 — 이미 네 번 헛짚었다.
        // 표적·수평거리·nav 상태·travel·stand 를 같이 찍으면 어느 고리인지 그 자리에서 읽힌다.
        // (% 400 < 2: Mob.serverAiStep 은 goal tick 을 <b>격틱</b>으로 돌리므로 == 0 만 보면
        // 홀수 id 개체는 영영 안 찍힌다.)
        if (night && mob.level() instanceof net.minecraft.server.level.ServerLevel sl
                && SimTime.tick(sl) % 400L < 2L) {
            SimEvents.event(mob, "경계중", String.format(
                    "표적 @%d,%d · 수평 %.1f블록(도착선 %.0f) · nav %s · travel %d · stand %d · 방문 %d",
                    spot.getX(), spot.getZ(), Math.sqrt(d2), arrive,
                    mob.getNavigation().isDone() ? "done" : "진행",
                    travel, stand, visits));
        }
        if (d2 > arrive * arrive) {
            mob.getLookControl().setLookAt(spot.getX() + 0.5, spot.getY() + 1.0, spot.getZ() + 0.5);
            mob.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.0);
            travel++;
            // <b>경로가 없으면 기다리지 말고 다음 집으로.</b> nav 가 done 인데 아직 도착선 밖이면
            // 길찾기가 목표를 못 잡은 것이다(닿을 수 없는 칸·막힌 지형). 종전에는 이 상태로
            // TRAVEL_LIMIT(600틱=30초)을 다 채워, 못 가는 집 하나에 밤의 상당 부분을 버렸다.
            // 40틱만 확인하고 넘긴다 — 잠깐의 재계산 공백을 오판하지 않을 만큼은 준다.
            boolean stuck = mob.getNavigation().isDone() && travel >= 40;
            if (stuck || travel >= TRAVEL_LIMIT) {
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
        return doorstep(sl, home);
    }

    /**
     * <b>집 바깥에서 설 수 있는 칸 — 문간.</b>
     *
     * <p>종전에는 집 앵커 열의 heightmap Y 를 그대로 표적으로 삼았다. 그 Y 는 <b>지붕 꼭대기</b>다.
     * 길찾기는 지붕 위 노드로 가는 경로를 만들지 못해 즉시 종료되고, 대원은 집에서 8~19블록
     * 떨어진 자리에 굳어 선 채 travel 카운터만 올렸다 — 겉보기엔 "밤새 안 움직임"이었다.
     *
     * <p>실측(경계 무대 10): {@code 표적 @22,0 · 수평 8.8블록 · nav done · travel 382 · 방문 1}
     * — nav 가 done 인데 거리가 도착선 밖이라는 것이 경로 실패의 지문이다. 첫 표적만 방문에
     * 잡힌 것은 그 집이 마침 대원의 출발 자리였기 때문이다.
     *
     * <p>그래서 집 <b>둘레 고리</b>(2~5칸)에서 지면 칸을 찾는다: 발밑이 막고, 머리까지 두 칸이
     * 비고, 집 앵커와 높이 차가 크지 않은 칸. 그중 집에 가장 가까운 것이 문간이다. 하나도
     * 없으면 집 좌표를 그대로 돌려준다(그 경우는 도착 실패가 다시 로그에 남는다).
     */
    private BlockPos doorstep(net.minecraft.server.level.ServerLevel sl, BlockPos home) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                int r2 = dx * dx + dz * dz;
                if (r2 < 4 || r2 > 25) {
                    continue; // 집 안(≤1칸)도, 너무 먼 곳(>5칸)도 아니다
                }
                BlockPos c = sl.getHeightmapPos(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        home.offset(dx, 0, dz));
                // <b>지붕은 문간이 아니다.</b> 종전 |Δy| ≤ 4 는 3~4칸 높이 지붕 위 칸을 통과시켰고,
                // 그 칸은 닿을 수 없어 대원이 담 밑에 밤새 섰다(실측 무대 14: 앵커 -20,-2 에
                // 7블록 거리로 8명이 굳음). 집 바닥과 같은 층(+1 계단)까지만 문간으로 본다.
                if (c.getY() > home.getY() + 1 || c.getY() < home.getY() - 3) {
                    continue; // 지붕 위·절벽 아래 — 집과 같은 층이 아니다
                }
                if (!sl.getBlockState(c).isAir() || !sl.getBlockState(c.above()).isAir()) {
                    continue; // 몸이 들어갈 두 칸이 비어야 한다
                }
                if (!sl.getBlockState(c.below()).blocksMotion()) {
                    continue; // 발판이 있어야 한다
                }
                double d = c.distSqr(home);
                if (d < bestD) {
                    bestD = d;
                    best = c;
                }
            }
        }
        return best == null ? home : best;
    }
}
