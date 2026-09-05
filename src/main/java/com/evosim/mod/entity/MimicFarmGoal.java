package com.evosim.mod.entity;

import com.evosim.core.FarmEconomy;
import com.evosim.core.FoodEconomy;
import com.evosim.core.LifeStage;
import com.evosim.core.Schedule;
import com.evosim.mod.log.SimEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;

import java.util.EnumSet;

/**
 * 자기 밭 수확 goal (M1 — 자영만, 소작 배정은 M2). 노동 시간에 소유 구획의 익은 타일을 순회
 * 수확 — 하루 용량 C(FarmEconomy.capacity)까지만(전담창 추상화). 수확 시 익음 타이머 리셋.
 * 우선순위는 채집(Forage)보다 앞 — 틱당 수익 우위(밭 0.0037 > 들풀 0.0011)를 행동으로 반영.
 */
public class MimicFarmGoal extends Goal {

    private static final int HARVEST_COOLDOWN = 100; // 기존 채집과 동일 리듬

    private final MimicEntity mob;
    private int cooldown;
    private int harvestedToday;
    private long day = -1;
    private BlockPos target;
    private int stuckTicks;

    /** 지금 진전을 재고 있는 표적 — 바뀌면 최근접 기록을 새로 시작한다. */
    private BlockPos progressTarget;

    /** 그 표적까지 <b>지금까지 가장 가까이</b> 갔던 거리². */
    private double bestDistSqr = Double.MAX_VALUE;

    /**
     * 표적에 더 가까워지지 못한 채 이 틱이 지나면 도달 불가로 보고 배정을 반납한다.
     *
     * <p>종전 60틱은 <b>좌표가 정확히 같은</b> 경우에만 세는 값이라 짧아도 됐다. 이제는 왕복도
     * 잡는데, 길이 크게 돌아가는 정상 통근이 여기 걸리면 멀쩡한 출근을 끊는다 — 넉넉히 준다.
     */
    private static final int NO_PROGRESS_DROP_TICKS = 200;

