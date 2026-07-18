package com.evosim.mod.entity;

import com.evosim.core.Elder;
import com.evosim.core.ExpressionResolver;
import com.evosim.core.FoodEconomy;
import com.evosim.core.Individual;
import com.evosim.core.LifeStage;
import com.evosim.core.Multipliers;
import com.evosim.core.Schedule;
import com.evosim.core.Sex;
import com.evosim.core.SurvivalRules;
import com.evosim.core.Trait;
import com.evosim.mod.log.SimEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * 채집·사냥 goal (설계서 §4 §16). <b>노동 시간대</b>에 성년·만혼소년이 실제로 식량을 확보한다.
 *
 * <ul>
 *   <li><b>사냥</b>: 인지 범위 안에 동물이 보이면 <b>즉각</b> 추격·타격 → 잡으면 사냥배율만큼 식량.</li>
 *   <li><b>채집</b>: 주변 풀(잔디·고사리)을 찾아가 부숴 채집배율만큼 식량. <b>약초학자</b>는 꽃·버섯도 채집.
 *       채집 사이 <b>쿨타임</b>으로 한 번에 다 밀어버려 즉각 소멸하는 것을 막는다.</li>
 * </ul>
 *
 * <p>부순 블록은 드랍 없이 제거되고, 벌인 채집·사냥량이 <b>소지분 H</b>에 쌓인다(성별 배율 — 남 1.5×/여 0.5×).
 * H≥2가 되면 귀가 goal이 저장고에 입금한다(식량 경제 v2). 저장고가 궁하면 배회시간·비제공자도 채집 합류(R4),
 * 위급인데 저장고도 비면 시간대 무시 채집 강행(R6).
 */
public class MimicForageGoal extends Goal {

    private static final double HUNT_RANGE = 12.0;   // 이 안의 동물은 즉각 사냥 대상
    private static final int GATHER_COOLDOWN = 100;  // 채집 간 쿨타임(틱) — 즉각 완전소멸 방지
    private static final int ATTACK_COOLDOWN = 20;   // 타격 간격(틱)
    private static final double HUNT_FOOD = 1.5;     // 동물 1마리 = 이 × 사냥배율
    private static final double GATHER_FOOD = 0.08;  // 채집물 1개 = 이 × 채집배율. 0.06→0.08(+33%):
    // 들풀-단독 지형 실측(부부 수입 3.1/일 vs 실효소모 2.9 → 잉여 +0.2)에서 잉여 +1.2/일로 —
    // 저장고 12(번식 문턱) 도달 ~4일. 밭 우위(틱당 0.0037)는 2.5배로 유지(역전 없음). 사냥 불변.
    private static final double BERRY_FOOD = 0.20;   // 다 익은 베리 1수확 = 이 × 정원배율(성중립). 0.50→0.20
    // (계층 분화 v2): 정원 = 실측 25회/가구/일 × 0.20 = 5.0/일 — 부부 소모(6.0)의 83%. "버틸 수
    // 있지만 굶어가는" 하한: 무자식 부부도 약간의 외부 소득이 필요하고, 자식은 외부 소득 필수.
    // 풀 고갈 후 외부 소득 = 소작뿐 → 수치만으로 봉건 종속. (구주석: 0.44→0.50:
    // 여성당 출산 2.4 역산(지참금이 정원으로 전환돼 저축 출발점이 6으로 내려간 밴드 재계산):
    // 무특성 잉여 s₀ = 8×1.21×0.50 − 2.9 = 1.94 → 게이트 회복 3.9÷1.94 = 2.0일 = 쿨다운과 일치
    // (출산 텀 2.0~2.4일), 기본 3명(불운 2) 밴드. 성별·채집특성 곱 없음 — 등급 배율 M(g)만.
    private static final double REACH = 2.2;         // 이 거리 안이면 채집(부수기) 가능. 1.9→2.2(R-5):
    // 경로탐색이 표적 2.0블록 앞에서 done 으로 끝나는 케이스(carex 실서명: tgt 2.0·nav=done·영구
    // 동결)를 도달로 인정 — 팔 뻗으면 닿는 인접 한 칸 간격이라 행동 의미는 불변.
    private static final int STUCK_DROP_TICKS = 40;  // 표적 무진전 이 틱 지속 → 표적 폐기·재표집(R-5 스냅)

