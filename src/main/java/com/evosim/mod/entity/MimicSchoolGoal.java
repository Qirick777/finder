package com.evosim.mod.entity;

import com.evosim.core.LifeStage;
import com.evosim.core.Schedule;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * <b>등하교</b> goal (P5b) — 등록된 소년이 노동 시간에 학교의 <b>제 자리</b>로 걸어가 머문다.
 *
 * <p>계획서 1.8: "채집·밭일 시간 시작 시 학교로, 종료 시 귀가." 시간이 끝나면 이 goal 이
 * 스스로 물러나고 기존 귀가 goal 이 이어받는다 — 돌아가는 길을 따로 만들지 않는다.
 *
 * <p><b>학교에 못 가는 소년은 기존대로 놀이 goal 이 잡는다</b>(계획서 1.8: "눈으로 구분된다").
 * 그래서 우선순위는 놀이보다 앞, 리시·전투보다 뒤다.
 *
 * <p>자리는 학생마다 다르다({@link FarmTicker#seatOf}). 학교 앵커 하나로 보내면 스무 명이
 * 한 칸에 뭉쳐 밀치기만 한다 — 도면의 독서대 앞 자리를 한 명씩 나눠 주면 저절로 흩어져 앉는다.
 */
public class MimicSchoolGoal extends Goal {

    /**
     * 이 거리 안에 들면 도착 — 자리 한 칸에 정확히 서지 않아도 수업으로 친다.
     *
     * <p><b>1.0(한 칸)이다.</b> 처음에 4.0(두 칸)을 썼는데, 자리는 실내이고 벽 두께가 한 칸이라
     * <b>벽 바깥에 붙어 선 아이가 착석으로 잡힐 수 있었다</b> — "학교에 들어가긴 하는가"를
     * 재는 지표가 정작 안팎을 구분하지 못했다. 한 칸이면 그 칸에 실제로 서야 한다.
     */
    private static final double ARRIVE_SQ = 1.0;

    /** 표적 무진전이 이 틱 지속되면 오늘 등교를 포기한다(막힌 자리에서 하루를 버리지 않게). */
    private static final int STUCK_GIVE_UP = 200;

    private final MimicEntity mob;
    private BlockPos seat;
    private BlockPos lastPos;
    private int stuckTicks;
    private long gaveUpDay = Long.MIN_VALUE;
    private boolean announced;

    public MimicSchoolGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.getIndividual() == null || mob.isFastSettle() || mob.isBuilding()
                || mob.isUnderThreat() || mob.getStage() != LifeStage.BOY) {
            return false;
        }
        if (Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime())
                != Schedule.Phase.WORK) {
            return false;
        }
        long today = SimTime.tick(mob.level()) / 24000L;
        if (today == gaveUpDay) {
            return false; // 오늘은 이미 못 갔다 — 놀이 goal 에 넘긴다
        }
        seat = FarmTicker.seatOf(mob);
        if (seat == null) {
            return idle();
        }
        return true;
    }

    /** 등교 자연 종료 — 출근 앵커 해제 후 비활성(리시 앵커가 거처로 복원). 밭일의 idle 과 같다. */
    private boolean idle() {
        mob.setWorkAnchor(null);
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (mob.getIndividual() == null || mob.isUnderThreat()
                || Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime())
                        != Schedule.Phase.WORK) {
            return false; // 시간이 끝났다 — 귀가는 기존 goal 이 맡는다
        }
        return seat != null && FarmTicker.seatOf(mob) != null;
    }

    @Override
    public void start() {
        lastPos = mob.blockPosition();
        stuckTicks = 0;
        announced = false;
        // <b>리시를 호위자로 바꾼다.</b> 활동반경 리시(우선순위 2)는 앵커에서 활동반경
        // ({@link com.evosim.core.Roaming#BASE_RADIUS} 32 · 애향 16)을 벗어나면 시간대와 무관하게
        // 끌고 돌아온다. 등교는 우선순위 6 이라 언제나 진다 — 학교가 32블록 밖이면 아이가
        // "출발 → 강제귀환"을 반복하며 영영 못 닿는다. 붙잡힌 게 아니라 <b>움직이는 중</b>이라
        // 무진전 포기에도 안 걸려, 착석도 포기도 없는 채로 하루가 지난다.
        //
        // 실측이 이것과 맞는다: 착석한 아이의 최대 거리 31 · 도착 못 한 아이의 최소 거리 37 로
        // 경계가 32 를 사이에 두고 갈렸고, 이번 런에서 실제 착석은 22블록 하나뿐이었으며
        // 46블록 등록자는 착석 0 · 포기 0 이었다. 길찾기는 56블록까지 닿는다(navprobe).
        //
        // <b>밭일이 이미 같은 결함을 겪고 같은 방식으로 고쳤다</b>({@link MimicEntity#roamAnchor}
        // 의 workAnchor 주석: "통근(≤48) 밭이 활동반경 밖이면 리시가 밭일을 선점해 무한 줄다리기").
        // 등교도 통근 한계가 48 인데 앵커를 세우지 않아 그 수정에서 빠져 있었다.
        //
        // workAnchor 를 쓰는 이유: 노동 시간대에만 유효해서 수업이 끝나면 저절로 풀린다 —
        // 남은 앵커가 밤 귀가를 학교로 끌지 않는다. 밭일 goal 은 BOY 를 제외하므로 충돌하지 않는다.
        mob.setWorkAnchor(seat);
    }

    @Override
    public void stop() {
        // <b>여기서 workAnchor 를 지우면 안 된다.</b> 리시(2)가 등교를 인수하는 순간 이 goal 이
        // 선점 정지되며 stop 이 불리는데, 그때 앵커를 지우면 앵커가 거처로 돌아가 리시가 아이를
        // 집으로 되끌고, 그러면 다시 이 goal 이 서고… 자기파괴 루프가 된다. 밭일 goal 의 stop 에
        // 같은 경고가 붙어 있는데(실측: act=복귀 진동) 내가 그대로 밟았다 — 리시를 호위자로
        // 바꾸려던 수정이, 호위가 시작되는 바로 그 순간 앵커를 걷어차고 있었다.
        //
        // 해제는 자연 종료 지점({@link #idle} — 등록이 풀린 날)·포기·노동시간 종료
        // ({@link MimicEntity#roamAnchor} 의 WORK 게이트)가 맡는다.
        seat = null;
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return false;
    }

    @Override
    public void tick() {
        if (seat == null) {
            return;
        }
        double d = mob.blockPosition().distSqr(seat);
        if (d <= ARRIVE_SQ) {
            // 도착 — 자리에서 앞을 본다. 여기 머무는 것 자체가 수업이다(출석은 새벽에
            // 이미 장부로 확정됐고, 이 goal 은 그 장부를 눈에 보이게 하는 연출이다).
            mob.getNavigation().stop();
            mob.getLookControl().setLookAt(seat.getX() + 0.5, seat.getY() + 1.0,
                    seat.getZ() + 0.5);
            mob.creditSchoolDay(SimTime.tick(mob.level()) / 24000L); // 하루 한 번만
            if (!announced) {
                announced = true;
                com.evosim.mod.log.SimEvents.event(mob, "등교", String.format(
                        "착석 @%d,%d (집에서 %.0f블록)", seat.getX(), seat.getZ(),
                        mob.getHomePos() == null ? -1.0
                                : Math.sqrt(mob.getHomePos().distSqr(seat))));
            }
            stuckTicks = 0;
            return;
        }
        // 앵커를 <b>매 틱 다시 세운다</b>(밭일과 같다) — 다른 goal 이 중간에 지우고 가더라도
        // 등교가 도는 동안에는 리시가 학교 쪽을 보게 유지된다.
        mob.setWorkAnchor(seat);
        if (mob.getNavigation().isDone()) {
            // <b>정확도 0</b> — 자리 칸에 실제로 올라서게 한다.
            //
            // {@code moveTo(x, y, z, 속도)} 는 내부에서 정확도 1 로 경로를 만든다("목표에서 한 칸
            // 이내면 도착"). 그래서 길찾기는 <b>대각선 이웃</b>에서 스스로 끝났다고 놓아 버리는데,
            // 착석 판정은 {@link #ARRIVE_SQ} 1.0 이라 직교 이웃(거리제곱 1)만 인정하고 대각선(2)은
            // 탈락이다. 두 기준이 어긋나 아이는 길찾기가 놓아준 칸에 서고 goal 은 계속 기다리다
            // 200틱 뒤 포기했다 — 그러고도 길이 막힌 것이 아니라 <b>움직이지 않은 것</b>이라
            // 원인이 안 보였다.
            //
            // 계측이 이걸 한 줄로 갈랐다: "내y1.0 자리y1 · <b>경로도달가능</b> · <b>네비끝남</b>"
            // — 높이도 맞고 길도 있는데 길찾기만 끝나 있었다. 같은 자리가 날에 따라 되기도 안
            // 되기도 한 것은 접근 방향에 따라 경로 종점이 직교냐 대각이냐로 갈렸기 때문이다.
            //
            // 판정을 2.0 으로 <b>느슨하게 풀지 않는다</b> — 그 눈금은 벽 바깥에 붙어 선 아이를
            // 착석으로 세던 값(4.0)을 좁히며 정한 것이라, 되돌리면 "학교에 들어갔는가"를 재는
            // 지표가 다시 안팎을 흐린다. 대신 아이를 제 칸까지 보낸다.
            var path = mob.getNavigation().createPath(seat, 0);
            if (path != null) {
                mob.getNavigation().moveTo(path, 1.0);
            } else {
                // 그 칸으로 길이 안 나면(누가 서 있거나 막혔거나) 종전대로 근처까지라도 간다 —
                // 무진전 감시가 받아 포기 사건으로 남기고, 그 사건이 사유를 말한다.
                mob.getNavigation().moveTo(seat.getX() + 0.5, seat.getY(), seat.getZ() + 0.5, 1.0);
            }
        }
        // 무진전 감시 — 길이 막혔는데 하루 종일 벽에 붙어 있으면 그 아이는 굶지도 놀지도 못한다.
        BlockPos now = mob.blockPosition();
        if (now.equals(lastPos)) {
            if (++stuckTicks >= STUCK_GIVE_UP) {
                gaveUpDay = SimTime.tick(mob.level()) / 24000L;
                // <b>왜</b> 못 갔는지까지 남긴다 — "1블록 앞에서 멈췄다"만으로는 자리가 막힌
                // 것인지, 길이 안 나는 것인지, 높이가 어긋난 것인지 구분할 수 없다. 이 세션에서
                // 원인을 추측했다가 틀린 적이 여러 번이라, 포기 사건이 스스로 사유를 말하게 한다.
                var path = mob.getNavigation().createPath(seat, 0);
                com.evosim.mod.log.SimEvents.event(mob, "등교", String.format(
                        "포기 — %d틱 무진전 (자리 @%d,%d 까지 %.0f블록) · 내y%.1f 자리y%d"
                                + " · 경로%s · 네비%s",
                        stuckTicks, seat.getX(), seat.getZ(), Math.sqrt(d),
                        mob.getY(), seat.getY(),
                        path == null ? "없음" : (path.canReach() ? "도달가능" : "부분"),
                        mob.getNavigation().isDone() ? "끝남" : "진행중"));
                seat = null;
                mob.setWorkAnchor(null); // 포기했으면 리시도 놓아준다 — 집으로 돌아갈 수 있게
            }
        } else {
            lastPos = now;
            stuckTicks = 0;
        }
    }
}