    public MimicFarmGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.getIndividual() == null || mob.isFastSettle() || mob.isBuilding()
                || mob.getStage() == LifeStage.INFANT || mob.getStage() == LifeStage.BOY) {
            return false;
        }
        // 위급 배정자는 시간표를 무시한다 — MimicForageGoal 이 위급 때 배회·밤을 무시하고 채집을
        // 강행하는 것과 같은 예외. 낮에 위급해진 무밭 성년은 FarmTicker.emergencyHire 가 그 자리에서
        // 배정하는데, 노동 시간이 이미 지났으면 그 배정이 다음 날까지 아무 소용이 없다(그 사이 아사).
        boolean urgent = mob.isCritical() && FarmTicker.assignedPlot(mob.getId()) != 0L;
        if (!urgent
                && Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime())
                        != Schedule.Phase.WORK) {
            return idle();
        }
        long today = com.evosim.mod.entity.SimTime.tick(mob.level()) / 24000L;
        if (today != day) {
            day = today;
            harvestedToday = 0; // 일일 용량 리셋
        }
        // ── 수확 문턱과 노동 문턱은 다르다 ────────────────────────────────────────────
        // 아래 둘은 <b>수확만</b> 막는다. 종전에는 여기서 곧장 idle() 로 빠져, 익은 타일이
        // 널린 밭 한복판에 선 채로 남은 근무시간을 통째로 버렸다 — 실측(관리무대, 54타일
        // 전부 익음): "수전 파커 코앞(7,-2)에 익은 타일이 있는데 안 딴다 — 사유: 하루 수확
        // 용량 소진(8/8)". 육안으로 본 "다 익었는데 못 따고 멍때린다"가 바로 이것이다.
        //
        // 용량은 <b>딸 수 있는 양</b>의 상한이지 <b>일할 수 있는 시간</b>의 상한이 아니다.
        // 더 못 딴다는 것은 그 사람에게 "딸 게 없다"와 같으므로, 지시 사양대로 관리로 넘긴다
        // ("출근하면 수확 + 수확할 게 없어도 놀지 않고 작물관리"). 관리는 수확량·소득을
        // 만들지 않으므로 용량·쿼터가 지키려는 것(수취 상한·노년 지원 누수)은 그대로 지켜진다.
        boolean harvestBlocked = false;
        if (harvestedToday >= FarmEconomy.capacity(mob.getIndividual(), mob.getStage())) {
            harvestBlocked = true; // 전담창 소진 — 수확은 끝, 관리는 가능
        }
        if (!urgent && mob.getStage() == LifeStage.ELDER && mob.elderQuotaMet()) {
            // 노년 노동의 단일 상한 = 쿼터(노년 확장 산출 ㉵) — 밭 수확도 addHarvest 로 dayGathered 에
            // 누적되므로 여기서 막지 않으면 용량(6타일=4.5/일)까지 뚫려 자식 지원 누수가 재발한다.
            // 잔여 익은 타일은 부족분 게시 → 소작(2세대 일자리)으로 자연 이관.
            harvestBlocked = true;
        }
        if (mob.isSatisfiedToday() && FarmTicker.assignedPlot(mob.getId()) == 0L) {
            // 만족(M7)은 <b>출근 자체를 안 하는</b> 것이라 관리로도 넘기지 않는다 — 자기 밭
            // 노동 정지가 사다리 분화의 장치이고, 여기를 열면 만족한 지주가 계속 일하게 된다.
            idleWhy("만족 상태이고 배정 없음");
            return idle(); // 소작 출근(배정)은 계약 의무라 유지
        }
        target = harvestBlocked ? null : nearestWorkRipe();
        if (target != null) {
            mob.setFarmHasNoWork(false);
            tendTarget = null; // 익은 게 있으면 언제나 수확이 먼저
            mob.setActivity("수확 " + harvestedToday + "/"
                    + FarmEconomy.capacity(mob.getIndividual(), mob.getStage()));
            return true;
        }
        // 사유는 <b>여기서 적어만 두고</b>, 실제로 노는 경로에 닿았을 때만 내보낸다. 곧장
        // 찍었더니 관리하러 가는 개체까지 "익은 걸 안 딴다"로 잡혀 47건이 쏟아졌다 — 일하는
        // 중인데 결함으로 세는 거짓 양성이다.
        String why = harvestBlocked
                ? (harvestedToday >= FarmEconomy.capacity(mob.getIndividual(), mob.getStage())
                        ? "하루 수확 용량 소진(" + harvestedToday + "/"
                                + FarmEconomy.capacity(mob.getIndividual(), mob.getStage()) + ")"
                        : "노년 쿼터 소진")
                : "익은 표적을 못 찾음(돌봄 반경 밖이거나 남의 밭)";
        if (harvestBlocked && !tendAfterCap) {
            idleWhy("수확 상한(용량·쿼터) 소진 — 관리 이관 꺼짐");
            return idle(); // A/B 의 종전 거동
        }
        // ── 딸 게 없으면 놀지 않고 <b>밭을 돌본다</b> ──────────────────────────────────
        // 종전에는 여기서 앵커만 남기고 비활성이 되어, 출근한 소작이 하루의 남은 시간을 버렸다
        // (실측 가동률 72% — 용량 8 중 5.8타일만 땀). 그 시간을 관리에 쓰면 익음이 빨라져
        // 다음 날의 공급이 는다. 수확 용량은 소모하지 않는다 — 어차피 비던 시간이다.
        tendPlot = tendablePlot();
        if (tendPlot != 0L) {
            // 표적은 <b>도착해서 다 돌본 뒤에만</b> 바꾼다(tendTick 이 비운다). 걷는 도중에
            // 다시 고르면 목표가 계속 바뀌어 제자리 움찔이 된다.
            if (tendTarget == null) {
                tendTarget = pickTendTile(tendPlot);
                tendStay = 0;
            }
            if (tendTarget != null) {
                mob.setFarmHasNoWork(false);
                mob.setActivity("관리중");
                tendWhy(why);
                return true;
            }
        }
        // 표적이 <b>지금</b> 없을 뿐 아직 배정된 노동일이 남았다면 출근 앵커를 유지한 채 비활성만
        // 된다. 여기서 idle()로 앵커를 지우면 우선순위 2인 리시(MimicLeashGoal)가 즉시 거처로
        // 되끌고, 다음 틱에 타일 하나가 익으면 이 goal 이 다시 켜져 밭으로 보낸다 —
        // 출근→밭일→배회(리시 복귀)→출근 의 무한 왕복이 되고, 그 사이 아무것도 못 먹는다
        // (실측: 위기 상태로 이 순환을 반복하다 사망). 앵커를 남기면 밭 근처에 머물며
        // 채집(우선순위 7)으로 시간을 쓰고, 타일이 익는 즉시 그 자리에서 수확한다.
        if (harvestBlocked) {
            // <b>오늘은 여기서 할 일이 없다</b> — 앵커를 놓고 물러난다. 위 주석의 왕복 우려는
            // "잠시 딸 게 없을 뿐"인 경우의 이야기다: 용량을 채운 뒤에는 타일이 익어도 못 따므로
            // 되돌아올 일이 없어 진동하지 않는다. 앵커를 쥔 채 서 있으면 육안으로 본 그 장면이
            // 된다 — "밭에 익은 덤불이 있는데 안 따고 그냥 밭 중앙에 머무른다". 관리도 못 하는
            // 이유는 관리가 <b>안 익은 타일</b>만 대상이기 때문이다(익은 것은 더 익힐 게 없다).
            // 남은 타일이 전부 익은 밭에서는 관리 자리가 아예 없다.
            mob.setActivity("퇴근(" + why + ")");
            idleWhy(why + " · 관리할 안 익은 타일도 없음 — 오늘 노동 종료");
            mob.setFarmHasNoWork(true); // 채집 금지(농사 집중)를 풀어 준다
            return idle();
        }
        if (FarmTicker.assignedPlot(mob.getId()) != 0L) {
            mob.setActivity("대기(딸 것 없음)");
            idleWhy(why + " · 관리 자리도 없음");
            mob.setFarmHasNoWork(true); // 채집 금지(농사 집중)를 풀어 준다
            return false; // 앵커 유지 — 하루 노동이 끝난 게 아니라 잠시 딸 게 없을 뿐
        }
        idleWhy(why + " · 배정도 소유도 없음");
        mob.setFarmHasNoWork(true);
        return idle();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void stop() {
        // 주의: 여기서 workAnchor 를 지우면 안 된다 — 리시(2)가 출근을 인수하는 순간 이 goal 이
        // 선점 정지되며 stop 이 불리는데, 그때 앵커를 지우면 리시가 거처로 되끌어 자기파괴
        // 루프가 된다(실측: act=복귀 진동). 해제는 자연 종료 지점(canUse 의 idle 경로)·입금
        // 귀가(MimicReturnGoal.start)·노동시간 종료(roamAnchor 의 WORK 게이트)가 맡는다.
        target = null;
    }

    /** 밭일 자연 종료 — 출근 앵커 해제 후 비활성(리시 앵커가 거처로 복원). */
    private boolean idle() {
        mob.setWorkAnchor(null);
        mob.setActivity(""); // 밭일이 끝났으니 표시는 다른 goal 에 넘긴다
        return false;
    }

    private long lastTendWhyTick = -9999L;

    /**
     * <b>수확할 게 있는데 관리를 한다</b> — 그 장면을 잡아 사유를 적는다.
     *
     * <p>손질을 시작하는 순간 주변에 익은 타일이 있는지 본다. 있으면 그것이 <b>어느 구획</b>의
     * 것이고 <b>왜 못 따는지</b>를 남긴다. 지금 손질하는 밭에는 익은 게 없어야만 여기까지 오므로
     * (그 조건은 pickTendTile 이 막는다), 걸린다면 <b>남의 밭</b>이거나 <b>내 밭이지만 못 따는
     * 사정</b>(용량 소진·돌봄 반경 밖)이다. 둘은 대응이 완전히 다르므로 추측하지 않는다.
     */
    private void tendWhy(String why) {
        if (!(mob.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return;
        }
        long now = com.evosim.mod.entity.SimTime.tick(sl);
        if (now - lastTendWhyTick < 200L) {
            return;
        }
        lastTendWhyTick = now;
        BlockPos me = mob.blockPosition();
        long assigned = FarmTicker.assignedPlot(mob.getId());
        long id = mob.getIndividual().id();
        for (FarmStore.Plot p : FarmStore.get(sl).all().values()) {
            if (p.anchor != null && me.distSqr(p.anchor) > 4096.0) {
                continue;
            }
            for (long l : p.ripe) {
                BlockPos pos = BlockPos.of(l);
                if (me.distSqr(pos) > 64.0) {
                    continue; // 8블록 안 — 눈에 "코앞"으로 보이는 범위
                }
                boolean minePlot = p.ownerId == id || mob.marriedTo(p.ownerId)
                        || p.id == assigned;
                SimEvents.event(mob, "관리멍", String.format(
                        "손질 중인데 %.0f블록 앞(%d,%d)에 익은 타일 — 구획%d(%s) · 수확 못 하는 사유: %s",
                        Math.sqrt(me.distSqr(pos)), pos.getX(), pos.getZ(), p.id,
                        minePlot ? "내 밭/배정" : "남의 밭", minePlot ? why : "권한 없음"));
                return;
            }
        }
    }

    private long lastWhyTick = -9999L;

    /**
     * <b>코앞에 익은 게 있는데 안 딴다</b> — 그 사유를 로그로 뱉는다.
     *
     * <p>문지기 조건이 여럿이라(용량 소진·노년 쿼터·만족·근무시간·돌봄 반경·남의 밭) 겉으로는
     * 전부 "멍때림"으로 보인다. 육안으로 본 그 장면이 어느 조건인지 추측하지 않으려면 코드가
     * 직접 말해야 한다. 3블록 안에 익은 타일이 있을 때만, 개체당 200틱에 한 번만 남긴다.
     */
    private boolean idleWhy(String reason) {
        if (!(mob.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return false;
        }
        long now = com.evosim.mod.entity.SimTime.tick(sl);
        if (now - lastWhyTick < 200L) {
            return false;
        }
        // <b>훑기 자체</b>를 200틱에 한 번으로 묶는다. 이벤트가 나갈 때만 갱신하면, 근처에 익은
        // 게 없는 흔한 경우에 매 틱 전 구획 × 전 타일을 훑게 된다 — 노는 개체가 많을수록,
        // 밭이 늘수록 비용이 커져 진단기가 런을 느리게 만든다.
        lastWhyTick = now;
        BlockPos me = mob.blockPosition();
        for (FarmStore.Plot p : FarmStore.get(sl).all().values()) {
            if (p.anchor != null && me.distSqr(p.anchor) > 4096.0) {
                continue; // 64블록 밖 구획 — 3블록 판정에 걸릴 리 없다
            }
            for (long l : p.tiles) {
                BlockPos pos = BlockPos.of(l);
                if (me.distSqr(pos) > 9.0 || !sl.isLoaded(pos)) {
                    continue;
                }
                var st = sl.getBlockState(pos);
                if (st.is(Blocks.SWEET_BERRY_BUSH)
                        && st.getValue(SweetBerryBushBlock.AGE) >= 3) {
                    SimEvents.event(mob, "밭멍", String.format(
                            "코앞(%d,%d)에 익은 타일이 있는데 안 딴다 — 사유: %s · 구획%d",
                            pos.getX(), pos.getZ(), reason, p.id));
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 수확 상한을 채운 뒤 관리로 넘길 것인가 — A/B 스위치. 끄면 종전 거동(그 자리에서 논다).
     *
     * <p>켜면 커버리지가 오르고 익음이 빨라져 <b>생산이 는다</b>. 지시 3번("시간이 쌓이면서
     * 소작농이 역전하거나 너무 부유해져서는 안 된다")에 걸리는 변경이라 껐다 켜며 재야 한다.
     */
    private static boolean tendAfterCap = true;

    public static void setTendAfterCap(boolean on) {
        tendAfterCap = on;
    }

    public static boolean tendAfterCap() {
        return tendAfterCap;
    }

    /** 한 관리 자리에 <b>도착한 뒤</b> 머무는 틱 — 여기까지 채우면 다음 자리로 옮긴다(≈3초). */
    private static final int TEND_STAY_TICKS = 60;

    /** 커서에서 이 개수까지 훑어 다음 칸을 찾는다 — 전부 멀면 가까운 칸으로 붙는다. */
    private static final int TEND_SCAN_SPAN = 24;

    /** 다음 칸이 이보다 멀면 건너뛴다(16블록²) — 밭을 가로질러 뛰는 그림 방지. */
    private static final double TEND_FAR_SQR = 256.0;

    /** 관리 자리에 도착해 머문 틱. */
    private int tendStay;

    /** 안 익은 목록에서 지금 도는 위치(-1 = 아직 시작 안 함 — 개체별 시작점을 잡는다). */
    private int tendCursor = -1;

    private BlockPos tendTarget;
    private long tendPlot;

    /**
     * 돌볼 구획 — 배정된 소작 밭이 먼저고, 없으면 <b>제 밭</b>(배우자 명의 포함)이나 맡은 마름
     * 구획이다.
     *
     * <p>배정만 보면 <b>지주가 제 밭을 못 돌본다</b>. 실측(caretest): 딸 것도 없고 관리도 못 해
     * 집 근처에서 목표만 바꾸며 떠는 개체로 잡혔다(움찔 2명 중 하나가 지주 본인). 돌봄은
     * 고용 관계가 아니라 그 밭에 이해가 걸린 사람의 일이다.
     */
    private long tendablePlot() {
        long assigned = FarmTicker.assignedPlot(mob.getId());
        if (assigned != 0L) {
            return assigned;
        }
        if (!(mob.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return 0L;
        }
        FarmStore fs = FarmStore.get(sl);
        long id = mob.getIndividual().id();
        long mine = fs.newestOwnedPlot(id);
        if (mine != 0L) {
            return mine;
        }
        long steward = fs.stewardOf(id);
        if (steward != 0L) {
            return steward;
        }
        for (FarmStore.Plot p : fs.all().values()) {
            if (p.ownerId != 0L && mob.marriedTo(p.ownerId)) {
                return p.id; // 배우자 명의 밭 — 가구 노동은 수확과 같은 기준(양방향 혼인)
            }
        }
        return 0L;
    }

    /**
     * 관리할 타일 고르기 — 배정 구획의 <b>아직 안 익은</b> 칸 중 가까운 쪽. 익은 칸은 수확이
     * 가져가므로 여기서는 제외한다(같은 칸을 두고 두 행동이 서로 뺏으면 움찔거린다).
     */
    private BlockPos pickTendTile(long plotId) {
        if (!(mob.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return null;
        }
        FarmStore.Plot p = FarmStore.get(sl).get(plotId);
        if (p == null || p.tiles.length == 0) {
            return null;
        }
        // <b>만석인 밭에는 끼어들지 않는다.</b> 커버리지는 1.0 에서 잘리므로 더 붙어도 산출이
        // 늘지 않고, 좁은 밭에서는 서로 부대껴 끼임만 만든다(육안 관측: 6타일 밭에 5명 —
        // 케어범위 24 라 한 명이면 이미 100%). 나를 뺀 커버리지가 이미 1.0 이면 물러난다.
        // 주인(과 배우자)은 예외다. 이 규칙은 <b>좁은 밭에 소작들이 서로 부대끼는 것</b>을
        // 막으려는 것인데, 12타일짜리 밭은 소작 한 명의 케어 반경만으로 이미 100% 덮여
        // 주인이 제 밭을 돌볼 자리가 없다고 판정된다. 그러면 주인은 관리 대신 채집으로
        // 빠져 제 밭을 떠난다(육안 관측: "지주가 관리는 안 하고 채집하고 돌아다닌다").
        //
        // 산출이 늘지 않는 것은 맞다(커버리지는 1.0 에서 잘린다). 그러나 주인이 제 밭을
        // 돌보는 그림이 맞고, 어차피 그 시간은 채집으로 쓰든 관리로 쓰든 밭의 산출에는
        // 같다 — 끼임 우려도 한 명 더 서는 정도라 소작 여럿이 겹치는 것과 다르다.
        boolean ownWork = p.ownerId == mob.getIndividual().id() || mob.marriedTo(p.ownerId);
        if (!ownWork && FarmTicker.careOf(sl, p, mob.getId())[1] >= 1.0) {
            return null;
        }
        // <b>익은 게 남은 밭은 손질할 때가 아니다.</b>
        //
        // 수확 용량을 채운 소작을 놀리지 않으려고 관리로 넘겼는데, 그 결과가 익은 밭 한복판에서
        // 손질하는 그림이었다(육안 관측: "다 자라도 안 따고 관리를 하고 있는데?"). 사람 눈으로
        // 이상한 게 맞고, 경제로 봐도 이상하다 — 이미 익은 것을 못 거두는 밭에 익음을 더 빨리
        // 돌려 봐야 쓸 데가 없다. 손질은 <b>다 딴 밭</b>에 하는 일이다.
        //
        // 이 밭에 익은 게 남았으면 물러난다. 그 소작은 오늘 할 일이 끝난 것이고, 위 호출부가
        // 앵커를 놓고 귀가시킨다.
        if (p.ripe.length > 0) {
            return null;
        }
        // <b>안 익은 목록을 커서로 순회한다.</b>
        //
        // 종전에는 매번 "가장 가까운" 안 익은 타일을 골랐다. 이미 그 앞에 서 있으므로 다시
        // 골라도 같은 타일이 나오고, 미믹은 제자리에서 팔만 휘두른다(육안 관측). 무작위로
        // 바꿔 봤지만 그것은 왔다 갔다 할 뿐 <b>순회하는 느낌</b>이 아니다.
        //
        // 목록 순서는 밭을 깐 순서(줄 단위)라, 그대로 돌면 <b>이랑을 따라 걸어가는</b> 그림이
        // 된다. 시작점을 개체마다 다르게 주어(id 기준) 여럿이 붙어도 서로 다른 구역에서
        // 시작해 각자 제 쪽을 돈다 — 줄줄이 따라다니지 않는다.
        long[] list = p.unripe;
        if (list.length == 0) {
            return null; // 전부 익었다 — 돌볼 것이 없다(그러면 밭일은 물러난다)
        }
        BlockPos me = mob.blockPosition();
        if (tendCursor < 0) {
            tendCursor = (int) Math.floorMod(mob.getIndividual().id(), list.length);
        }
        int span = Math.min(list.length, TEND_SCAN_SPAN);
        for (int k = 0; k < span; k++) {
            int idx = Math.floorMod(tendCursor + k, list.length);
            BlockPos t = BlockPos.of(list[idx]);
            if (t.equals(tendTarget)) {
                continue; // 방금 돌본 자리는 건너뛴다
            }
            if (me.distSqr(t) > TEND_FAR_SQR) {
                continue; // 너무 멀다 — 밭을 가로질러 뛰지 않는다. 가까운 칸이 먼저 걸린다
            }
            // <b>고른 칸은 그 자리에서 눈으로 확인한다.</b> 목록은 200틱(10초)마다 갱신되므로
            // 그 사이 익은 덤불은 아직 안 익은 목록에 남아 있다 — 열매가 달린 칸을 손질하는
            // 장면이 딱 그 창에서 나온다. 어긋남을 보면 목록을 즉시 다시 만들고 이번 틱은
            // 물러난다(다음 틱이 올바른 목록으로 고른다).
            var st = sl.getBlockState(t);
            if (!st.is(Blocks.SWEET_BERRY_BUSH)
                    || st.getValue(SweetBerryBushBlock.AGE) >= 3) {
                FarmTicker.refreshLists(sl, p);
                return null;
            }
            tendCursor = idx + 1;
            return t;
        }
        // 반경 안에 다음 칸이 없다 — 커서를 그대로 두고 목록에서 가장 가까운 칸으로 붙는다
        // (밭 밖에서 막 출근한 경우가 이쪽이다).
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (long l : list) {
            BlockPos t = BlockPos.of(l);
            var st = sl.getBlockState(t);
            if (!st.is(Blocks.SWEET_BERRY_BUSH) || st.getValue(SweetBerryBushBlock.AGE) >= 3) {
                continue; // 목록이 낡았다 — 열매 달린 칸은 손질 대상이 아니다
            }
            double d = me.distSqr(t);
            if (d < bd) {
                bd = d;
                best = t;
            }
        }
        return best;
    }

    /**
     * 관리 행동 — 자리로 가서 팔을 휘두르고, 관리 중임을 알린다. 알린 만큼만 익음이 빨라진다
     * ({@link FarmTicker#reportTending}). 자리에 도착하지 못했으면 알리지 않는다 — 걸어가는
     * 중에 가속이 붙으면 "밭 근처에 있기만 해도 자란다"가 되어 연출과 수치가 어긋난다.
     */
    private void tendTick() {
        if (tendTarget == null || !(mob.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return;
        }
        mob.setWorkAnchor(tendTarget); // 리시가 거처로 되끌지 않게 — 수확 경로와 같은 이유
        if (tendPlot == 0L) {
            tendTarget = null;
            return;
        }
        // 손질하는 3초 사이에 그 칸이 익을 수 있다 — 익었으면 즉시 놓는다. 안 그러면 열매가
        // 달린 채로 계속 손질하는 그림이 남는다.
        var cur = sl.getBlockState(tendTarget);
        if (!cur.is(Blocks.SWEET_BERRY_BUSH) || cur.getValue(SweetBerryBushBlock.AGE) >= 3) {
            tendTarget = null;
            tendStay = 0;
            return;
        }
        // <b>걸어가는 동안에도 관리 중으로 친다.</b> 도착했을 때만 보고하면, 자리를 옮겨
        // 다니게 만든 순간 커버리지가 걸음마다 끊겨 익음 배속이 널뛴다. 밭 안에서 다음
        // 이랑으로 가는 걸음도 일이다 — 다만 집에서 출근하는 길까지 세지 않도록 표적
        // 8블록 안일 때만 인정한다.
        if (mob.blockPosition().closerThan(tendTarget, 8.0)) {
            FarmTicker.reportTending(mob.getId(), tendPlot,
                    com.evosim.mod.entity.SimTime.tick(sl),
                    FarmEconomy.careRange(mob.getIndividual()));
        }
        if (!mob.blockPosition().closerThan(tendTarget, 2.5)) {
            mob.getNavigation().moveTo(tendTarget.getX() + 0.5, tendTarget.getY(),
                    tendTarget.getZ() + 0.5, 1.0);
            return;
        }
        // 도착 — 잠깐 손질하고(팔 휘두르기) 다음 이랑으로 옮긴다. 이 "머물다 옮기기"가
        // 지시 사양의 <b>밭 돌아다니며 관리하는 연출</b>이다.
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(tendTarget.getX() + 0.5, tendTarget.getY() + 0.5,
                tendTarget.getZ() + 0.5); // 손질하는 칸을 내려다본다
        if (mob.tickCount % 10 == 0) {
            mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            // 지금 <b>어느 칸을</b> 만지고 있는지 멀리서도 보이게. 팔 동작만으로는 무엇을 하는
            // 중인지 분간이 안 된다는 것이 육안 관측이었다.
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                    tendTarget.getX() + 0.5, tendTarget.getY() + 0.6, tendTarget.getZ() + 0.5,
                    3, 0.25, 0.2, 0.25, 0.0);
        }
        if (++tendStay >= TEND_STAY_TICKS) {
            tendTarget = null; // 다음 자리는 canUse 가 고른다
            tendStay = 0;
        }
    }

    @Override
    public void tick() {
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (target == null) {
            tendTick();
            return;
        }
        // 출근 앵커(F1) — 표적 보유 동안 리시 앵커를 작업 타일로: 활동반경(기본 32·애향 16) 밖
        // 통근 밭(≤48)에서 리시가 밭일을 선점해 출발↔강제귀환 줄다리기로 재배가 막히던 결함 수정.
        mob.setWorkAnchor(target);
        if (!mob.blockPosition().closerThan(target, 1.9)) {
            mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
            // 무진전 탈출(ForageGoal 의 R-5 스냅과 같은 장치) — 긴급 고용은 거리 무제한이라
            // 길이 끊긴 밭에 배정될 수 있다. 그러면 이 goal 이 우선순위 6으로 채집(7)을 선점한 채
            // 제자리에 서서 굶어 죽는다(구제하려던 개체를 더 빨리 죽이는 역효과). 일정 틱 제자리면
            // 배정을 반납해 그날은 다른 밭·채집으로 돌아가게 한다.
            // <b>가까워지고 있는가</b>로 본다 — 좌표가 같은지가 아니라.
            //
            // 종전에는 blockPosition 이 <b>정확히 같을 때만</b> 무진전으로 셌다. 그러면 제자리에
            // 얼어붙은 개체는 잡지만, <b>왔다 갔다 하는</b> 개체는 좌표가 매번 달라 영영 안
            // 걸린다. 육안 관측이 그 경우다: 위급해진 개체가 먼 밭으로 배정받으면, 가는 도중
            // 소지가 떨어져 귀가(우선순위 3)가 밭일(6)을 선점해 집으로 끌고 오고, 인출하면 다시
            // 밭으로 나서기를 반복한다 — 왕복 거리가 지고 갈 수 있는 식량보다 길어 <b>영원히
            // 도착하지 못한다</b>. 그동안 배정을 쥐고 있으니 다른 일도 못 한다.
            //
            // 지금까지 <b>가장 가까이 갔던 거리</b>를 기억하고, 그보다 나아지지 않는 상태가
            // 이어지면 도달 불가로 본다. 집에 다녀오면 거리가 나빠지므로 왕복이 그대로 잡힌다.
            // 표적이 바뀌면 기록을 새로 시작한다.
            double d = mob.blockPosition().distSqr(target);
            if (!target.equals(progressTarget)) {
                progressTarget = target;
                bestDistSqr = d;
                stuckTicks = 0;
            } else if (d < bestDistSqr - 1.0) {
                bestDistSqr = d; // 한 발이라도 가까워졌다 — 진행 중
                stuckTicks = 0;
            } else if (++stuckTicks >= NO_PROGRESS_DROP_TICKS) {
                FarmStore.Plot p = plotOf(target);
                FarmTicker.reportUnreachable(mob.getId(), p != null ? p.id : 0L);
                SimEvents.event(mob, "출근포기", String.format(
                        "구획%d 까지 %.0f블록 — %d틱 동안 더 가까워지지 못해 배정을 반납한다"
                                + "(최근접 %.0f블록%s)",
                        p != null ? p.id : 0L, Math.sqrt(d), stuckTicks, Math.sqrt(bestDistSqr),
                        mob.isCritical() ? " · 위급" : ""));
                target = null;
                progressTarget = null;
                stuckTicks = 0;
                mob.setWorkAnchor(null);
            }
            return;
        }
        stuckTicks = 0;
        var st = mob.level().getBlockState(target);
        if (st.is(Blocks.SWEET_BERRY_BUSH) && st.getValue(SweetBerryBushBlock.AGE) >= 3) {
            mob.level().setBlockAndUpdate(target, st.setValue(SweetBerryBushBlock.AGE, 1));
            FarmStore.Plot p = plotOf(target);
            // 관리 효율(회차 S2 — 관리 바닥값): 마름 밭 E = max(마름 E, 지주 재흡수 E) — 지주
            // 오버사이트가 바닥이라 무능 마름 조기 임명해도 붕괴 없음, 지주가 캡 초과로 얇아지면
            // 마름 전담 E가 바닥 넘어 캡 돌파. 무마름 밭은 지주의 무마름 타일 합 기준. (plotEfficiency)
            // 마름의 채집 배율도 같은 순회에서 함께 읽는다(FarmStore.handOf).
            FarmStore.Hand hand = p != null ? farmStore().handOf(serverLevel(), p) : null;
            double e = hand != null ? hand.efficiency : 1.0;
            // 기본 수확 = 일한 개체의 채집 능력, <b>단 마름 솜씨가 바닥</b>이다(FarmStore.handOf).
            // 마름은 감독만 하는 것이 아니라 일을 가르친다 — 못하는 소작은 마름 수준까지 올라오고,
            // 마름보다 잘하는 소작은 제 능력을 유지한다(재능 있는 평민의 상승 경로 보존).
            //
            // <p><b>단, 그 증분은 소작의 것이 아니다</b>(소작 분할부 참조). 바닥은 <b>산출</b>을
            // 올리지 <b>임금</b>을 올리지 않는다 — 조직이 만든 몫은 조직(밭 계정)의 것이다.
            double mine = FoodEconomy.forageYieldMult(mob.getIndividual());
            double useMult = Math.max(mine, hand != null ? hand.stewardForage : 0.0);
            // 칸당 계수는 <b>반드시</b> FarmEconomy 의 상수를 쓴다. 여기에 숫자를 박아 두었더니
            // TILE_YIELD_MULT 를 0.5→0.8 로 올려도 실제 수확은 0.5 그대로였다 — 상수는 마름 선발·
            // 후보 점수 같은 예측 경로에만 쓰여, 계획과 실적이 조용히 어긋났다(w5 무효).
            double base = FarmEconomy.TILE_YIELD_MULT * useMult;
            // 가구 밭 판정 — 배우자는 <b>양방향</b>으로 본다(marriedTo). 남편의 spouseId 는 본처만
            // 가리키므로 단방향이면 첩 소유 밭이 "남의 밭"으로 잡혀 자기 가구 수확이 소작 분할로
            // 새어 나간다.
            boolean household = p != null && (p.ownerId == mob.getIndividual().id()
                    || mob.marriedTo(p.ownerId));
            if (p != null && !household) {
                // 소작 분할 — <b>E 는 지주 몫에만</b> 곱한다(5규칙 정합):
                //   규칙4·5(밭 무한 성장·자산 무한 누적) + 규칙3(소작 출산 2~3)이 동시에 서려면
                //   소작 수취가 밭 크기에 반비례해서는 안 된다. 그런데 종전에는 E(=(용량/타일)²)와
                //   누진 fee 가 <b>둘 다</b> 소작 몫을 규모에 반비례로 깎아, 193타일 실측에서 소작이
                //   자기 노동의 16.5%만 받았다(E 0.475 × 소작몫 0.347). 규칙4가 성공할수록 규칙3이
                //   깨지는 구조였다. 소작은 자기가 딴 만큼 받고, 관리 실패의 손실은 지주가 진다.
                //   (fee 누진은 FarmEconomy.fee 에서 함께 평탄화 — 그쪽 주석 참조.)
                //   새 회계 항등식: tShare + ownerCut + waste == base, waste = base×fee×(1−E).
                // 자산 누진 지대 — 소작 가구가 부유할수록 수취 비율이 줄고, 줄어든 만큼이 지주의
                // 초과분(저장고 직행)으로 간다. 기준선은 <b>성인</b> 소모라 자녀가 늘어도 오르지
                // 않는다(출산 자기제한). 규모 누진(ownerTiles)이 규칙4 성공 시 규칙3을 깨던 것과
                // 달리, 자산 기준은 소작 개인에 대해 자기교정적이고 영지 크기와 무관하다.
                double tenantLarder = mob.getHomePos() == null ? 0.0
                        : LarderStore.get(serverLevel()).get(mob.getHomePos());
                double adultNeed = mob.adultDailyNeed();
                // ── 마름 증분은 소작이 아니라 <b>계정</b>이 가져간다 ──
                //   종전에는 소작 몫을 부풀린 총수확(base)에 곱했다. 그래서 무능·멍청 소작이
                //   마름 밭에 붙기만 하면 제 능력의 몇 배를 집으로 들고 갔다 — 실측(시드11 d11):
                //   소작수확 558건 중 <b>355건(63%)</b>이 바닥에 걸렸고, 본인 능력 0.33 인 자가
                //   1.74 로 받아갔다(5.3배). 특성을 아무리 깎아도 소작 소득에는 닿지 않아,
                //   무능·멍청의 치명성이 통째로 세탁되고 있었다.
                //   이제 소작은 <b>제 능력분</b>의 소작 몫만 가져가고, 마름이 만든 증분(lift)은
                //   전액 밭 계정으로 간다. 조직이 만든 것은 조직의 것이다.
                //   회계 항등식 불변: tShare + lift + base×fee == base.
                //   mine ≥ 마름이면 lift = 0 → <b>현행과 완전히 동일</b>(재능 있는 평민 무손상).
                double mineBase = FarmEconomy.TILE_YIELD_MULT * mine;
                double tFull = FarmEconomy.tenantShare(base, tenantLarder, adultNeed);
                double tShare = Math.min(tFull,
                        FarmEconomy.tenantShare(mineBase, tenantLarder, adultNeed)); // E 미적용
                double lift = tFull - tShare;
                double baseShare =
                        (FarmEconomy.baseOwnerShare(base, tenantLarder, adultNeed) + lift) * e;
                double excessShare = FarmEconomy.excessOwnerShare(base, tenantLarder, adultNeed) * e;
                mob.addHarvest(tShare);
                p.account += baseShare;
                p.excessHoard += excessShare; // 잠금 축장(밤 정산 때 지주 저장고로, 확장 무관)
                if (mob.getIndividual().id() != p.stewardId) {
                    // 마름 수당의 입력 — 소작이 <b>집에 가져간 것</b>이 아니라 소작이 <b>생산한
                    // 것</b>(tShare + lift)이 기준이다. 위 변경으로 무능한 소작의 수취가 떨어지는데
                    // 수당까지 같이 떨어지면, 못하는 일꾼을 끌어올릴수록 마름이 손해를 보는
                    // 뒤틀린 유인이 생긴다. 마름은 제 조직이 생산한 만큼 받는다.
                    // (수치상 tShare + lift == 종전 tShare 라, 이 항은 수당을 <b>현행값 그대로</b>
                    //  붙들어 둔다 — 이번 개편이 수당을 건드리지 않았음을 A/B 로 증명할 수 있다.)
                    FarmTicker.recordTenantPay(p.id, tShare + lift, mob.getId());
                }
                // 원장: totalYield 는 <b>실분배 합</b>(낭비 제외) — totalToOwner+totalToTenant 와 항등.
                farmStore().recordHarvest(p, tShare + baseShare + excessShare,
                        baseShare + excessShare, tShare);
                com.evosim.mod.log.SimAudit.record(
                        com.evosim.mod.log.SimAudit.Src.FARM_TENANT, tShare);
                com.evosim.mod.log.SimAudit.record(
                        com.evosim.mod.log.SimAudit.Src.RENT, baseShare + excessShare);
                SimEvents.event(mob, "소작수확", String.format(
                        "+%.2f (지대 계정 %.2f + 축장 %.2f, E%.2f, G%.2f%s, 오늘 %d타일)",
                        tShare, baseShare, excessShare, e, useMult,
                        useMult > mine
                                ? String.format("←마름(본인 %.2f · 증분 %.2f 계정행)", mine, lift)
                                : "", harvestedToday + 1));
            } else {
                // 자영 = 전액 지주 몫이므로 E 적용(확장 제동 유지 — 자영 지주만 예외가 되지 않게).
                double own = base * e;
                mob.addHarvest(own); // 자기 밭 = 100% 본인 몫
                if (p != null) {
                    farmStore().recordHarvest(p, own, own, 0.0); // 자영 수확도 원장에(주인 몫)
                }
                com.evosim.mod.log.SimAudit.record(
                        com.evosim.mod.log.SimAudit.Src.FARM_SELF, own);
                SimEvents.event(mob, "밭수확", String.format("자영 +%.2f (E%.2f, 오늘 %d타일)",
                        own, e, harvestedToday + 1));
            }
            resetTimer(target);
            harvestedToday++;
            cooldown = HARVEST_COOLDOWN;
        }
        target = null;
    }

    private net.minecraft.server.level.ServerLevel serverLevel() {
        return (net.minecraft.server.level.ServerLevel) mob.level();
    }

    private FarmStore farmStore() {
        return FarmStore.get(serverLevel());
    }

    /** 이 타일이 속한 구획. */
    private FarmStore.Plot plotOf(BlockPos pos) {
        for (FarmStore.Plot p : farmStore().all().values()) {
            for (long l : p.tiles) {
                if (l == pos.asLong()) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * 일할 밭(소유 구획 전부 + 오늘 배정된 소작 구획)에서 가장 가까운 익은 타일.
     *
     * <p><b>돌봄 반경 밖 타일은 후보에서 제외</b>한다 — {@link MimicForageGoal}의 boundMode 와 대칭.
     * 종전엔 이 goal 만 육아 구속을 전혀 보지 않아, 구속된 부모가 12블록 밖 밭 타일을 표적으로
     * 잡고 밖으로 걸어나가면 우선순위 1인 {@link MimicParentingGoal}(반경 r 이탈 시 발동)이
     * 즉시 되끌었다. 두 판정이 같은 경계 r 을 히스테리시스 없이 공유하므로 r 에서 무한 진동한다:
     * 6.6 → 육아 발동(안으로) → 6.4 → 육아 해제 → 밭일 발동(밖으로) → 6.6 … 실측 증상은
     * "밭일/육아가 1초 간격으로 바뀌며 제자리에서 움찔거림", 결과는 <b>노동 시간 전체 수입 0</b>.
     * 밭 부지는 거처 12블록을 회피하므로(findFarmSite) 실질적으로 구속자는 밭일을 하지 않게 되며,
     * 이는 채집 쪽에 이미 있던 사양("집에만 있는 쪽이 정원을 맡는다")과 같은 규칙이다.
     */
    private BlockPos nearestWorkRipe() {
        if (!(mob.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return null;
        }
        double careR = careRadius();
        long id = mob.getIndividual().id();
        long assigned = FarmTicker.assignedPlot(mob.getId());
        FarmStore fs = FarmStore.get(sl);
        long newestMine = fs.newestOwnedPlot(id);
        long stewardPlot = fs.stewardOf(id);
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (FarmStore.Plot p : fs.all().values()) {
            boolean mine = p.ownerId == id;
            // 배우자 소유 밭 — 양방향 판정. 종전 단방향(mob.getSpouseId() == p.ownerId)은 남편이
            // 첩의 밭을 가족 노동으로 인정하지 못했다(다처 비대칭). 아래 '직영지 원칙'의 최신
            // 구획도 그 배우자 기준으로 구해야 하므로 소유자별로 조회한다.
            boolean spouses = !mine && mob.marriedTo(p.ownerId);
            // 마름 노동 모드(v1.3) — 소작 0인 자기 위임 구획은 직접 일군다(분배는 소작식).
            // 소작이 1명이라도 배정되면 관리 모드(수당) — 밭일 대신 본업(채집)으로 복귀.
            boolean stewardLabor = p.id == stewardPlot && p.id != assigned
                    && FarmTicker.assignedToPlot(p.id) == 0;
            if (!mine && !spouses && p.id != assigned && !stewardLabor) {
                continue; // 무단 수확 금지 — 소유·배우자 소유(가족 노동)·오늘 배정·마름 노동만
            }
            // 직영지 원칙(소작 루프 v2): 다구획 주인 가족의 자가 노동은 최신 구획만 —
            // 구 구획은 100% 소작 몫(신규 개간과 동시에 인계). 배정 소작 출근은 그대로.
            if (mine && p.id != newestMine && p.id != assigned) {
                continue;
            }
            if (spouses && p.id != fs.newestOwnedPlot(p.ownerId) && p.id != assigned) {
                continue;
            }
            // <b>밭이 세어 둔 익음 목록</b>만 본다 — 여기서 다시 블록을 훑으면 관리 쪽 판단과
            // 어긋날 수 있고(그 어긋남이 "익은 걸 두고 관리하는" 장면이었다), 미믹마다 매 틱
            // 전 타일을 훑는 비용도 그대로 남는다.
            for (long l : p.ripe) {
                BlockPos pos = BlockPos.of(l);
                if (careR >= 0.0 && pos.distSqr(mob.getHomePos()) > careR * careR) {
                    continue; // 돌봄 반경 밖 — 표적으로 잡으면 육아 goal 과 경계 진동(위 주석)
                }
                double d = mob.blockPosition().distSqr(pos);
                if (d < bd) {
                    bd = d;
                    best = pos;
                }
            }
        }
        return best;
    }

    /** 돌봄 구속 중이면 반경(careRadius, 최소 {@link MimicParentingGoal#CARE_SLACK}), 아니면 -1(무제한). */
    private double careRadius() {
        if (mob.isCritical()) {
            return -1.0; // 생존이 육아 구속보다 우선 — MimicForageGoal 의 위급 분기와 동일
        }
        if (!mob.isCaregiverBound() || mob.getHomePos() == null || mob.getIndividual() == null) {
            return -1.0;
        }
        // 표적 반경 = 노동 반경. 육아 goal 의 견인은 그 바깥(1.35×)에서만 걸리므로 반경 끝
        // 타일에서 일해도 집으로 끌려가지 않는다(경계 진동 방지는 육아 쪽 이력현상 담당).
        return MimicParentingGoal.workRadius(mob.getIndividual());
    }

    /** 수확한 타일의 익음 타이머 리셋(재성장 기점). */
    private void resetTimer(BlockPos pos) {
        if (!(mob.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return;
        }
        for (FarmStore.Plot p : FarmStore.get(sl).all().values()) {
            for (int i = 0; i < p.tiles.length; i++) {
                if (p.tiles[i] == pos.asLong()) {
                    p.planted[i] = FarmStore.careNow(sl, p); // 수확 리셋도 가상 시각으로
                    // 목록도 <b>그 자리에서</b> 옮긴다. 다음 스캔(200틱)을 기다리면 방금 딴
                    // 타일이 최대 10초간 익은 목록에 남아 같은 자리를 계속 노린다.
                    FarmTicker.markHarvested(sl, p, pos.asLong());
                    FarmStore.get(sl).setDirty();
                    return;
                }
            }
        }
    }
}