    private final MimicEntity mob;
    private Animal huntTarget;
    private BlockPos gatherTarget;
    private int gatherCooldown;
    private int attackCooldown;
    private boolean boundMode; // 지정 돌봄자 — 반경 노동(사냥 금지·표적 careRadius 제한), canUse가 갱신
    private BlockPos stuckPos;  // R-5 동결 감지 — 표적 추적 중 마지막 위치
    private int stuckTicks;     // 같은 위치 지속 틱(표적 있음·미도달 상태 한정)

    public MimicForageGoal(MimicEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        mob.attachForageGoal(this); // 진단 참조(간헐 채집 정지 규명용) — 행위엔 무관여
    }

    /** 진단 문자열 — 현재 표적·쿨타임·사냥 상태(검증 무대 progress 용, 판정에 사용 금지). */
    public String debugState() {
        return String.format("tgt=%s cool=%d hunt=%s",
                gatherTarget == null ? "-" : gatherTarget.toShortString(),
                gatherCooldown, huntTarget == null ? "-" : huntTarget.getType().toShortString());
    }

    @Override
    public boolean canUse() {
        Individual ind = mob.getIndividual();
        if (ind == null || mob.isFastSettle() || mob.isBuilding()) {
            return false; // 무대 검증 시드 통제 / 건축 중엔 채집 정지
        }
        if (!SurvivalRules.canGather(mob.getStage(), ind)) {
            return false; // 유아·일반소년은 자급 불가
        }
        // R6/A-3: 위급(소지 고갈) — 저장고에 밥 있으면 귀가가 우선(MimicReturnGoal),
        // 저장고도 비었을 때만 배회·밤·취침 무시하고 채집을 강행한다(생존이 육아 구속보다 우선).
        if (mob.isCritical()) {
            boundMode = false;
            return !mob.larderHasFood();
        }
        // 지정 돌봄자(육아 개편) — <b>정원 전담</b>: 외부 채집·사냥은 불허, 정원 익은 베리만
        // 딴다(적극 포함 전 등급 — 지시 사양 "집에만 있는 쪽이 정원을 맡는다"). 외부 노동은
        // 커버리지로 해제된 배우자의 몫. 정원 익음이 없으면 육아 전념(goal 비활성).
        boundMode = mob.isCaregiverBound();
        if (boundMode) {
            // 돌봄자 정원 전담은 <b>만족·넉넉 무관</b>(부엌일 — 노동 정지 게이트의 예외).
            // 관측 실측: 돌봄 아내가 만족 진입 후 정원 픽업 0회 → 가구 정원 공급(25회/일)의
            // 절반이 방치돼 관리등급 배율이 실효 소득으로 이어지지 않던 결함. 외부 채집·사냥
            // 불허(승인 사양 "집에만 있는 쪽이 정원을 맡는다")는 그대로 — 익은 정원만 딴다.
            Schedule.Phase p = Schedule.phaseAt(ind, mob.level().getDayTime());
            return p != Schedule.Phase.SLEEP && ripeHomeBerry() != null;
        }
        Schedule.Phase phase = Schedule.phaseAt(ind, mob.level().getDayTime());
        // 노년 쿼터 노동: 노동시간(마감 6000) 안에서 하루 필요량(dailyQuota)만 벌고 쉼. R4 동원 제외.
        if (mob.getStage() == LifeStage.ELDER) {
            long tod = mob.level().getDayTime() % 24000L;
            return phase == Schedule.Phase.WORK && tod < Elder.WORK_END && !mob.elderQuotaMet();
        }
        if (phase == Schedule.Phase.WORK) {
            // 농사 집중(수렵채집→농사 전환) — 자기(배우자) 밭을 가진 provider 는 저장고가 넉넉하면
            // 채집으로 이탈하지 않는다: 밭 익은 타일은 MimicFarmGoal(우선순위 6)이 하루 용량까지
            // 수확하고, 초과분(익은 backlog)은 소작 고용으로 넘긴다. 즉 밭 수입(자가 수확 7.8/일 +
            // 지대)이 채집(9.0/일)을 대체 — 소작을 들일수록 지주로 굳는다. 넉넉선 미만이면(대가족·
            // 흉작) 채집 보충 허용(생계 안전판) — 이때도 밭 goal 이 익은 타일을 먼저 가져간다.
            if (mob.ownsFarm() && mob.larderComfortable()) {
                return false;
            }
            return mob.isProviderRole() || !mob.larderComfortable(); // R4: 넉넉하면 비제공자는 쉼
        }
        if (phase == Schedule.Phase.WANDER) {
            // R4 확장: 저장고 궁하면 배회시간에도 채집 + 경쟁(M7): 이웃 우위까지 쉼 없이 노동(밤잠만 잠)
            return !mob.larderComfortable() || mob.isCompetitiveDriven();
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void stop() {
        huntTarget = null;
        gatherTarget = null;
    }

    @Override
    public void tick() {
        if (gatherCooldown > 0) {
            gatherCooldown--;
        }
        if (attackCooldown > 0) {
            attackCooldown--;
        }
        Individual ind = mob.getIndividual();
        if (ind == null) {
            return;
        }

        // 1) 사냥 — 동물을 보면 즉각. 탐지·추적 유지 거리 모두 같은 배율(넓게 보고 즉시 포기하는 모순 방지).
        //    지정 돌봄자(boundMode)는 사냥 금지 — 추격이 careRadius 밖으로 끌고 나가는 것을 원천 차단.
        double huntRange = HUNT_RANGE * Multipliers.huntRange(ind);
        if (boundMode) {
            huntTarget = null;
        } else {
            if (huntTarget != null && (!huntTarget.isAlive()
                    || mob.distanceToSqr(huntTarget) > huntRange * huntRange * 2.0)) {
                huntTarget = null;
            }
            if (huntTarget == null) {
                huntTarget = nearestAnimal(huntRange);
            }
        }
        if (huntTarget != null) {
            mob.getLookControl().setLookAt(huntTarget, 30.0F, 30.0F);
            if (mob.distanceToSqr(huntTarget) > 4.0) {
                mob.getNavigation().moveTo(huntTarget, 1.2);
            } else if (attackCooldown == 0) {
                mob.swing(InteractionHand.MAIN_HAND);
                mob.doHurtTarget(huntTarget);
                attackCooldown = ATTACK_COOLDOWN;
                if (!huntTarget.isAlive()) {
                    double sexM = ind.sex() == Sex.MALE
                            ? FoodEconomy.MALE_FORAGE : FoodEconomy.FEMALE_FORAGE;
                    double food = HUNT_FOOD * Multipliers.hunt(ind) * sexM * stageMult();
                    mob.addHarvest(food);
                    com.evosim.mod.log.SimAudit.record(com.evosim.mod.log.SimAudit.Src.HUNT, food);
                    SimEvents.event(mob, "사냥", String.format("동물 처치 → 식량 +%.2f", food));
                    huntTarget = null;
                }
            }
            return;
        }

        // 2) 채집 — 풀(약초학자는 꽃·버섯도)을 부숴 식량. 쿨타임 중이면 그냥 배회.
        boolean herbalist = ExpressionResolver.isExpressed(ind, Trait.HERBALIST);
        if (gatherCooldown > 0) {
            idleWander();
            return;
        }
        if (gatherTarget != null && !forageable(mob.level().getBlockState(gatherTarget), herbalist)) {
            gatherTarget = null;
        }
        if (gatherTarget != null && boundMode
                && !isRipeBerry(mob.level().getBlockState(gatherTarget))) {
            gatherTarget = null; // 구속 전에 잡아둔 들풀 — 폐기(정원 전담: 익은 베리만 허용)
        }
        if (gatherTarget == null) {
            gatherTarget = findForage(herbalist, Multipliers.forageRange(ind));
        }
        if (gatherTarget != null) {
            if (mob.blockPosition().closerThan(gatherTarget, REACH)) {
                BlockState ts = mob.level().getBlockState(gatherTarget);
                if (isRipeBerry(ts)) {
                    // 다 익은 베리는 부수지 않고 수확 → age 1 로 되돌려 재성장(바닐라 수확).
                    // 성중립 × 능력 등급 M(g) — 익음률이 상한인 정원은 "누가 따느냐"가 아니라
                    // "얼마나 잘 관리하느냐(등급)"만 수익을 가른다(밴드 산출 ②③).
                    // 가구 관리등급 배율(mob.gardenMult) — 수확자 개인이 아닌 '정원 관리' 기준
                    // (설계 주석 정합·관측 결함 수정: 돌봄자 수확이 관리자 배율을 무효화하던 것).
                    double food = BERRY_FOOD * mob.gardenMult() * stageMult();
                    mob.addHarvest(food);
                    com.evosim.mod.log.SimAudit.record(com.evosim.mod.log.SimAudit.Src.GARDEN, food);
                    mob.level().setBlockAndUpdate(gatherTarget, ts.setValue(SweetBerryBushBlock.AGE, 1));
                    mob.swing(InteractionHand.MAIN_HAND);
                    SimEvents.event(mob, "수확", String.format("옆 정원 베리 → 식량 +%.2f", food));
                    gatherCooldown = GATHER_COOLDOWN;
                } else if (mob.level().destroyBlock(gatherTarget, false)) {
                    double food = GATHER_FOOD * FoodEconomy.forageYieldMult(ind) * stageMult();
                    mob.addHarvest(food);
                    com.evosim.mod.log.SimAudit.record(com.evosim.mod.log.SimAudit.Src.GRASS, food);
                    SimEvents.event(mob, "채집", String.format("+%.2f", food));
                    gatherCooldown = GATHER_COOLDOWN;
                }
                gatherTarget = null;
                stuckTicks = 0;
            } else {
                mob.getNavigation().moveTo(gatherTarget.getX() + 0.5, gatherTarget.getY(),
                        gatherTarget.getZ() + 0.5, 1.0);
                // R-5 스냅 — 표적은 유효한데 경로가 done 인 채 제자리(간헐 영구 동결의 실서명:
                // tgt 고정·cool=0·nav=done·위치 불변). 일정 틱 무진전이면 표적을 버리고 재표집해
                // 동결을 재시도로 바꾼다(무작위 표본이라 다음 표적·경로는 대개 다르다).
                if (mob.blockPosition().equals(stuckPos)) {
                    if (++stuckTicks >= STUCK_DROP_TICKS) {
                        gatherTarget = null;
                        stuckTicks = 0;
                        gatherCooldown = 10; // 짧은 숨 — 같은 틱 재선정으로 동일 동결 재진입 방지
                    }
                } else {
                    stuckPos = mob.blockPosition();
                    stuckTicks = 0;
                }
            }
            return;
        }
        idleWander(); // 채집물도 동물도 없으면 돌아다니며 탐색
    }

    /** 단계 수확 배율 — 노년 0.5(노쇠). */
    private double stageMult() {
        return mob.getStage() == LifeStage.ELDER ? Elder.FORAGE_MULT : 1.0;
    }

    private void idleWander() {
        // 지정 돌봄자 — 반경 밖으로 흘러나가지 않게 거처로 되돌린다(반경 내면 제자리 소요).
        if (boundMode && mob.getHomePos() != null
                && !withinCare(mob.blockPosition())) {
            BlockPos h = mob.getHomePos();
            mob.getNavigation().moveTo(h.getX() + 0.5, h.getY(), h.getZ() + 0.5, 1.0);
            return;
        }
        if (mob.getNavigation().isDone()) {
            Vec3 t = DefaultRandomPos.getPos(mob, 8, 5);
            if (t != null) {
                mob.getNavigation().moveTo(t.x, t.y, t.z, 0.9);
            }
        }
    }

    /** 구속 반경 판정 — 거처 기준 careRadius(+정원 여유 {@link MimicParentingGoal#CARE_SLACK}) 안인가. */
    private boolean withinCare(BlockPos p) {
        BlockPos home = mob.getHomePos();
        if (home == null || mob.getIndividual() == null) {
            return true; // 판정 불능 — 제한하지 않음(구속 자체가 homePos 전제라 실질 미도달)
        }
        double r = Math.max(mob.getIndividual().parentingCare().careRadius(),
                MimicParentingGoal.CARE_SLACK);
        return p.distSqr(home) <= r * r;
    }

    private Animal nearestAnimal(double range) {
        Animal best = null;
        double bestDist = Double.MAX_VALUE;
        for (Animal a : mob.level().getEntitiesOfClass(
                Animal.class, mob.getBoundingBox().inflate(range))) {
            if (!a.isAlive()) {
                continue;
            }
            double d = mob.distanceToSqr(a);
            if (d < bestDist) {
                bestDist = d;
                best = a;
            }
        }
        return best;
    }

    /**
     * 채집물 한 칸 탐색 — 우선순위: ① <b>자기 거처 옆 정원의 다 익은 베리</b>(멀리 있어도 되돌아와 수확),
     * ② 근처(±5 × 탐지배율) 아무 익은 베리, ③ 주변 풀 무작위 표본. 표본 수는 면적 비례로 보정 —
     * 반경만 넓히면 같은 표본이 흩어져 근거리 발견율이 되레 떨어지는 가짜 이점을 막는다(배율 1.0이면 종전 그대로 ±5·24회).
     */
    private BlockPos findForage(boolean herbalist, double rangeMult) {
        BlockPos garden = ripeHomeBerry();
        if (garden != null) {
            return garden; // 내 밭이 익었으면 어디 있든 그리로 가서 딴다 (구속 중에도 허용 — 정원 전담)
        }
        if (boundMode) {
            return null; // 지정 돌봄자는 정원 외 표적 없음(외부 채집 불허 — 해제된 배우자의 몫)
        }
        BlockPos base = mob.blockPosition();
        int half = (int) Math.round(5 * rangeMult);
        BlockPos berry = nearestRipeBerry(base, half);
        if (berry != null) {
            return berry; // 익은 베리가 근처에 있으면 풀보다 먼저 딴다
        }
        int samples = (int) Math.ceil(24.0 * half * half / 25.0);
        for (int i = 0; i < samples; i++) {
            int dx = mob.getRandom().nextInt(half * 2 + 1) - half;
            int dz = mob.getRandom().nextInt(half * 2 + 1) - half;
            int dy = mob.getRandom().nextInt(3) - 1;
            BlockPos p = base.offset(dx, dy, dz);
            if (forageable(mob.level().getBlockState(p), herbalist) && !farmTile(p)) {
                return p;
            }
        }
        return null;
    }

    /** 자기 거처 옆 정원(x=±3, 8칸)에서 다 익은 베리 한 칸. 없으면 null. 위치와 무관하게 정확히 조준한다. */
    private BlockPos ripeHomeBerry() {
        BlockPos home = mob.getHomePos();
        if (home == null) {
            return null;
        }
        for (BlockPos tile : HomeStructure.gardenCells(home, mob.getHomeFacingDir())) {
            for (int dy = 3; dy >= -3; dy--) {
                BlockPos p = tile.offset(0, dy, 0);
                if (isRipeBerry(mob.level().getBlockState(p))) {
                    return p;
                }
            }
        }
        return null;
    }

    /** 근처(±half — 기본 5 × 식물 탐지배율)에서 가장 가까운 다 익은 스위트베리 덤불. 없으면 null. */
    private BlockPos nearestRipeBerry(BlockPos base, int half) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos p = base.offset(dx, dy, dz);
                    if (isRipeBerry(mob.level().getBlockState(p)) && !farmTile(p)) {
                        double d = base.distSqr(p);
                        if (d < bestDist) {
                            bestDist = d;
                            best = p;
                        }
                    }
                }
            }
        }
        return best;
    }

    /** 등록된 밭 타일인가 — 일반 채집은 남의 밭을 건드리지 않는다(무단 수확 금지, 배정은 MimicFarmGoal). */
    private boolean farmTile(BlockPos p) {
        return mob.level() instanceof net.minecraft.server.level.ServerLevel sl
                && FarmStore.get(sl).isFarmTile(p);
    }

    /** 누구나 채집하는 풀(잔디·고사리) + 다 익은 옆 정원 베리 + 약초학자만 채집하는 꽃·버섯. */
    private static boolean forageable(BlockState s, boolean herbalist) {
        if (s.is(Blocks.GRASS) || s.is(Blocks.TALL_GRASS)
                || s.is(Blocks.FERN) || s.is(Blocks.LARGE_FERN)) {
            return true;
        }
        if (isRipeBerry(s)) {
            return true; // 다 익은 베리는 누구나 수확
        }
        return herbalist && (s.is(BlockTags.FLOWERS)
                || s.is(Blocks.BROWN_MUSHROOM) || s.is(Blocks.RED_MUSHROOM));
    }

    /** 스위트베리 덤불이 다 익었는지(age 3 = 수확 가능). */
    private static boolean isRipeBerry(BlockState s) {
        return s.is(Blocks.SWEET_BERRY_BUSH) && s.getValue(SweetBerryBushBlock.AGE) >= 3;
    }
}
