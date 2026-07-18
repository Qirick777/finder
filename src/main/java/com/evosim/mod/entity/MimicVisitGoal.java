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

    private final MimicEntity mob;
    private BlockPos dest;
    private int chatLeft;
    private boolean began;

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
        if (m.getHomePos() != null) {
            for (long h : MimicEntity.litHearthsView()) {
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
            return false;
        }
        if (Schedule.phaseAt(mob.getIndividual(), mob.level().getDayTime())
                != Schedule.Phase.WANDER) {
            return false;
        }
        long gameDay = com.evosim.mod.entity.SimTime.tick(mob.level()) / 24000L;
        if (gameDay - mob.lastVisitDay() < VISIT_COOLDOWN_DAYS) {
            return false; // 개체 쿨다운은 단조 시계(gameTime 일) — 수면 스킵·무대 시간 조작에 불변
        }
        // 좌석 장부는 새벽 시계(dayTime 일) — 하루 생활 리듬(배정·배회)과 같은 축이고,
        // 무대가 setDayTime 으로 고정할 수 있어 결정론(단조 시계면 무대 중 일경계 통과 시
        // 장부가 초기화돼 만석 감시가 간헐 붕괴 — 실측 플레이크).
        long seatKey = mob.level().getDayTime() / 24000L;
        rollDay(seatKey);
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
            SEATS.merge(dest.asLong(), 1, Integer::sum);
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
        MimicEntity partner = nearestAdultAt(dest);
        if (!began) {
            began = true;
            mob.setLastVisitDay(com.evosim.mod.entity.SimTime.tick(mob.level()) / 24000L); // 도착 = 방문 성립
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
        dest = null;
        began = false;
        mob.setVisitAnchor(null);
    }

    /** 목적지 — 8~48블록 내 타 가구 켜진 모닥불(거처 좌표) 중 (id+날) 결정론 선택 + 좌석 확인. */
    private BlockPos pickDest(long day) {
        List<BlockPos> cands = new ArrayList<>();
        for (long h : MimicEntity.litHearthsView()) {
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
