package com.evosim.mod.entity;

import com.evosim.core.LifeStage;
import com.evosim.core.Schedule;
import com.evosim.mod.encounter.Encounter;
import com.evosim.mod.encounter.EncounterContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 이웃 방문·잡담 goal (배회 생활 v1 — 설계 계획서 v2 §4). 배회 시간의 유휴 정착민이 타 가구
 * 모닥불로 마실을 가 근처 성년과 잡담한다(없으면 불쬐기). 도착 시 {@link Encounter#begin}
 * 경유 — <b>추후 신분 예법·물물교환·요청은 전부 이 대화 순간에 레지스트리 등록만으로 얹힌다</b>.
 *
 * <p>목적지: 8~48블록 내 타 가구 켜진 모닥불 중 (id+날) 결정론 선택 — 날마다 다른 집.
 * 같은 모닥불 하루 방문 상한 2명(선착), 개체 쿨다운 2일. 원거리 이동은 visitAnchor 로 리시
 * 협조(노인 마실과 동일 패턴).
 */
public class MimicVisitGoal extends Goal {

    private static final int CHAT_TICKS = 340;      // 체류 예산(17초)
    private static final double MIN_DIST = 8.0;     // 이보다 가까우면 이웃 아님(같은 마당)
    private static final double MAX_DIST = 48.0;    // 통근권 = 마을권
    private static final double ARRIVE = 3.0;       // 모닥불 도착 판정
    private static final int VISIT_COOLDOWN_DAYS = 2;
    private static final int SEAT_CAP = 2;          // 같은 모닥불 하루 방문 상한

    // 좌석 장부(하루 단위 휘발) — hearth(home) 좌표 → 오늘 방문자 수. 새 날에 전체 리셋.
    private static final Map<Long, Integer> SEATS = new HashMap<>();
    private static long seatDay = -1L;

    /** 선점 시 앵커를 쥐고 있을 것인가 — 끄면 종전 거동(선점마다 전부 놓아 무한 왕복). */
    private static boolean holdOnPreempt = true;

    public static void setHoldOnPreempt(boolean on) {
        holdOnPreempt = on;
    }

    public static boolean holdOnPreempt() {
        return holdOnPreempt;
    }

    private final MimicEntity mob;
    private BlockPos dest;
    private boolean atChurch;          // 이번 목적지가 교회인가 — 도착 처리가 갈린다
    private BlockPos churchPos;        // 그 교회의 등기 좌표(자리가 아니라 앵커)
    private int chatLeft;
    private boolean began;
    private boolean reserved;   // 좌석을 이미 잡았는가 — 선점 후 재개 시 중복 예약 방지

    public MimicVisitGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** 검증 무대용 — 좌석 장부 초기화/선점. */
    public static void clearSeats() {
        SEATS.clear();
        seatDay = -1L;
    }

    /** 검증 진단 — canUse 게이트·후보 산출을 문자열로(판정에 사용 금지). */
    public static String debugProbe(MimicEntity m) {
        StringBuilder sb = new StringBuilder();
        sb.append("stage=").append(m.getStage());
        sb.append(" crit=").append(m.isCritical());
        sb.append(" build=").append(m.isBuilding());
        sb.append(" travel=").append(m.isCourtTravel());
        sb.append(" bound=").append(m.isCaregiverBound());
        long gameDay = com.evosim.mod.entity.SimTime.tick(m.level()) / 24000L;
        sb.append(" day=").append(gameDay).append(" lastVisit=").append(m.lastVisitDay());
        int cands = 0;
        int nearRejected = 0;
        if (m.getHomePos() != null && m.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            for (long h : MimicEntity.occupiedHomes(sl)) {
                net.minecraft.core.BlockPos p = net.minecraft.core.BlockPos.of(h);
                if (p.equals(m.getHomePos())) {
                    continue;
                }
                double d = p.distSqr(m.getHomePos());
                if (d < MIN_DIST * MIN_DIST) {
                    nearRejected++;
                } else if (d <= MAX_DIST * MAX_DIST) {
                    cands++;
                }
            }
        }
        sb.append(" cands=").append(cands).append(" tooNear=").append(nearRejected);
        return sb.toString();
    }

    public static void debugFillSeats(BlockPos hearthHome, int n, long day) {
        seatDay = day;
        SEATS.put(hearthHome.asLong(), n);
    }

    private static void rollDay(long day) {
        if (day != seatDay) {
            seatDay = day;
            SEATS.clear();
        }
    }

    @Override
    public boolean canUse() {
        if (mob.getStage() != LifeStage.ADULT || mob.getIndividual() == null
                || mob.getHomePos() == null || mob.isBuilding() || mob.isFastSettle()
                || mob.isCourtTravel() || mob.isCritical() || mob.isCaregiverBound()) {
            return abandon();
        }
        if (Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime())
                != Schedule.Phase.WANDER) {
            return abandon();
        }
        // <b>여유가 있어야 마실·예배를 간다.</b>
        //
        // 이 줄과 우선순위 상향은 <b>한 쌍</b>이다. 방문은 9번이라 배회 시간에 채집(7)·놀이(8)에
        // 언제나 밀렸다 — 명석·경쟁 특성자는 배회에도 일하고, 부모는 놀아주고, 가난한 자는
        // 채집한다. 실측: 성년 53명 중 29명이 교회 반경 안이고 쿨다운도 0명인데 <b>방문이
        // 0건</b>이었고, 이웃집 마실까지 0 이었다(goal 자체가 안 돈 것이지 교회 코드 문제가
        // 아니었다).
        //
        // 그래서 우선순위를 채집 위로 올리는데, 그것만 하면 <b>배고픈 개체가 채집 대신 예배를
        // 가서 굶는다</b>. 여유 조건을 함께 걸어야 "먹을 것이 없으면 일하고, 남으면 나선다"가
        // 되어 순서가 뒤집히지 않는다.
        if (!mob.larderComfortable()) {
            return abandon();
        }
        long gameDay = com.evosim.mod.entity.SimTime.tick(mob.level()) / 24000L;
        if (gameDay - mob.lastVisitDay() < VISIT_COOLDOWN_DAYS) {
            return abandon(); // 개체 쿨다운은 단조 시계(gameTime 일) — 수면 스킵·무대 시간 조작에 불변
        }
        // 좌석 장부는 새벽 시계(dayTime 일) — 하루 생활 리듬(배정·배회)과 같은 축이고,
        // 무대가 setDayTime 으로 고정할 수 있어 결정론(단조 시계면 무대 중 일경계 통과 시
        // 장부가 초기화돼 만석 감시가 간헐 붕괴 — 실측 플레이크).
        long seatKey = mob.level().getDayTime() / 24000L;
        rollDay(seatKey);
        // <b>리시가 데려다 주는 중이면 같은 목적지로 이어 간다.</b> 여기서 다시 뽑으면 표적이
        // 매번 바뀌어 제자리 움찔이 된다(stop 주석의 그 현상이 선점 경로로도 일어난다).
        if (dest != null) {
            return true;
        }
        dest = pickDest(gameDay);
        return dest != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (dest == null) {
            return false;
        }
        if (Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime())
                != Schedule.Phase.WANDER) {
            return false;
        }
        return !began || chatLeft > 0;
    }

    @Override
    public void start() {
        began = false;
        chatLeft = CHAT_TICKS;
        if (dest != null) {
            // 좌석 선점(선착) + 리시 협조 앵커 — 활동반경 밖 이웃도 리시가 끌고 간다.
            //
            // <b>교회는 앵커로 센다.</b> 마실은 목적지(모닥불)가 곧 정원의 단위지만, 교회는
            // 목적지가 <b>건물 안의 한 자리</b>라 자리 좌표로 세면 한 자리에 한 명씩 세는 꼴이
            // 되어 건물 상한({@link Facilities#CHURCH_CAP})이 영영 차지 않는다 —
            // {@code pickChurch} 가 앵커로 확인하므로 세는 쪽도 앵커여야 한다.
            // 좌석은 <b>한 번만</b> 잡는다. 리시에 선점됐다 다시 서는 경우 stop 이 반납하지
            // 않았으므로(위 주석), 여기서 또 더하면 한 사람이 정원을 둘 이상 먹는다.
            if (!reserved) {
                SEATS.merge(atChurch ? churchPos.asLong() : dest.asLong(), 1, Integer::sum);
                reserved = true;
            }
            mob.setVisitAnchor(dest);
        }
    }

    @Override
    public void tick() {
        if (dest == null) {
            return;
        }
        double d = mob.blockPosition().distSqr(dest);
        if (d > ARRIVE * ARRIVE) {
            if (!began) {
                mob.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, 1.0);
            }
            return;
        }
        long today = com.evosim.mod.entity.SimTime.tick(mob.level()) / 24000L;
        if (atChurch) {
            // 교회 도착 — <b>왔다는 사실만</b> 적는다. 헌금·신세·급여는 새벽 정산이 한 번에
            // 처리한다(저장고를 여러 곳에서 만지지 않게). 대화 상대를 찾지 않는 이유는
            // 교회가 만남의 자리가 아니라 <b>주인에게 신세를 지는 자리</b>이기 때문이다.
            if (!began) {
                began = true;
                mob.setLastVisitDay(today);
                mob.noteChurchVisit(churchPos, today);
                com.evosim.mod.log.SimEvents.event(mob, "교회", String.format(
                        "방문 @%d,%d (집에서 %.0f블록)", churchPos.getX(), churchPos.getZ(),
                        Math.sqrt(mob.getHomePos().distSqr(churchPos))));
            }
            chatLeft--;
            mob.getLookControl().setLookAt(dest.getX() + 0.5, dest.getY() + 1.0, dest.getZ() + 0.5);
            return;
        }
        MimicEntity partner = nearestAdultAt(dest);
        if (!began) {
            began = true;
            mob.setLastVisitDay(today); // 도착 = 방문 성립
            if (mob.level() instanceof ServerLevel sl) {
                Encounter.begin(sl, mob, partner, EncounterContext.Place.HEARTH,
                        EncounterContext.Occasion.VISIT, CHAT_TICKS);
            }
        }
        chatLeft--;
        if (partner != null) {
            mob.getLookControl().setLookAt(partner, 30.0F, 30.0F);
            partner.getLookControl().setLookAt(mob, 30.0F, 30.0F); // 마주 보기(AI 비침습)
            if (chatLeft % 60 == 0) {
                mob.swing(InteractionHand.MAIN_HAND); // 이따금 손짓 — "대화하는 그림"
            }
        } else {
            mob.getLookControl().setLookAt(dest.getX() + 0.5, dest.getY() + 0.5,
                    dest.getZ() + 0.5); // 상대 없으면 불쬐기
        }
    }

    @Override
    public void stop() {
        // <b>도착 못 하고 물러나면 예약을 반납한다.</b> start 에서 자리를 하나 잡아 두는데
        // stop 이 그것을 놓지 않으면, 상위 우선순위 goal 에 선점될 때마다 예약만 쌓인다.
        // 그러면 두 가지가 한꺼번에 망가진다: (1) 유령 예약으로 정원이 차서 남이 못 오고,
        // (2) 다시 설 때마다 {@code pickDest} 가 <b>다른 자리</b>를 잡아 표적이 계속 바뀐다 —
        // 아이가 어디 못 가고 움찔거리는 모양이 된다.
        //
        // 도착한 뒤(began)에는 반납하지 않는다 — 그 자리는 실제로 쓰인 것이다.
        // <b>리시에 선점된 것뿐이면 아무것도 놓지 않는다.</b> 이 goal 은 활동반경 밖 이웃을
        // 리시(우선순위 2)가 끌고 가 주도록 visitAnchor 를 건다. 그런데 리시가 그 앵커 때문에
        // 발동해 이 goal(6)을 선점하면 stop 이 돌고, 여기서 앵커를 지우면 앵커가 집으로
        // 돌아가 리시가 즉시 해제된다 → 마실이 다시 서서 목적지를 새로 뽑는다 → 리시 발동 …
        // 틱 단위 무한 루프다. 육안 관측 "마실/복귀가 초간격으로 반복되며 움찔거린다"가 이것이고,
        // 데려다 주라고 만든 장치가 스스로를 무너뜨리고 있었다.
        //
        // 선점과 진짜 종료는 canContinueToUse 로 가른다 — 아직 이어 갈 수 있으면 선점이다.
        if (holdOnPreempt && dest != null && !began && canContinueToUse()) {
            return; // 예약·앵커·목적지 유지 — 리시가 데려다 주는 중
        }
        // <b>도착 못 하고 물러나면 예약을 반납한다.</b>
        if (!began && dest != null) {
            long key = atChurch && churchPos != null ? churchPos.asLong() : dest.asLong();
            SEATS.computeIfPresent(key, (k, v) -> v <= 1 ? null : v - 1);
        }
        dest = null;
        began = false;
        reserved = false;
        atChurch = false;
        churchPos = null;
        mob.setVisitAnchor(null);
    }

    /**
     * 문지기에서 막혔을 때 <b>들고 있던 예약·앵커를 놓는다</b> — 항상 {@code false}.
     *
     * <p>{@code stop} 이 선점 때 아무것도 놓지 않게 바꿨으므로, 놓는 책임이 여기로 온다.
     * 리시가 데려가는 도중에 조건이 바뀌면(배회 시간 종료·저장고 부족·위급) goal 이 다시 서지
     * 않아 {@code stop} 이 불릴 일이 없고, 그러면 앵커가 영영 남아 리시가 그 집으로 계속 끈다.
     */
    private boolean abandon() {
        if (dest != null) {
            if (!began) {
                long key = atChurch && churchPos != null ? churchPos.asLong() : dest.asLong();
                SEATS.computeIfPresent(key, (k, v) -> v <= 1 ? null : v - 1);
            }
            dest = null;
            began = false;
            reserved = false;
            atChurch = false;
            churchPos = null;
            mob.setVisitAnchor(null);
        }
        return false;
    }

    /** 목적지 — 8~48블록 내 타 가구 켜진 모닥불(거처 좌표) 중 (id+날) 결정론 선택 + 좌석 확인. */
    private BlockPos pickDest(long day) {
        List<BlockPos> cands = new ArrayList<>();
        if (!(mob.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return null;
        }
        // <b>교회를 먼저 본다</b>(P6) — 있으면 그날은 교회로 간다.
        //
        // 계획서 1.5 의 교회는 "확률적 방문" 이고, 이 goal 자체가 이미 확률적이다: 개체마다
        // 쿨다운 2일, 자리 상한, (id+날) 결정론 선택. 그래서 <b>따로 확률을 두지 않는다</b> —
        // 새 난수를 얹으면 몰림을 막는 장치가 둘이 되어 어느 쪽이 듣는지 못 가린다.
        //
        // 교회를 우선하는 이유: 마실 후보(이웃 집)는 수십 채라, 교회를 같은 통에 섞으면
        // 뽑힐 확률이 수십 분의 일이 되어 P6 를 관측할 만큼 방문이 쌓이지 않는다. 자리 상한이
        // 몰림을 막으므로 우선해도 한 곳에 몰리지 않는다.
        BlockPos church = pickChurch(sl, day);
        if (church != null) {
            atChurch = true;
            return church;
        }
        atChurch = false;
        for (long h : MimicEntity.occupiedHomes(sl)) {
            BlockPos p = BlockPos.of(h);
            if (p.equals(mob.getHomePos())) {
                continue; // 자기 집
            }
            double d = p.distSqr(mob.getHomePos());
            if (d < MIN_DIST * MIN_DIST || d > MAX_DIST * MAX_DIST) {
                continue;
            }
            cands.add(p);
        }
        if (cands.isEmpty()) {
            return null;
        }
        cands.sort((a, b) -> Long.compare(a.asLong(), b.asLong())); // 결정론 순서
        long myId = mob.getIndividual().id();
        int start = (int) Math.floorMod(myId + day, cands.size());
        for (int k = 0; k < cands.size(); k++) { // 좌석 만석이면 다음 후보로 순회
            BlockPos p = cands.get((start + k) % cands.size());
            if (SEATS.getOrDefault(p.asLong(), 0) < SEAT_CAP) {
                return p;
            }
        }
        return null; // 전부 만석 — 오늘은 마실 없음
    }

    /**
     * 갈 만한 교회의 <b>빈 자리</b> — 없으면 null.
     *
     * <p>목적지를 교회 앵커가 아니라 <b>자리</b>로 잡는다. 앵커 하나로 보내면 방문자가 한 칸에
     * 뭉쳐 밀치기만 한다 — 학생 자리를 한 명씩 나눠 준 것과 같은 이유이고, 그때 실측으로
     * 확인한 문제다. 자리 수가 아니라 크기별 상한({@link Facilities#CHURCH_CAP} ·
     * {@link Facilities#SMALL_CHURCH_CAP})이 하루 정원이다.
     */
    private BlockPos pickChurch(net.minecraft.server.level.ServerLevel sl, long day) {
        List<FacilityStore.Entry> open = new ArrayList<>();
        for (FacilityStore.Entry e : FacilityStore.get(sl).all()) {
            if (e.kind.group != FacilityTemplate.Group.CHURCH) {
                continue;
            }
            if (e.pos.distSqr(mob.getHomePos())
                    > Facilities.CHURCH_REACH * Facilities.CHURCH_REACH) {
                continue;
            }
            int cap = e.kind == FacilityTemplate.Kind.CHURCH
                    ? Facilities.CHURCH_CAP : Facilities.SMALL_CHURCH_CAP;
            if (SEATS.getOrDefault(e.pos.asLong(), 0) < cap) {
                open.add(e);
            }
        }
        if (open.isEmpty()) {
            return null;
        }
        open.sort((a, b) -> Long.compare(a.pos.asLong(), b.pos.asLong())); // 결정론 순서
        FacilityStore.Entry pick = open.get(
                (int) Math.floorMod(mob.getIndividual().id() + day, open.size()));
        var tpl = FacilityTemplate.of(sl, pick.kind, pick.rotation, pick.mirrored);
        if (tpl.isEmpty() || tpl.get().seats().isEmpty()) {
            return null; // 자리 없는 도면 — 갈 곳이 없다(착공 사건의 "자리N" 이 이것을 보여준다)
        }
        List<BlockPos> seats = tpl.get().seats();
        int taken = SEATS.getOrDefault(pick.pos.asLong(), 0);
        churchPos = pick.pos;
        return pick.pos.offset(seats.get(taken % seats.size()));
    }

    /** 모닥불(거처) 5블록 내 다른 성년 — 집주인 또는 동석 방문자. */
    private MimicEntity nearestAdultAt(BlockPos p) {
        MimicEntity best = null;
        double bd = Double.MAX_VALUE;
        for (MimicEntity m : mob.level().getEntitiesOfClass(MimicEntity.class,
                new net.minecraft.world.phys.AABB(p).inflate(5.0))) {
            if (m == mob || !m.isAlive() || m.getIndividual() == null
                    || (m.getStage() != LifeStage.ADULT && m.getStage() != LifeStage.ELDER)) {
                continue;
            }
            double d = m.distanceToSqr(mob);
            if (d < bd) {
                bd = d;
                best = m;
            }
        }
        return best;
    }
}
