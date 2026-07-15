package com.evosim.mod.entity;

import com.evosim.core.Activity;
import com.evosim.core.Combat;
import com.evosim.core.Courtship;
import com.evosim.core.DeterministicRng;
import com.evosim.core.Elder;
import com.evosim.core.ExpressionResolver;
import com.evosim.core.Famine;
import com.evosim.core.FoodEconomy;
import com.evosim.core.HomeResolution;
import com.evosim.core.Genetics;
import com.evosim.core.Individual;
import com.evosim.core.Kinship;
import com.evosim.core.LifeStage;
import com.evosim.core.MateHome;
import com.evosim.core.BerryEconomy;
import com.evosim.core.Multipliers;
import com.evosim.core.Physique;
import com.evosim.core.Satisfaction;
import com.evosim.core.Polygyny;
import com.evosim.core.ParentingClass;
import com.evosim.core.Reproduction;
import com.evosim.core.Roaming;
import com.evosim.core.Schedule;
import com.evosim.core.Settlement;
import com.evosim.core.Sex;
import com.evosim.core.SurvivalRules;
import com.evosim.core.Trait;
import com.evosim.mod.block.MimicHearthBlock;
import com.evosim.mod.log.SimEvents;
import com.evosim.mod.reg.ModBlocks;
import com.evosim.mod.reg.ModEntities;
import com.evosim.mod.stage.StageObserver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.phys.AABB;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 미믹 개체 (설계서 §1). 플레이어 형태 엔티티 — 성별(스티브/알렉스)·생애단계로 외형 구분.
 *
 * <p>순수 도메인 데이터는 {@link Individual}에 있고(설계서 §18), 이 엔티티는 그것을 마크에서
 * 표현·구동하는 층. 성별/단계만 클라이언트로 동기화(렌더용). 저장/로드 완전판은 Phase 6.
 */
public class MimicEntity extends PathfinderMob {

    private static final EntityDataAccessor<Boolean> FEMALE =
            SynchedEntityData.defineId(MimicEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> STAGE =
            SynchedEntityData.defineId(MimicEntity.class, EntityDataSerializers.INT);

    @Nullable
    private Individual individual;

    private int growthTicks = 0;
    private boolean fastGrowth = false; // 무대 검증용 초고속 성장
    private boolean tooYoungObserved = false;

    // 사회(§3, §10): 거처 포인터(null=방랑자).
    @Nullable
    private BlockPos homePos = null;
    // 태어난(스폰된) 위치 — 애향심 신축 앵커·1세대 정착 기준(설계서 §13-D). 최초 tick에 지연 설정.
    @Nullable
    private BlockPos birthPos = null;

    // 구애 상태머신 (구애 사양서 v2). 방랑자만 참여. 전부 세션 내 상태(저장 안 함, 리로드 시 재탐색).
    private MateState mateState = MateState.IDLE;
    private int searchTimer = 0;                                        // 탐색 누적(틱)
    private final List<Integer> candidates = new ArrayList<>();         // 후보 id (매력 내림차순)
    private final Map<Integer, Integer> candidateCharm = new HashMap<>(); // id → 내 기준 매력
    private final Map<Integer, Long> approachRetryAt = new HashMap<>(); // 재시도 시각(거절 1일·접근실패 2400틱 — 휘발)
    private int rejectionsGiven = 0;                                    // 내가 발행한 거절 수(눈낮춤 §10 — NBT)
    private int courtTargetId = -1;                                     // 현재 구애 대상(상호구애 특례)
    private final List<CourtRecord> courtLog = new ArrayList<>();       // GUI 기록(최근 것 유지)
    private static final int COURT_LOG_MAX = 20;

    // 혼인·거처(재혼/분가/건축). 배우자는 개체 고유 id로 링크(리로드 안정).
    private long spouseId = 0L;                 // 배우자 Individual.id (0=미혼)
    private boolean widowed = false;            // 배우자 사망 → 재구애 참여
    private byte homeFacing = 0;                // 천막 방향(Direction.get2DDataValue)
    private boolean building = false;           // 거처 건축 중(부부 공통) — 분담·리더는 buildTick 이 결정
    @Nullable
    private BlockPos buildTargetPos = null;     // 지금 걸어가 설치할 다음 블록(연출용, 저장 안 함)
    private int buildReachTicks = 0;            // 현재 목표 접근 시도 누적(교착 방지 폴백용)
    private int buildCooldown = 0;              // 개인 설치 박자 카운터(도착 즉시 설치, 전역 동기 아님)
    private static final int BUILD_INTERVAL = 8; // 설치 박자(틱) — 한 칸 놓은 뒤 이만큼 쉼
    private static final double BUILD_REACH = 2.4; // 이 수평 거리 안이어야 설치(밖이면 걸어감)
    private static final int BUILD_REACH_TIMEOUT = 60; // 이만큼 못 닿으면 강제 설치(막힌 자리 교착 방지)
    // 인식 범위 = 신중도(엄격할수록 넓음). 노동은 근접 위주, 배회는 넓게(구애 사양서 v2 확장).
    private static final double WORK_PERCEPT_BASE = 3.0;
    private static final double WORK_PERCEPT_PER = 2.0;   // 레벨당(0~4)
    private static final double WANDER_PERCEPT_BASE = 8.0;
    private static final double WANDER_PERCEPT_PER = 4.0;
    private static final double COURT_CONTACT = 2.5;      // 이 거리면 구애 요청

    // 번식(§6): 마지막 출산 시각 + 출산 수. 실제 발동은 가족 정산의 저장고 게이트(familyTick).
    private long lastBirthTick = -100_000L;
    private int childrenBorn = 0;
    private static final int LOCAL_POP_CAP = 60;     // 지역 과밀 방지(임시)
    private static final double ZOMBIE_AGGRO_RANGE = 12.0; // 전투 가능 성년·노년의 유인 반경 — 위협 판정(12)과 정합
    private static final double ZOMBIE_CLOSE_AGGRO = 4.0;  // 유아·소년은 근접 조우만 — 원거리 자살 유인 제거, 밤 위협은 유지

    // 유아 돌봄/아사 (육아 클래스): 하루 급식 시각에 곁에 성인 없으면 굶주림↑, 임계 초과 시 아사.
    private int careHunger = 0;
    private boolean attendedToday = false;         // 오늘 낮에 성인이 곁에 있었나(래치 — NBT CareLatch)
    private int careTimeScale = 1;                 // 검증용 시간 압축(1=평상) — 낮 샘플 실경로를 그대로 압축(휘발)
    private long lastCareDay = Long.MIN_VALUE; // 마지막 급식 판정한 절대 일자(하루 1회 보장)
    private boolean fastCare = false;          // 무대 검증용 초고속 급식(틱 주기)
    private static final int CARE_INTERVAL = 20;         // fast 모드 판정 주기(틱)
    private static final long CARE_SAMPLE_START = 1000L;  // 낮 돌봄 샘플 창 시작(기상 무렵)
    private static final long CARE_SAMPLE_END = 12000L;   // 낮 돌봄 샘플 창 끝(황혼 전) — [미확정]
    private static final long CARE_SAMPLE_INTERVAL = 400L; // 샘플 간격(틱, 스태거) — [미확정]
    private static final int CARE_DEATH = 3;       // 연속 방치 임계 → 아사 (평상시 3일)
    private static final double FEED_RADIUS = 5.0; // 이 반경 내 성인이 있으면 먹여줌

    // 식량 경제 v2 (FoodEconomy): 개인 보유 H(배부름+소지 통합) + 거처 저장고 L(정수 입출금).
    private double holding = 1.5;               // H — 시작 1.5(밴드 [1,2) 안, 콜드스타트 완충)
    private int hungerGraceTicks = 0;           // H=0 지속 틱(아사 유예 클럭, NBT 저장 — B-4)
    private boolean wasCritical = false;        // 위급 전이 감지(로그 1회용, 휘발)
    private boolean introLogged = false;        // 등장(개체 변수) 로그 1회용 — 로그 ON 상태에서만 소모
    private int mobilizedState = -1;            // R4 동원 전이 감지(-1 미정 / 0 넉넉 / 1 동원)

    // 이주(기근 감지·족외혼 구혼 여행) — Famine 순수 판정에 넘길 시각들. 0 = 미초기화(첫 틱에 now).
    private long lastForageSuccessTick = 0L;    // 마지막 채집/사냥/수확 성공(NBT — 기근 판정 근거)
    private long settledTick = 0L;              // 마지막 정착(setHomePos) 시각(NBT — 재이주 쿨다운)
    private long lonelySinceTick = -1L;         // 짝 후보 0명 시작 시각(-1 = 후보 있음/미혼 아님)
    private long courtTravelUntil = 0L;         // 구혼 여행 만료 시각(NBT)
    private long courtTravelTarget = 0L;        // 구혼 여행 목적지(타향 모닥불, BlockPos.asLong, NBT)
    private BlockPos visitAnchor = null;        // 노인 방문 임시 앵커(ElderVisitGoal 설정 — 휘발)
    private boolean hearthRegistered = false;   // 로드 첫 틱에 켜진 모닥불 전역 목록 재등록(휘발)
    private boolean stageActor = false;         // 검증 무대 개체 — 혈통 원장·인구 통계에서 제외(NBT)
    private long tenantFarm = 0L;               // 상시 소작 중인 밭 구획 id(0=없음, NBT — 봉건 관계)
    private boolean satisfiedToday = false;     // 만족 캐시(휘발 — 새벽마다 재계산, M7)
    private boolean competitiveDriven = false;  // 경쟁 발동 캐시(이웃 우위 — 배회 노동 트리거)
    private int tenantStreak = 0;               // 같은 밭 연속 출근 일수(NBT — 승격 카운터)
    private boolean ledgerChecked = false;      // 원장 등록 1회 시도 가드(휘발 — register 자체는 멱등)
    private double lastSurplus = 0.0;           // 마지막 정산 후 저장고 잔량(스캐너 표시)
    private boolean lastFed = true;             // 위급 아님(스캐너 표시)
    private boolean fastSettle = false;         // 무대 검증용 초고속(시간 600배 압축)
    private double cachedFamilyNeed = 6.0;      // 가족틱이 갱신하는 가족 하루소모 캐시(goal용)
    private boolean cachedProvider = true;      // 가족틱이 갱신하는 제공자 역할 캐시(R4)
    private int cachedMaternal = 0;             // 어미 모성애 캐시(+1 강함/−1 없음) — 자식 허기·성장에 적용
    private double dayGathered = 0.0;           // 오늘 채집 누적(노년 쿼터 판정 — 휘발)
    private long gatherDay = -1L;               // dayGathered 의 날짜(바뀌면 리셋)
    private static final int HUNGER_INTERVAL = 100;       // 소모 주기(틱, 스태거)
    private static final int FAST_HUNGER_INTERVAL = 10;   // fast 모드 소모 주기
    private static final double FAST_TIME_SCALE = 600.0;  // fast: 40틱 ≈ 1일 압축
    private static final int FAST_STARVE_GRACE = 40;      // fast 모드 아사 유예(틱)
    private static final int FAST_SETTLE_INTERVAL = 40;   // fast 모드 가족 정산 주기(틱)

    // 기준값 — 생애단계·성별 배율의 곱으로 실제 속성 산출(설계서 §7 §1).
    private static final double BASE_SPEED = 0.28D;
    private static final double BASE_ATTACK = 2.0D;
    private static final double BASE_HEALTH = 20.0D;

    public MimicEntity(EntityType<? extends MimicEntity> type, Level level) {
        super(type, level);
        // 건축 중 손에 든 블럭(연출용)이 사망 시 드랍되지 않도록.
        setDropChance(EquipmentSlot.MAINHAND, 0.0F);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, BASE_HEALTH)
                .add(Attributes.MOVEMENT_SPEED, BASE_SPEED)
                .add(Attributes.FOLLOW_RANGE, 24.0D)
                // 전투 시 doHurtTarget 이 공격력 속성을 읽으므로 반드시 등록(없으면 크래시).
                .add(Attributes.ATTACK_DAMAGE, BASE_ATTACK);
    }

    /**
     * 생애단계·성별에 따라 속성 재적용 (설계서 §7 §1). 유아 거의 정지·소년 느림·성년 기본,
     * 여성은 힘/체력 배율 0.4(60%↓). 단계 성장·개체 부여 때마다 호출.
     */
    private void refreshStageAttributes() {
        LifeStage stage = getStage();
        // 신체 등급 배수(재빠름/굼뜸 → 속도, 튼튼/빈약 → 체력). 개체 없으면 중립 1.0.
        double agility = individual != null ? Physique.agility(individual) : 1.0;
        double toughness = individual != null ? Physique.toughness(individual) : 1.0;
        var speed = getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(BASE_SPEED * SurvivalRules.moveSpeedFactor(stage) * agility);
        }
        double fem = SurvivalRules.physicalFactor(isFemale() ? Sex.FEMALE : Sex.MALE);
        var attack = getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null) {
            // 힘 등급 배수(힘센 +8%/등급·약함 −6%/등급) — 소모 ±4%/등급(appetite)과 쌍을 이루는
            // 트레이드오프의 효과 쪽. 성별 배율에 곱(무등급/무특성 1.0 = 종전과 동일).
            double str = individual != null ? Physique.strength(individual) : 1.0;
            attack.setBaseValue(BASE_ATTACK * fem * str);
        }
        var health = getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            double newMax = BASE_HEALTH * fem * toughness;
            health.setBaseValue(newMax);
            if (getHealth() > newMax) {
                setHealth((float) newMax);
            }
        }
    }

    @Override
    protected void registerGoals() {
        // 하루 리듬(§16): 밤=귀가·취침, 낮=채집·구애. 우선순위 낮을수록 먼저 점유.
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new MimicBuildGoal(this));     // 거처 건축(부지로 이동·머묾)
        this.goalSelector.addGoal(1, new MimicParentingGoal(this)); // 유아 돌봄(거처 반경 구속)
        this.goalSelector.addGoal(2, new MimicCombatGoal(this));    // 전투 진입/도망(§13-B)
        this.goalSelector.addGoal(2, new MimicLeashGoal(this));     // 활동반경 리시(앵커 복귀, 분산 방지)
        this.goalSelector.addGoal(2, new MimicShareGoal(this));     // 가족 나눔(가드①: 배우자 위급 > 노인 배달)
        this.goalSelector.addGoal(3, new ElderVisitGoal(this));     // 노인 방문: 자식 집 배달·마실 육아(Return보다 앞)
        this.goalSelector.addGoal(3, new MimicReturnGoal(this));    // 식량 귀가: 넣으러/꺼내러(v2, 밥이 구애보다 먼저)
        this.goalSelector.addGoal(3, new MimicCourtshipGoal(this)); // 방랑자 구애(§10, 배회 시간)
        this.goalSelector.addGoal(4, new MimicHomeGoal(this));      // 밤 귀가(§3, 취침·정산 대비)
        this.goalSelector.addGoal(5, new MimicRestGoal(this));      // 취침(집에서 밤새 쉼)
        this.goalSelector.addGoal(6, new MimicFarmGoal(this)); // 자기 밭 우선(틱당 수익 우위) — 같은 순위, 등록 순서로 선행
        this.goalSelector.addGoal(6, new MimicForageGoal(this));    // 노동 채집/사냥 배회(§4)
        this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 1.0D)); // 그 외 배회
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(FEMALE, false);
        this.entityData.define(STAGE, LifeStage.ADULT.ordinal());
    }

    public boolean isFemale() {
        return this.entityData.get(FEMALE);
    }

    public void setFemale(boolean female) {
        this.entityData.set(FEMALE, female);
    }

    public LifeStage getStage() {
        int i = this.entityData.get(STAGE);
        LifeStage[] all = LifeStage.values();
        return all[Math.floorMod(i, all.length)];
    }

    public void setStage(LifeStage stage) {
        this.entityData.set(STAGE, stage.ordinal());
        this.refreshDimensions();
        refreshStageAttributes();
    }

    @Nullable
    public Individual getIndividual() {
        return individual;
    }

    public void setIndividual(Individual ind) {
        this.individual = ind;
        setFemale(ind.sex() == Sex.FEMALE);
        refreshStageAttributes();
    }

    @Nullable
    public BlockPos getHomePos() {
        return homePos;
    }

    public void setHomePos(@Nullable BlockPos pos) {
        this.homePos = pos;
        this.settledTick = level().getGameTime(); // 정착 시각 — 재이주 쿨다운 기준(기근 오탐 방지)
    }

    /** 태어난 위치 — 없으면 현재 위치로 지연 확정(1세대·스폰 개체). 애향심 신축 앵커. */
    public BlockPos getBirthPos() {
        if (birthPos == null) {
            birthPos = blockPosition();
        }
        return birthPos;
    }

    public void setBirthPos(BlockPos pos) {
        this.birthPos = pos;
    }

    /** 활동반경 앵커 — 구혼 여행 중엔 타향 모닥불, 평소엔 거처(없으면 태어난 곳). 리시가 이 반경으로 묶는다. */
    public BlockPos roamAnchor() {
        if (isCourtTravel()) {
            return BlockPos.of(courtTravelTarget); // 리시가 그 마을까지 끌고 가는 캐러밴 엔진
        }
        if (visitAnchor != null) {
            return visitAnchor; // 노인 마실 — 활동반경 밖 자식 집도 리시가 끌고 간다(구혼 여행과 동일 패턴)
        }
        return homePos != null ? homePos : getBirthPos();
    }

    /** 노인 방문 goal 이 설정하는 임시 앵커(null = 해제) — 리시가 자식 집으로 끌게 한다. */
    public void setVisitAnchor(@Nullable BlockPos pos) {
        this.visitAnchor = pos;
    }

    /** 활동반경(블록) — 특성별 차등({@link Roaming}). 개체 없으면 기본값. */
    public double roamRadius() {
        return individual != null ? Roaming.radius(individual) : Roaming.BASE_RADIUS;
    }

    /** 방랑자 = 성년이면서 거처 없음 (짝 구애 대상, §9). */
    public boolean isWanderer() {
        return homePos == null && getStage() == LifeStage.ADULT;
    }

    /** 구애 참여 자격 = 살아있는 짝이 없는 성년(방랑·사별·성년자식 모두 포함). */
    public boolean isSingleAdult() {
        return getStage() == LifeStage.ADULT && individual != null
                && (spouseId == 0L || widowed);
    }

    // ── 점검용 디버그 훅 (명령어로 상황을 즉시 세팅) ──

    /** 두 미믹을 즉시 짝지어 거처 귀속 로직 발동(건축/이주/합류 관찰용). */
    public void debugForcePair(MimicEntity other) {
        pairWith(other);
    }

    /** 배우자 링크만 걸기(거처 변화 없음) — 이미 사는 부모 부부 세팅용. */
    public void debugMarryTo(MimicEntity other) {
        spouseId = other.getIndividual().id();
        widowed = false;
        other.setSpouse(getIndividual().id());
        mateState = MateState.PAIRED;
        other.setMateState(MateState.PAIRED);
    }

    /** 즉시 거처 정착 + 천막·모닥불 완성 배치(홀거처주/가족 사전 세팅용). */
    public void debugSettleWithTent(BlockPos home, Direction facing) {
        setHomePos(home);
        homeFacing = (byte) facing.get2DDataValue();
        building = false;
        if (level() instanceof ServerLevel sl) {
            for (HomeStructure.Placement p : HomeStructure.plan(home, facing)) {
                var state = p.token() == HomeStructure.TOKEN_FENCE
                        ? Blocks.OAK_FENCE.defaultBlockState()
                        : Blocks.WHITE_WOOL.defaultBlockState();
                sl.setBlockAndUpdate(p.pos(), state);
            }
            placeHearth(sl, home, facing, true);
        }
    }

    /**
     * 점검용 — 빈 거처(천막 + 꺼진 모닥불)를 즉시 지어 폐기목록에 등록. 애향심/이주자 재사용 판정 관찰용
     * (거주자 없이 존재하는 꺼진 거처를 하나 만들어 둔다).
     */
    public static void debugPlaceAbandonedHome(ServerLevel sl, BlockPos home, Direction facing) {
        for (HomeStructure.Placement p : HomeStructure.plan(home, facing)) {
            var state = p.token() == HomeStructure.TOKEN_FENCE
                    ? Blocks.OAK_FENCE.defaultBlockState()
                    : Blocks.WHITE_WOOL.defaultBlockState();
            sl.setBlockAndUpdate(p.pos(), state);
        }
        placeHearth(sl, home, facing, false); // 꺼진 모닥불
        ABANDONED_HOMES.add(new int[] {home.getX(), home.getY(), home.getZ(),
                facing.get2DDataValue()});
    }

    /** 거처 상태 — 방랑/단독거처주/가족동거 (재혼 판정용). */
    public MateHome.Status homeStatus() {
        if (homePos == null) {
            return MateHome.Status.WANDERER;
        }
        return countOtherGrownAtHome() == 0 ? MateHome.Status.LONE_OWNER
                : MateHome.Status.FAMILY_MEMBER;
    }

    /**
     * 나 말고 이 거처에 사는 성년·노년 수 — <b>거처 중심</b> 스캔(가구 스캔과 동일 원칙).
     * 과거엔 개체 중심이라, 집에서 먼 곳에서 재혼이 성사되면 집의 성년 아들·노부모가 안 보여
     * LONE_OWNER 오판 → 거주자 있는 집을 폐가화(모닥불 끔)했다. 노년 포함 — 노부모만 남는
     * 집을 폐가로 만들지 않기 위함.
     */
    private int countOtherGrownAtHome() {
        if (homePos == null) {
            return 0;
        }
        int n = 0;
        for (MimicEntity m : level().getEntitiesOfClass(MimicEntity.class,
                new net.minecraft.world.phys.AABB(homePos).inflate(48.0))) {
            if (m != this && m.isAlive()
                    && (m.getStage() == LifeStage.ADULT || m.getStage() == LifeStage.ELDER)
                    && homePos.equals(m.getHomePos())) {
                n++;
            }
        }
        return n;
    }

    /** 배우자가 아직 살아있나(거처/근처에 같은 Individual.id 존재). 반경 128 — 이주성 부부의 낮 분산(최대 활동반경 합)에도 오탐 없게. */
    private boolean spouseAlive() {
        if (spouseId == 0L) {
            return false;
        }
        for (MimicEntity m : level().getEntitiesOfClass(MimicEntity.class, getBoundingBox().inflate(128.0))) {
            if (m != this && m.isAlive() && m.getIndividual() != null
                    && m.getIndividual().id() == spouseId) {
                return true;
            }
        }
        return false;
    }

    public void setSpouse(long id) {
        this.spouseId = id;
        this.widowed = false;
    }

    /** 두 개체가 배우자 링크로 묶여 있나 — 어느 한쪽이라도 상대를 가리키면 참(일부다처의 비대칭 링크 포함). */
    public boolean isSpouseWith(MimicEntity other) {
        return (individual != null && other.spouseId == individual.id())
                || (other.getIndividual() != null && spouseId == other.getIndividual().id());
    }

    public boolean isBuilding() {
        return building;
    }

    public Direction getHomeFacingDir() {
        return Direction.from2DDataValue(homeFacing);
    }

    /**
     * 취침 시 눕는 방향 — 천막 안쪽(입구 반대)으로 머리를 두게 한다(머리가 천막 밖으로 빠져나오는 문제 해결).
     * 렌더러가 이 방향으로 몸을 뉘고 침대처럼 위치를 보정해 천막 안에 반듯이 눕는다.
     */
    @Override
    @Nullable
    public Direction getBedOrientation() {
        if (getPose() == Pose.SLEEPING && homePos != null) {
            return getHomeFacingDir().getOpposite(); // 입구(모닥불)는 전방, 머리는 후방(안쪽)
        }
        return super.getBedOrientation();
    }

    public void setFastGrowth(boolean fast) {
        this.fastGrowth = fast;
    }

    /** 자연 디스폰 안 함 (설계서: 개체는 세대를 이어야 하므로 멀어져도 사라지면 안 됨). */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    private int regenTimer = 0;
    private static final int REGEN_INTERVAL = 40;   // 재생 판정 주기(틱)
    private static final double REGEN_BASE = 0.5;   // 주기당 기본 회복량(× 회복력 등급 배수)

    /** 회복력(강건/병약) 등급 비례 재생 — 위험이 없을 때만 서서히 체력 회복(전투 중 탱킹 방지). */
    private void regenTick() {
        if (individual == null || getHealth() >= getMaxHealth()) {
            regenTimer = 0;
            return;
        }
        if (isUnderThreat()) {
            return; // 위험 중엔 회복 안 함
        }
        if (holding <= 0.0) {
            return; // 굶는 중엔 회복 없음(재생이 아사 피해를 상쇄해 교착되는 것 방지)
        }
        if (++regenTimer < REGEN_INTERVAL) {
            return;
        }
        regenTimer = 0;
        heal((float) (REGEN_BASE * Physique.recovery(individual)));
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) {
            if (birthPos == null) {
                birthPos = blockPosition(); // 스폰 위치 확정(1세대 정착 기준)
            }
            if (lastForageSuccessTick == 0L) {
                lastForageSuccessTick = level().getGameTime(); // 미초기화(신규/구세이브) → 지금부터 계측
            }
            if (settledTick == 0L) {
                settledTick = level().getGameTime(); // 구 세이브 호환 — 로드 직후 즉시 이주 방지
            }
            if (!ledgerChecked && individual != null && level() instanceof ServerLevel sl0) {
                // 혈통 원장 등록 — 첫 서버 틱(출생·스폰·구세이브 로드 모두 이 경로 통과, register 멱등).
                // 무대 개체는 통계 오염 방지를 위해 제외(스폰 헬퍼가 addFreshEntity 전에 마킹).
                ledgerChecked = true;
                if (!stageActor) {
                    FamilyLedger.get(sl0).register(individual, level().getGameTime() / 24000L);
                }
            }
            if (!hearthRegistered) {
                hearthRegistered = true;
                // 리로드 복구: 켜진 모닥불 전역 목록(LIT_HEARTHS)은 휘발이라, 로드 첫 틱에 자기
                // 거처가 켜져 있으면 재등록 — 구혼 여행 목적지가 리로드 후 전멸하는 문제 방지.
                if (homePos != null && hearthLitAt(homePos)) {
                    hearthLit(homePos, true);
                }
            }
            growthTick();
            observeTooYoung();
            attractZombies();  // 근처 좀비가 미믹을 공격 대상으로 삼게 함
            mateTick();        // 구애 인식·후보 등록(노동/배회). 실제 구애 이동은 MimicCourtshipGoal
            buildTick();       // 거처 건축(짓는 연출) — 리더가 한 칸씩
            // 이주 중 업힌 유아: 어미가 새 거처 반경에 들면 내려줌(도착).
            if (getStage() == LifeStage.INFANT && isPassenger()
                    && getVehicle() instanceof MimicEntity carrier && carrier.isHome()) {
                stopRiding();
            }
            introTick();       // 관찰 로그: 개체 변수(성별·세대·특성) 1회 소개 — 유전 검증 근거
            hungerTick();      // 식량 v2: 개인 보유 연속 소모(활동·특성·부상 차등) + 아사 클럭
            familyTick();      // 식량 v2: 대표가 주기 정산(입금·급식·번식·베리) — 18000 의존 제거
            berryDemoTick();   // /evosim berry 실연: 심기→성장→수확을 실시간으로 진행
            infantCareTick();
            regenTick();       // 회복력(강건/병약) 등급 비례 재생(위험 없을 때)
        }
    }

    /**
     * 좀비 어그로 유도 — 인지 범위 내 <b>임자 없는</b> 좀비(타겟 없음/죽은 타겟)를 자신에게 붙인다.
     * 이미 살아있는 대상(다른 미믹·플레이어)을 노리는 좀비는 뺏지 않는다(좀비 한 마리=한 대상, 진동 방지).
     */
    private void attractZombies() {
        if ((level().getGameTime() + getId()) % 10 != 0) {
            return; // 스태거(부하 분산)
        }
        // 미믹은 바닐라 좀비의 자연 표적이 아니라 이 함수가 유일한 어그로 공급원이다.
        // 원거리 능동 유인은 전투 가능 단계(성년·노년)만 — 유아·소년이 20블록 밖 좀비를 스스로
        // 끌어와 죽던 결함 제거. 비전투 단계는 근접 조우(4)에서만 노려져 밤 위협 자체는 유지.
        // 조심성은 두 반경 모두 ×0.75(눈에 덜 띔 — 유일 공급원이라 실효 보장).
        double range = (SurvivalRules.canFight(getStage()) ? ZOMBIE_AGGRO_RANGE : ZOMBIE_CLOSE_AGGRO)
                * (individual != null ? Combat.aggroRangeMult(individual) : 1.0);
        for (Zombie z : level().getEntitiesOfClass(Zombie.class, getBoundingBox().inflate(range))) {
            var cur = z.getTarget();
            if (cur == null || !cur.isAlive()) {
                z.setTarget(this);
            }
        }
    }

    // ── 구애 상태머신 (구애 사양서 v2) ──

    /**
     * 인식·후보 등록 (§2 SEARCHING). 방랑자는 노동·배회 시간에 인식 범위(신중도 비례)의 이성을 후보로
     * 모은다 — 실제 구애 시도(이동·요청)는 배회 시간의 {@link MimicCourtshipGoal}가 한다.
     */
    private void mateTick() {
        // 구혼 여행 만료 잔재 정리: 빈손 귀환인데 그 사이 가족이 이주해 고향이 폐가(모닥불 꺼짐)면
        // 폐가에 홀로 좌초되지 않게 방랑자로 전환(구애 풀 합류·재정착 경로).
        // 성년 게이트보다 먼저 — 여행 중 노년 전이돼도 잔재가 남지 않게.
        if (courtTravelTarget != 0L && level().getGameTime() >= courtTravelUntil) {
            endCourtTravel();
            if (homePos != null && !hearthLitAt(homePos)) {
                BlockPos gone = homePos;
                setHomePos(null);
                SimEvents.event(this, "구혼여행", String.format(
                        "빈손 만료 — 고향 @%d,%d 은 폐가 → 방랑 전환", gone.getX(), gone.getZ()));
            }
        }
        // 사별 감지: 배우자가 살아있지 않으면 과부/홀아비 → 재구애 참여.
        // 일부다처 승계: 본처가 죽어도 같은 거처에 다른 아내가 있으면 그쪽으로 재링크(홀아비 아님).
        // 성년 게이트보다 앞 — 노년도 사별·승계는 감지(포지션·표시 정확성). 재구애는 여전히
        // 성년 전용(isSingleAdult 가 ADULT 한정 + 아래 게이트가 노년 mateState 를 IDLE 로).
        if (individual != null && spouseId != 0L && !widowed
                && (level().getGameTime() + getId()) % 40 == 0) {
            if (!spouseAlive()) {
                MimicEntity nextWife = null;
                if (!isFemale() && individual != null) {
                    for (MimicEntity m : householdMembers()) {
                        if (m != this && m.isFemale() && m.isAlive()
                                && m.getIndividual() != null && m.spouseId == individual.id()
                                && (m.getStage() == LifeStage.ADULT
                                        || m.getStage() == LifeStage.ELDER)) {
                            nextWife = m; // 노년 둘째 부인도 승계 대상(아내 포지션 유지)
                            break;
                        }
                    }
                }
                if (nextWife != null) {
                    spouseId = nextWife.getIndividual().id(); // 승계 — 재구애 없이 혼인 유지
                    SimEvents.event(this, "승계", "본처 사망 → 둘째 부인 #" + nextWife.getId() + " 승계");
                } else {
                    widowed = true;
                    mateState = MateState.SEARCHING; // 노년이면 바로 아래 게이트가 IDLE 로 되돌림
                    clearCourtshipPool(); // 재구애는 백지에서 — 결혼 전 거절 기록이 재혼을 막지 않게
                }
            }
        }
        if (individual == null || getStage() != LifeStage.ADULT) {
            mateState = MateState.IDLE;
            return; // 구애는 성년 전용 — 노년은 위 정리·사별 감지까지만
        }
        if (building) {
            return; // 건축 중엔 구애 안 함
        }
        if (!isSingleAdult()) {
            mateState = MateState.PAIRED;
            return;
        }
        Schedule.Phase phase = Schedule.phaseAt(individual, level().getDayTime());
        boolean active = StageObserver.isActive()
                || phase == Schedule.Phase.WORK || phase == Schedule.Phase.WANDER;
        // 진단(무대 개체 한정, 2초 간격) — 구혼여행이 왜 출발 안 하는지 결정 상태를 그대로 기록.
        // 실게임엔 무대 개체가 없어 무영향. 원인 규명 후 제거.
        if (isStageActor() && (level().getGameTime() + getId()) % 40 == 0) {
            SimEvents.event(this, "구애진단", String.format(
                    "phase=%s active=%s single=%s cands=%d lonely=%s travel=%s hearth=%s",
                    phase, active ? "Y" : "N", isSingleAdult() ? "Y" : "N", candidates.size(),
                    lonelySinceTick < 0L ? "reset"
                            : (level().getGameTime() - lonelySinceTick) + "t/" + Famine.LONELY_TRAVEL_AFTER,
                    isCourtTravel() ? "Y" : "N",
                    nearestForeignHearth() == 0L ? "NONE" : "OK"));
        }
        if (active) {
            perceive(phase);
            searchTimer++;
            if (mateState == MateState.IDLE || mateState == MateState.PAIRED) {
                mateState = MateState.SEARCHING;
            }
            // 족외혼(이주 설계 §3-B): 비근친 후보 0명이 오래가면 타향 모닥불로 구혼 여행.
            if (candidates.isEmpty() && homePos != null && !isCourtTravel()) {
                if (lonelySinceTick < 0L) {
                    lonelySinceTick = level().getGameTime();
                } else if (level().getGameTime() - lonelySinceTick > Famine.LONELY_TRAVEL_AFTER) {
                    startCourtTravel();
                }
            } else if (!candidates.isEmpty()) {
                lonelySinceTick = -1L;
            }
        }
    }

    /** 구혼 여행 시작 — 가장 가까운 타향 모닥불을 임시 앵커로(리시가 그 마을까지 끌고 간다). */
    private void startCourtTravel() {
        long target = nearestForeignHearth();
        if (target == 0L) {
            // 알려진 타향 없음 — 외로움 클럭을 통째로 되감지 않고 반나절 뒤 재시도 지점으로만 되감기
            // (여기서 -1로 리셋하면 실패할 때마다 3일을 다시 다 기다리는 버그).
            lonelySinceTick = level().getGameTime() - Famine.LONELY_TRAVEL_AFTER + 12000L;
            return;
        }
        lonelySinceTick = -1L;
        courtTravelTarget = target;
        courtTravelUntil = level().getGameTime() + Famine.TRAVEL_DURATION;
        BlockPos t = BlockPos.of(target);
        SimEvents.event(this, "구혼여행", String.format("비근친 후보 0 지속 → 타향 모닥불 @%d,%d 로 출발",
                t.getX(), t.getZ()));
    }

    /** 구혼 여행 중인가 — 여행 중엔 리시 앵커가 타향 모닥불, 귀가·취침 goal은 물러난다. */
    public boolean isCourtTravel() {
        return courtTravelTarget != 0L && level().getGameTime() < courtTravelUntil;
    }

    private void endCourtTravel() {
        courtTravelTarget = 0L;
        courtTravelUntil = 0L;
        lonelySinceTick = -1L;
    }

    // 켜진 모닥불 전역 목록(구혼 여행 목적지) — ABANDONED_HOMES처럼 휘발성(재점화 이벤트로 복구).
    private static final List<Long> LIT_HEARTHS = new ArrayList<>();

    private static void hearthLit(BlockPos home, boolean lit) {
        Long key = home.asLong();
        LIT_HEARTHS.remove(key);
        if (lit) {
            LIT_HEARTHS.add(key);
            if (LIT_HEARTHS.size() > 64) {
                LIT_HEARTHS.remove(0); // 가지치기(오래된 항목부터)
            }
        }
    }

    /** 이 거처의 모닥불이 켜져 있나(블록 실측 — 폐가 판정). */
    private boolean hearthLitAt(BlockPos home) {
        BlockPos hp = HomeStructure.hearthPos(home, getHomeFacingDir());
        var st = level().getBlockState(hp);
        return st.getBlock() instanceof MimicHearthBlock && st.getValue(MimicHearthBlock.LIT);
    }

    /** 자기 마을(반경 48) 밖에서 가장 가까운 켜진 모닥불. 없으면 0. */
    private long nearestForeignHearth() {
        long best = 0L;
        double bestD = Double.MAX_VALUE;
        for (long h : LIT_HEARTHS) {
            BlockPos p = BlockPos.of(h);
            if (homePos != null && p.distSqr(homePos) < 48.0 * 48.0) {
                continue; // 자기 마을권 — 이미 인지 범위에서 찾아봤음
            }
            double d = p.distSqr(blockPosition());
            if (d < bestD && d < 512.0 * 512.0) {
                bestD = d;
                best = h;
            }
        }
        return best;
    }

    /** 인식 범위 내 이성 독신 성년(비근친·비거절)을 후보로 추가하고 매력 내림차순 유지. */
    private void perceive(Schedule.Phase phase) {
        int lvl = individual.mateChoice().ordinal();
        double range = phase == Schedule.Phase.WORK
                ? WORK_PERCEPT_BASE + lvl * WORK_PERCEPT_PER
                : WANDER_PERCEPT_BASE + lvl * WANDER_PERCEPT_PER;
        // 부유선호일 때만, 구획 계정을 소유주별로 1회 집계(후보마다 전 구획 순회 O(후보×구획) 회피).
        Map<Long, Double> accountByOwner = null;
        if (ExpressionResolver.isExpressed(individual, Trait.PREF_WEALTH)
                && level() instanceof ServerLevel psl) {
            accountByOwner = new HashMap<>();
            for (FarmStore.Plot p : FarmStore.get(psl).all().values()) {
                accountByOwner.merge(p.ownerId, p.account, Double::sum);
            }
        }
        for (MimicEntity m : level().getEntitiesOfClass(MimicEntity.class, getBoundingBox().inflate(range))) {
            if (m == this || m.getIndividual() == null || m.isFemale() == isFemale()) {
                continue;
            }
            // 일부다처: 여성은 기혼 성년 남성도 잠재 짝으로 고려(감점 등록). 남성 인식은 독신만(그대로).
            boolean marriedMale = isFemale() && !m.isFemale() && !m.isSingleAdult()
                    && m.getStage() == LifeStage.ADULT && !m.isBuilding();
            if (!m.isSingleAdult() && !marriedMale) {
                continue;
            }
            int id = m.getId();
            if (candidateCharm.containsKey(id)
                    || approachRetryAt.getOrDefault(id, 0L) > level().getGameTime()) {
                continue; // 이미 후보 / 쿨다운 중(거절 1일·접근 실패 2400틱)
            }
            if (Kinship.isRelated(individual, m.getIndividual())) {
                continue; // 근친 회피 §13-E
            }
            int charm = Multipliers.charmScore(individual, m.getIndividual())
                    - (marriedMale ? Polygyny.MARRIED_CHARM_PENALTY : 0); // 기혼 감점 — 독신 우선
            if (accountByOwner != null && level() instanceof ServerLevel sl) {
                // 부유선호 — 상대의 잉여(저장고+밭 계정)를 매력으로. 부유한 기혼 지주는 감점 −2를
                // 가점(최대 +3)으로 자연 상쇄 — 인위적 감점 해제 없이 부가 매력이 되는 경로.
                double w = m.getHomePos() == null ? 0.0 : LarderStore.get(sl).get(m.getHomePos());
                w += accountByOwner.getOrDefault(m.getIndividual().id(), 0.0);
                charm += Multipliers.wealthCharm(w, FoodEconomy.consumptionPerDay(
                        m.getStage(), Activity.MOVE, m.getIndividual(), false));
            }
            candidateCharm.put(id, charm);
            candidates.add(id);
        }
        candidates.sort((x, y) -> Integer.compare(
                candidateCharm.getOrDefault(y, 0), candidateCharm.getOrDefault(x, 0)));
    }

    /**
     * 구애를 받았을 때의 수락 판정 (§3). PAIRED면 자동 거절, 내가 이 상대를 구애 중이면 상호구애로 즉시
     * 성사, 아니면 베이지안 확률로 판정. 성사 시 원자적으로 짝을 맺고 양쪽에 기록을 남긴다.
     */
    public boolean receiveCourtship(MimicEntity suitor) {
        Individual si = suitor.getIndividual();
        if (individual == null || si == null) {
            return false;
        }
        if (!isSingleAdult() || building) {
            // 일부다처: 기혼 성년 남성이 독신 여성의 구애를 받으면 게이트(상한·아내 질투·부양 증명)
            // 전부 통과 시 기본 수락 — 통과 못 하면 기존처럼 자동 거절.
            if (!building && !isFemale() && getStage() == LifeStage.ADULT
                    && suitor.isFemale() && suitor.isSingleAdult()
                    && homePos != null && level() instanceof ServerLevel sl) {
                double larder = LarderStore.get(sl).get(homePos);
                if (Polygyny.canAccept(currentWives(), larder, liveFamilyNeed(), individual)) {
                    logCourt(suitor, true, Multipliers.charmScore(individual, si), 1,
                            Math.max(1, candidates.size()), 100);
                    marrySecond(suitor);
                    return true;
                }
                SimEvents.event(suitor, "구애",
                        "중혼 거절 — 아내 질투(부유층 아님)/부양 미달 중 하나");
            }
            return false; // 이미 짝 있음/건축 중 → 자동 거절
        }
        // 상호구애 특례: 내가 이 상대를 구애 중이면 판정 없이 성사.
        if (mateState == MateState.COURTING && courtTargetId == suitor.getId()) {
            logCourt(suitor, true, Multipliers.charmScore(individual, si), 1, candidates.size(), 100);
            pairWith(suitor);
            return true;
        }
        pruneCandidates(); // 판정 직전 유령 경쟁자(사망·기혼 전이) 제거 — n·better 부풀림에 의한 부당 거절 방지
        int charm = Multipliers.charmScore(individual, si);
        int n = candidates.size();
        int better = 0;
        for (int c : candidateCharm.values()) {
            if (c > charm) {
                better++;
            }
        }
        // 눈낮춤(§10 멸종 방지): 거절을 발행할수록 유효 k(까다로움)가 내려가 수락률이 점진 상승 —
        // 까다로운 개체·척박한 후보군도 결국 맺어진다. 성혼 시 리셋. NBT 영속.
        int k = Math.max(0, individual.mateChoice().k() - rejectionsGiven);
        double p = Courtship.acceptChance(better, n, k); // 밸런싱 스케일 적용값(GUI %도 이 값)
        boolean accept = getRandom().nextDouble() < p;
        logCourt(suitor, accept, charm, better + 1, n, (int) Math.round(p * 100));
        if (accept) {
            pairWith(suitor);
            return true;
        }
        rejectionsGiven++;
        return false;
    }

    /** 후보 풀에서 무효(사망·소멸·비자격) 항목 제거 — perceive 의 등록 자격과 동일 기준. */
    private void pruneCandidates() {
        candidates.removeIf(id -> {
            var e = level().getEntity(id);
            boolean valid = e instanceof MimicEntity mm && mm.isAlive() && mm.getIndividual() != null
                    && (mm.isSingleAdult()
                        || (isFemale() && !mm.isFemale() && mm.getStage() == LifeStage.ADULT
                            && !mm.isBuilding())); // 일부다처: 여성의 기혼남 후보는 유지
            if (!valid) {
                candidateCharm.remove(id);
            }
            return !valid;
        });
    }

    /** 수락/거절을 양쪽 기록에 남긴다 (내 RECEIVED + 구애자 COURTED) + 관찰 로그(판정 수치 포함). */
    private void logCourt(MimicEntity suitor, boolean accepted, int charm, int rank, int pool, int percent) {
        long now = level().getDayTime();
        addCourtLog(new CourtRecord(now, CourtRecord.Kind.RECEIVED, suitor.getId(),
                "미믹#" + suitor.getId(), accepted, charm, rank, pool, percent));
        suitor.addCourtLog(new CourtRecord(now, CourtRecord.Kind.COURTED, getId(),
                "미믹#" + getId(), accepted, charm, rank, pool, percent));
        SimEvents.event(suitor, "구애", String.format("%s — 상대 #%d · 매력%d 순위%d/%d 확률%d%%",
                accepted ? "성사" : "거절", getId(), charm, rank, pool, percent));
    }

    /** 실시간 가족 하루소모 — 중혼 부양 증명 게이트용(가족틱 캐시 부패로 인한 과소·과대 판정 방지). */
    private double liveFamilyNeed() {
        List<FoodEconomy.Eater> eaters = new ArrayList<>();
        for (MimicEntity m : householdMembers()) {
            if (m.getIndividual() != null) {
                eaters.add(new FoodEconomy.Eater(m.getIndividual(), m.getStage(), m.holding, m.isHome()));
            }
        }
        return eaters.isEmpty() ? cachedFamilyNeed : FoodEconomy.nominalDailyNeed(eaters);
    }

    /** 현재 아내들(같은 거처·배우자 링크가 나를 가리키는 성년·노년 여성) — 일부다처 게이트 입력.
     *  노년 아내 포함 — 나이 들었다고 아내 수·질투 게이트에서 빠지면 상한·용인 심사가 뚫린다. */
    private List<Individual> currentWives() {
        List<Individual> wives = new ArrayList<>();
        if (individual == null) {
            return wives;
        }
        for (MimicEntity m : householdMembers()) {
            if (m != this && m.isFemale() && m.getIndividual() != null
                    && m.spouseId == individual.id()
                    && (m.getStage() == LifeStage.ADULT || m.getStage() == LifeStage.ELDER)) {
                wives.add(m.getIndividual());
            }
        }
        return wives;
    }

    /** 둘째 부인 합류 — 신부만 링크 갱신(남편 spouseId는 본처 유지), 거처 합류는 기존 moveInto 재사용. */
    private void marrySecond(MimicEntity bride) {
        bride.setSpouse(individual.id());
        bride.setMateState(MateState.PAIRED);
        bride.setCourtTargetId(-1);
        bride.endCourtTravel();
        heartEffect(this);
        heartEffect(bride);
        if (level() instanceof ServerLevel sl) {
            moveInto(sl, this, bride); // 홀거처주였으면 그 집은 폐가화(기존 규칙)
        }
        bride.clearCourtshipPool(); // 성혼 정리(pairWith와 동일)
        bride.rejectionsGiven = 0;
        StageObserver.record(getId(), "mating:polygyny");
        SimEvents.event(this, "중혼", "둘째 부인 #" + bride.getId()
                + " 합류 — 아내 용인·저장고 부양 증명 통과 (아내 " + (currentWives().size()) + "명)");
    }

    /** 짝 성사 — 배우자 링크 + 거처 귀속(재혼/분가/신축) 결정. */
    private void pairWith(MimicEntity other) {
        spouseId = other.getIndividual().id();
        widowed = false;
        other.setSpouse(getIndividual().id());
        mateState = MateState.PAIRED;
        other.setMateState(MateState.PAIRED);
        courtTargetId = -1;
        other.setCourtTargetId(-1);
        endCourtTravel();       // 구혼 여행 목적 달성 — 정착은 아래 resolveHome이 처리
        other.endCourtTravel();
        heartEffect(this);
        heartEffect(other);
        if (level() instanceof ServerLevel sl) {
            resolveHome(sl, other);
        }
        StageObserver.record(getId(), "mating:pair");
        SimEvents.event(this, "짝성립", "상대 #" + other.getId());
        clearCourtshipPool();       // 성혼 — 낡은 후보·거절 기록 정리(사망까지 잔존하던 메모리·stale 캐시)
        other.clearCourtshipPool();
        rejectionsGiven = 0;        // 눈낮춤 리셋 — 짝을 찾았으니 기준 원복
        other.rejectionsGiven = 0;
    }

    /** MateHome 규칙대로 거처 귀속: 새집(재활용/신축) / 한쪽 거처로 이주 / 둘다혼자→랜덤 합류. */
    private void resolveHome(ServerLevel sl, MimicEntity other) {
        switch (MateHome.resolve(homeStatus(), other.homeStatus())) {
            case USE_A -> moveInto(sl, this, other);
            case USE_B -> moveInto(sl, other, this);
            case KEEP_ONE -> {
                MimicEntity keep = getRandom().nextBoolean() ? this : other;
                moveInto(sl, keep, keep == this ? other : this);
            }
            case NEW -> makeNewHome(sl, other);
        }
    }

    /** guest를 host의 거처로 입주. guest가 단독 거처주였다면 그 집은 폐기(모닥불 끔) — 그 집의 미성년 자식(과부 재혼 등)도 함께 데려온다(좌초 방지). */
    private static void moveInto(ServerLevel sl, MimicEntity host, MimicEntity guest) {
        BlockPos guestOldHome = guest.getHomePos();
        boolean wasLoneOwner = guestOldHome != null
                && guest.homeStatus() == MateHome.Status.LONE_OWNER;
        if (wasLoneOwner) {
            guest.abandonHome(sl); // 원래 단독 거처 폐기
        }
        guest.setHomePos(host.getHomePos());
        guest.homeFacing = host.homeFacing;
        if (wasLoneOwner && guest.getIndividual() != null) {
            long gid = guest.getIndividual().id();
            for (MimicEntity c : sl.getEntitiesOfClass(MimicEntity.class,
                    new net.minecraft.world.phys.AABB(guestOldHome).inflate(48.0))) {
                if (c.isAlive() && c.getIndividual() != null
                        && (c.getStage() == LifeStage.INFANT || c.getStage() == LifeStage.BOY)
                        && guestOldHome.equals(c.getHomePos())
                        && (c.getIndividual().parentAId() == gid
                                || c.getIndividual().parentBId() == gid)) {
                    c.setHomePos(host.getHomePos());
                    c.homeFacing = host.homeFacing;
                }
            }
        }
        relightHearth(sl, host.getHomePos(), host.getHomeFacingDir());
        SimEvents.event(guest, "합류", String.format("#%d 거처로 입주 @%d,%d",
                host.getId(), host.getHomePos().getX(), host.getHomePos().getZ()));
    }

    /**
     * 새 거처 마련 — 정착 성향(이주자/애향심/기본) 조합으로 결정(설계서 §13-D, {@link HomeResolution}).
     * 애향심은 빈 거처(꺼진 모닥불)를 확률적으로 먼저 노리고, 이주자는 반드시 신축한다. 신축 위치·거리도
     * 조합에 따라 정한다(기본=짝 성사 자리, 기본+애향=애향 보유자 고향, 애향+애향=두 거처 중간).
     */
    private void makeNewHome(ServerLevel sl, MimicEntity other) {
        HomeResolution.Disposition da = HomeResolution.dispositionOf(individual);
        HomeResolution.Disposition db = HomeResolution.dispositionOf(other.getIndividual());
        HomeResolution.Plan plan = HomeResolution.plan(da, db);

        // ① 애향심: 확률적으로 빈 거처 재사용 시도. 이주자는 emptyPercent=0 → 항상 신축.
        if (plan.emptyPercent() > 0 && getRandom().nextInt(100) < plan.emptyPercent()) {
            int[] reuse = findAbandonedHome(sl);
            if (reuse != null) {
                occupyAbandoned(sl, other, reuse);
                return;
            }
        }

        // ② 신축 — 조합별 앵커(기준점)와 거리로 자리를 잡고 짓는 연출 시작.
        int[] anchor = buildAnchor(other, da, plan.anchor());
        List<int[]> existing = collectExistingHomes(sl, anchor[0], anchor[2]);
        DeterministicRng rng = new DeterministicRng(getRandom().nextLong());
        int[] pos = Settlement.placeHome(new int[] {anchor[0], anchor[2]}, plan.distance(),
                existing, Settlement.MIN_GAP, rng);
        Direction facing = Direction.from2DDataValue(getRandom().nextInt(4));
        // 기단 높이 = 발자국 지형 '최고점'에 맞춤(파묻힘·공중부양 방지). 그보다 낮은 칸은 흙으로 메운다.
        BlockPos site = new BlockPos(pos[0], anchor[1], pos[1]);
        int baseY = terrainBaseY(sl, site, facing);
        BlockPos home = new BlockPos(pos[0], baseY, pos[1]);

        setHomePos(home);
        other.setHomePos(home);
        homeFacing = (byte) facing.get2DDataValue();
        other.homeFacing = homeFacing;
        flattenSite(sl, home, facing); // 하단 평탄화 — 낮은 칸 흙 메움 + 흙 효과음(하단 전부 접지)
        // 둘 다 건축 상태(부지로 이동·완성까지 구애/채집 정지). 실제 분담·리더는 buildTick 이 매 틱 결정.
        this.building = true;
        this.buildReachTicks = 0;
        other.building = true;
        other.buildReachTicks = 0;
        SimEvents.event(this, "건축", String.format("신축 시작 @%d,%d 방향=%s (배우자 #%d) 성향 %s×%s",
                home.getX(), home.getZ(), facing, other.getId(), da, db));
    }

    /** 빈 거처(꺼진 모닥불)에 부부가 입주 — 모닥불 재점화. */
    private void occupyAbandoned(ServerLevel sl, MimicEntity other, int[] reuse) {
        BlockPos home = new BlockPos(reuse[0], reuse[1], reuse[2]);
        Direction facing = Direction.from2DDataValue(reuse[3]);
        setHomePos(home);
        other.setHomePos(home);
        homeFacing = (byte) reuse[3];
        other.homeFacing = homeFacing;
        relightHearth(sl, home, facing);
        SimEvents.event(this, "입주", "빈 거처 재사용 @" + home.getX() + "," + home.getZ()
                + " (상대 #" + other.getId() + ")");
    }

    /** 신축 위치 기준점 {x,y,z} — 조합별 앵커(짝 성사 자리 / 애향 보유자 고향 / 두 거처 중간). */
    private int[] buildAnchor(MimicEntity other, HomeResolution.Disposition da,
                              HomeResolution.Anchor anchor) {
        switch (anchor) {
            case HOMER_BIRTH -> {
                MimicEntity homer = da == HomeResolution.Disposition.HOMER ? this : other;
                BlockPos b = homer.getBirthPos();
                return new int[] {b.getX(), b.getY(), b.getZ()};
            }
            case MIDPOINT_HOMES -> {
                BlockPos ha = homeOrBirth(this);
                BlockPos hb = homeOrBirth(other);
                return new int[] {(ha.getX() + hb.getX()) / 2, (ha.getY() + hb.getY()) / 2,
                        (ha.getZ() + hb.getZ()) / 2};
            }
            default -> { // MATING_SPOT
                BlockPos p = blockPosition();
                return new int[] {p.getX(), p.getY(), p.getZ()};
            }
        }
    }

    /** 가족 거처가 있으면 그 좌표, 없으면(방랑자) 태어난 위치. */
    private static BlockPos homeOrBirth(MimicEntity m) {
        return m.getHomePos() != null ? m.getHomePos() : m.getBirthPos();
    }

    /** 주변(그리고 폐기목록) 거처 좌표(x,z) — 겹침 회피용. */
    /** 앵커(실제 배치 기준점) 중심으로 수집 — 개체 중심 수집은 이주 거리(이주자 128~192)가 스캔을
     *  넘어서면 목적지 주변 남의 집을 못 보고 겹쳐 짓던 결함(M-3). */
    private List<int[]> collectExistingHomes(ServerLevel sl, int anchorX, int anchorZ) {
        List<int[]> existing = new ArrayList<>();
        var box = new net.minecraft.world.phys.AABB(
                new BlockPos(anchorX, blockPosition().getY(), anchorZ)).inflate(160.0);
        for (MimicEntity m : sl.getEntitiesOfClass(MimicEntity.class, box)) {
            BlockPos h = m.getHomePos();
            if (h != null) {
                existing.add(new int[] {h.getX(), h.getZ()});
            }
        }
        for (int[] a : ABANDONED_HOMES) {
            existing.add(new int[] {a[0], a[2]});
        }
        return existing;
    }

    // 세션 내 폐기(꺼진) 거처 목록 {x,y,z,facing2d} — 재활용용(리로드엔 사라짐, 무해).
    private static final List<int[]> ABANDONED_HOMES = new ArrayList<>();

    /** 내 거처를 폐기 — 모닥불 끄고 폐기목록 등록(건물은 폐허로 남음). */
    private void abandonHome(ServerLevel sl) {
        if (homePos == null) {
            return;
        }
        Direction facing = getHomeFacingDir();
        BlockPos hp = HomeStructure.hearthPos(homePos, facing);
        if (sl.getBlockState(hp).getBlock() instanceof MimicHearthBlock) {
            sl.setBlockAndUpdate(hp, sl.getBlockState(hp)
                    .setValue(MimicHearthBlock.LIT, Boolean.FALSE));
            hearthLit(homePos, false);
            ABANDONED_HOMES.add(new int[] {homePos.getX(), homePos.getY(), homePos.getZ(),
                    facing.get2DDataValue()});
            SimEvents.event(this, "폐가", String.format("모닥불 끔 @%d,%d (저장고는 남아 재사용 시 계승)",
                    homePos.getX(), homePos.getZ()));
        }
    }

    /** 폐기목록에서 근처 빈 거처 하나 찾아 반환(거주자 없고 모닥불 꺼짐 확인). 없으면 null. */
    private int[] findAbandonedHome(ServerLevel sl) {
        for (int i = 0; i < ABANDONED_HOMES.size(); i++) {
            int[] a = ABANDONED_HOMES.get(i);
            BlockPos home = new BlockPos(a[0], a[1], a[2]);
            if (home.distSqr(blockPosition()) > 96.0 * 96.0) {
                continue;
            }
            BlockPos hp = HomeStructure.hearthPos(home, Direction.from2DDataValue(a[3]));
            var st = sl.getBlockState(hp);
            if (!(st.getBlock() instanceof MimicHearthBlock)) {
                LarderStore.get(sl).remove(home); // 구조 자체가 소멸한 집 — 고아 저장고 함께 청소(M-9)
                ABANDONED_HOMES.remove(i);
                i--;         // 무효 항목 정리 후 계속 탐색 — 첫 꽝에서 포기해 뒤의 유효 폐가를
                continue;    // 놓치고 신축하던(애향심 재사용 설계 훼손) 결함 수정
            }
            if (st.getValue(MimicHearthBlock.LIT)) {
                ABANDONED_HOMES.remove(i);
                i--;         // 재점화 = 새 가족 거주 중 — 목록에서만 빼고 저장고는 그 가족 것(유지)
                continue;
            }
            if (anyResidentAt(sl, home)) {
                continue;
            }
            ABANDONED_HOMES.remove(i);
            return a;
        }
        return null;
    }

    private static boolean anyResidentAt(ServerLevel sl, BlockPos home) {
        for (MimicEntity m : sl.getEntitiesOfClass(MimicEntity.class,
                new net.minecraft.world.phys.AABB(home).inflate(48.0))) {
            if (m.isAlive() && home.equals(m.getHomePos())) {
                return true;
            }
        }
        return false;
    }

    private static final int MAX_FLATTEN = 16; // 메움·파냄 최대 깊이(협곡 폭주 방지)
    private static final net.minecraft.world.level.levelgen.Heightmap.Types SURFACE_MAP =
            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;

    /**
     * 기단 Y = 발자국 지표들의 <b>중앙값</b>(median) + 1. 중앙값에 맞추면 낮은 칸 메움량 + 높은 칸 파냄량의
     * 총합이 최소가 된다(L1 최소화점=median) → 흙을 가장 덜 쓰고 덜 파는 방향.
     */
    private static int terrainBaseY(ServerLevel sl, BlockPos site, Direction facing) {
        List<BlockPos> foot = HomeStructure.footprint(site, facing);
        int[] surf = new int[foot.size()];
        for (int i = 0; i < foot.size(); i++) {
            surf[i] = sl.getHeight(SURFACE_MAP, foot.get(i).getX(), foot.get(i).getZ()) - 1; // 지표 블록 Y
        }
        java.util.Arrays.sort(surf);
        return surf[surf.length / 2] + 1; // 하단 딛는 레벨(기단-1)=중앙값 지표
    }

    /**
     * 하단 평탄화 — 기단보다 낮은 칸은 흙으로 <b>메우고</b>, 높은 칸은 구조가 파묻히지 않게 <b>파낸다</b>.
     * 기단이 중앙값이라 메움·파냄 총량이 최소다. 하단이 전부 지면에 닿고(공중부양 방지) 벽이 흙에 묻히지
     * 않는다(매립 방지). 파냄은 destroyBlock 이 흙 파괴음·파티클을, 메움은 별도 흙 효과음을 낸다.
     */
    private void flattenSite(ServerLevel sl, BlockPos home, Direction facing) {
        int target = home.getY() - 1; // 하단이 딛어야 할 지면 레벨
        boolean played = false;
        for (BlockPos col : HomeStructure.footprint(home, facing)) {
            int surface = sl.getHeight(SURFACE_MAP, col.getX(), col.getZ()) - 1;
            if (surface < target) {
                for (int y = Math.max(surface + 1, target - MAX_FLATTEN); y <= target; y++) {
                    BlockPos p = new BlockPos(col.getX(), y, col.getZ());
                    if (isFillable(sl.getBlockState(p))) {
                        sl.setBlockAndUpdate(p, Blocks.DIRT.defaultBlockState());
                        if (!played) {
                            playDirtSound(sl, p);
                            played = true;
                        }
                    }
                }
            } else if (surface > target) {
                for (int y = target + 1; y <= Math.min(surface, target + 1 + MAX_FLATTEN); y++) {
                    BlockPos p = new BlockPos(col.getX(), y, col.getZ());
                    if (isDiggable(sl.getBlockState(p))) {
                        sl.destroyBlock(p, false); // 드랍 없음 + 흙 파괴음·파티클 자동
                    }
                }
            }
        }
    }

    /** 흙으로 메울 수 있는 칸인가(빈 칸·유체·교체 가능 초목 — 기존 지면은 건드리지 않음).
     *  꽃 한 송이가 기단 메움을 막아 구멍이 나던 결함 — 설치 판정과 같은 초목 목록 공유. */
    private static boolean isFillable(net.minecraft.world.level.block.state.BlockState st) {
        return st.isAir() || !st.getFluidState().isEmpty() || isClearableVegetation(st);
    }

    /** 파낼 수 있는 칸인가(자연 지형만 — 구조 블록·모닥불·기반암은 보호). */
    private static boolean isDiggable(net.minecraft.world.level.block.state.BlockState st) {
        if (st.isAir() || !st.getFluidState().isEmpty()) {
            return false;
        }
        if (st.is(Blocks.WHITE_WOOL) || st.is(Blocks.OAK_FENCE) || st.is(Blocks.BEDROCK)) {
            return false;
        }
        return !(st.getBlock() instanceof MimicHearthBlock);
    }

    /** 흙 파괴(정지 작업) 효과음. */
    private static void playDirtSound(ServerLevel sl, BlockPos p) {
        SoundType st = Blocks.DIRT.defaultBlockState().getSoundType();
        sl.playSound(null, p, st.getBreakSound(), SoundSource.BLOCKS,
                (st.getVolume() + 1.0F) / 2.0F, st.getPitch() * 0.8F);
    }

    // ── 옆 베리 정원 (거처 좌우 x=±3 · 8칸, 평탄화로 자리 확보됨) ──
    private static final int BERRY_CAP = 8; // 거처당 베리 상한

    /** 옆 정원 8칸에 베리를 {@code maxCount}그루까지 심는다(빈 자리·심을 지면인 곳만). 심은 수 반환. */
    public int plantBerries(ServerLevel sl, int maxCount) {
        if (homePos == null || maxCount <= 0) {
            return 0;
        }
        int planted = 0;
        for (BlockPos tile : HomeStructure.berryTiles(homePos, getHomeFacingDir())) {
            if (planted >= maxCount) {
                break;
            }
            if (tryPlantBerry(sl, tile, 1)) {
                planted++;
            }
        }
        return planted;
    }

    /** 베리 한 그루를 tile 자리(주변 지면 위)에 age 로 심는다. 성공 시 true. */
    private boolean tryPlantBerry(ServerLevel sl, BlockPos tile, int age) {
        BlockPos ground = findBerryGround(sl, tile);
        if (ground == null) {
            return false;
        }
        BlockPos spot = ground.above();
        BlockState occ = sl.getBlockState(spot);
        if (!(occ.isAir() || occ.is(Blocks.GRASS) || occ.is(Blocks.TALL_GRASS) || occ.is(Blocks.FERN))) {
            return false; // 자리 비어 있어야
        }
        sl.setBlockAndUpdate(spot, Blocks.SWEET_BERRY_BUSH.defaultBlockState()
                .setValue(SweetBerryBushBlock.AGE, age));
        return true;
    }

    /** tile 근처(±)에서 베리를 심을 수 있는 지면 블록을 찾는다. 없으면 null. */
    private static BlockPos findBerryGround(ServerLevel sl, BlockPos tile) {
        for (int dy = 2; dy >= -4; dy--) {
            BlockPos p = tile.offset(0, dy, 0);
            if (canPlaceBerryOn(sl.getBlockState(p))) {
                return p;
            }
        }
        return null;
    }

    private static boolean canPlaceBerryOn(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT)
                || s.is(Blocks.PODZOL) || s.is(Blocks.FARMLAND);
    }

    /**
     * 점검용 — 옆 정원의 베리 덤불을 전부 제거하고 제거 수 반환. 검증 스텝이 같은 자리에서
     * 재실행될 때 이전 실행의 잔재가 "심은 수 = 현재 그루" 회계 대조를 깨는 것을 막는다
     * (탐지 범위는 countBerries 와 동일한 타일·dy).
     */
    public int debugClearBerries(ServerLevel sl) {
        if (homePos == null) {
            return 0;
        }
        int cleared = 0;
        for (BlockPos tile : HomeStructure.berryTiles(homePos, getHomeFacingDir())) {
            for (int dy = 3; dy >= -3; dy--) {
                BlockPos p = tile.offset(0, dy, 0);
                if (sl.getBlockState(p).is(Blocks.SWEET_BERRY_BUSH)) {
                    sl.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                    cleared++;
                }
            }
        }
        return cleared;
    }

    /** 거처 옆 정원의 베리 그루 수. */
    public int countBerries(ServerLevel sl) {
        if (homePos == null) {
            return 0;
        }
        int c = 0;
        for (BlockPos tile : HomeStructure.berryTiles(homePos, getHomeFacingDir())) {
            for (int dy = 3; dy >= -3; dy--) {
                if (sl.getBlockState(tile.offset(0, dy, 0)).is(Blocks.SWEET_BERRY_BUSH)) {
                    c++;
                    break;
                }
            }
        }
        return c;
    }

    // ── /evosim berry 실연(實演): 아무것도 미리 깔지 않고, 심기→성장→수확을 실시간으로 보여준다 ──
    private int berryDemoTicks = 0;

    /** 실연 시작 — 잠시 후 1회 정산(번식+잉여로 베리 심기), 이어 심은 베리를 빠르게 익혀 수확까지 관찰. */
    public void startBerryDemo() {
        berryDemoTicks = 600; // 약 30초
    }

    public boolean isBerryDemo() {
        return berryDemoTicks > 0;
    }

    /** 실연 진행: (2초 후) 잉여 공급→정산 1회로 베리 심기 → 이후 심은 베리를 15틱마다 한 그루씩 빠르게 익힘. */
    private void berryDemoTick() {
        if (berryDemoTicks <= 0 || !(level() instanceof ServerLevel sl)) {
            return;
        }
        berryDemoTicks--;
        if (berryDemoTicks == 560) {
            addHarvest(20.0);   // 먹이·예비·번식 몫을 떼고도 넉넉히 남는 잉여
            debugSettleOnce();  // → 번식 1회 + 남는 잉여로 옆 정원에 베리 여러 그루(관찰)
        } else if (berryDemoTicks < 560 && berryDemoTicks % 15 == 0) {
            ripenOneBerry(sl);  // 심은 age1 베리를 빠르게 익혀 아버지가 수확할 수 있게(실시간 성장 연출)
        }
    }

    /** 옆 정원에서 아직 덜 익은 베리 한 그루의 나이를 +1 (실연용 빠른 성장). */
    private void ripenOneBerry(ServerLevel sl) {
        if (homePos == null) {
            return;
        }
        for (BlockPos tile : HomeStructure.berryTiles(homePos, getHomeFacingDir())) {
            for (int dy = 3; dy >= -3; dy--) {
                BlockPos p = tile.offset(0, dy, 0);
                BlockState s = sl.getBlockState(p);
                if (s.is(Blocks.SWEET_BERRY_BUSH) && s.getValue(SweetBerryBushBlock.AGE) < 3) {
                    sl.setBlockAndUpdate(p, s.setValue(SweetBerryBushBlock.AGE,
                            s.getValue(SweetBerryBushBlock.AGE) + 1));
                    return;
                }
            }
        }
    }

    /** 거처 모닥불 배치/재점화 (완성·이주 시). */
    private static void relightHearth(ServerLevel sl, BlockPos home, Direction facing) {
        placeHearth(sl, home, facing, true);
    }

    private static void placeHearth(ServerLevel sl, BlockPos home, Direction facing, boolean lit) {
        BlockPos hp = HomeStructure.hearthPos(home, facing);
        var cur = sl.getBlockState(hp);
        boolean replaceable = cur.isAir() || cur.getBlock() instanceof MimicHearthBlock
                || cur.is(Blocks.GRASS) || cur.is(Blocks.TALL_GRASS) || cur.is(Blocks.FERN);
        if (replaceable) {
            sl.setBlockAndUpdate(hp, ModBlocks.MIMIC_HEARTH.get().defaultBlockState()
                    .setValue(MimicHearthBlock.LIT, lit)
                    .setValue(MimicHearthBlock.FACING, facing));
        }
        hearthLit(home, lit); // 켜진 모닥불 전역 목록 갱신(구혼 여행 목적지)
    }

    /**
     * 짓는 연출 — 부부가 <b>각자에게 가장 가까운 칸</b>으로 직접 걸어가(MimicBuildGoal) 손에 든 그 블럭을
     * 닿았을 때 설치한다(팔 스윙 + 블록별 설치음). 칸 소유는 '가장 가까운 구성원'으로 갈라 <b>동시에·충돌
     * 없이</b> 짓고(거리 기반이라 걷는 거리 최소 → 설치가 촘촘해 스윙이 잘 보임), 다른 구성원이 있는 칸엔
     * 놓지 않아(질식 방지) 파묻지 않는다. <b>전투/피격 중엔 건축을 멈춘다</b>(이동은 전투 goal이 잡고, 설치
     * 스윙이 전투 스윙과 섞이지 않게). 닿을 수 없는 자리는 폴백 타이머로 강제 설치해 교착을 막고, 전부
     * 설치되면 모닥불을 점화한다.
     */
    private void buildTick() {
        if (!building || homePos == null || individual == null
                || !(level() instanceof ServerLevel sl)) {
            return;
        }
        // 전투/피격 중엔 건축 정지 — 스윙 누수·충돌 방지(끝나면 자동 재개).
        if (isUnderThreat()) {
            buildTargetPos = null;
            buildReachTicks = 0;
            clearBuildItem();
            return;
        }
        Direction facing = getHomeFacingDir();
        List<HomeStructure.Placement> plan = HomeStructure.plan(homePos, facing);
        List<MimicEntity> crew = buildCrew(sl);

        // ① 완성 판정 — 남은 설치 가능 블록이 없으면 종료(최소 id가 점화·완료 처리).
        if (!anyPlaceable(sl, plan)) {
            buildTargetPos = null;
            clearBuildItem();
            if (crew.isEmpty() || crew.get(0) == this) {
                placeHearth(sl, homePos, facing, true); // 완성 → 점화
                finishBuilding(sl);
            } else {
                building = false; // 동료가 마무리(점화)를 맡음
            }
            return;
        }

        if (buildCooldown > 0) {
            buildCooldown--;
        }

        // ② 목표 선정 — 진행 중 목표가 아직 유효하면 유지(오실레이션 방지), 아니면 내가 소유한 최근접 칸.
        HomeStructure.Placement target = stickyOrNearest(sl, plan, crew);
        if (target == null) {
            buildTargetPos = null;
            clearBuildItem();
            return; // 내 몫은 끝, 남은 건 동료가 마저 짓는다
        }
        if (!target.pos().equals(buildTargetPos)) {
            buildReachTicks = 0; // 새 목표 → 접근 타이머 초기화(먼 곳 강제설치 방지)
        }
        buildTargetPos = target.pos(); // MimicBuildGoal 이 이 좌표 옆으로 데려감
        showBuildItem(target);         // 그 블럭을 손에 들고 이동(설치 연출)

        // ③ 닿아야 설치. 못 닿으면 접근 대기(단, 오래 못 닿으면 강제 설치로 교착 방지).
        if (!withinReach(target.pos())) {
            buildReachTicks++;
            if (buildReachTicks < BUILD_REACH_TIMEOUT) {
                return; // 아직 걸어가는 중
            }
        }
        // ④ 개인 쿨다운 박자 — 도착 즉시(쿨다운 지났으면) 한 칸, 놓은 뒤 잠깐 쉼.
        if (buildCooldown > 0) {
            return;
        }
        if (placeBuildBlock(sl, target)) {
            swing(InteractionHand.MAIN_HAND);
            playPlaceSound(sl, target); // 블록별 설치 효과음
        }
        buildCooldown = BUILD_INTERVAL;
        buildReachTicks = 0;
        buildTargetPos = null; // 다음 틱에 다음 최근접 칸 선정
    }

    /** 진행 중 목표가 아직 설치 가능하면 유지, 아니면 내가 '소유'(최근접 구성원)한 최근접 설치가능 칸. */
    @Nullable
    private HomeStructure.Placement stickyOrNearest(ServerLevel sl, List<HomeStructure.Placement> plan,
                                                    List<MimicEntity> crew) {
        if (buildTargetPos != null && isPlaceable(sl, buildTargetPos)) {
            HomeStructure.Placement cur = placementAt(plan, buildTargetPos);
            if (cur != null) {
                return cur; // 고정 유지
            }
        }
        HomeStructure.Placement best = null;
        double bestD = Double.MAX_VALUE;
        for (HomeStructure.Placement p : plan) {
            if (!isPlaceable(sl, p.pos())) {
                continue;
            }
            double d = horizDistSq(p.pos());
            if (d < bestD && ownedByMe(crew, p.pos(), d)) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** 이 칸을 내가 담당하나 — crew 중 내가 가장 가깝나(동률이면 낮은 id). 거리 기반이라 걷는 거리 최소. */
    private boolean ownedByMe(List<MimicEntity> crew, BlockPos pos, double myD) {
        for (MimicEntity m : crew) {
            if (m == this) {
                continue;
            }
            double d = m.horizDistSq(pos);
            if (d < myD || (d == myD && m.getId() < getId())) {
                return false;
            }
        }
        return true;
    }

    private static HomeStructure.Placement placementAt(List<HomeStructure.Placement> plan, BlockPos pos) {
        for (HomeStructure.Placement p : plan) {
            if (p.pos().equals(pos)) {
                return p;
            }
        }
        return null;
    }

    private static boolean anyPlaceable(ServerLevel sl, List<HomeStructure.Placement> plan) {
        for (HomeStructure.Placement p : plan) {
            if (isPlaceable(sl, p.pos())) {
                return true;
            }
        }
        return false;
    }

    /** 이 블록 자리까지 수평 거리²(발밑 기준). */
    private double horizDistSq(BlockPos p) {
        double dx = (p.getX() + 0.5) - getX();
        double dz = (p.getZ() + 0.5) - getZ();
        return dx * dx + dz * dz;
    }

    /** 이 블록 자리에 손이 닿는가(수평 거리 기준 — 지붕 등 높은 칸은 폴백 타이머가 보완). */
    private boolean withinReach(BlockPos p) {
        return horizDistSq(p) <= BUILD_REACH * BUILD_REACH;
    }

    private long threatCacheTick = -1L;   // isUnderThreat 틱 캐시(휘발) — goal 평가가 틱당 수 회 재호출
    private boolean threatCacheValue;

    /** 전투/피격 중인가 — 피격 직후·최근 가해자 기억·나를 노리는 근처 좀비. 건축 정지 판단용.
     *  같은 틱 안에서는 첫 계산을 재사용(리시·건축·나눔·대사 판정이 틱당 3~5회 부르던 좀비 스캔 중복 제거).
     *  틱 중간에 발생한 피격은 다음 틱에 반영(1틱 지연 — 종전에도 goal 평가 순서에 따라 동일했음). */
    public boolean isUnderThreat() {
        long now = level().getGameTime();
        if (threatCacheTick == now) {
            return threatCacheValue;
        }
        threatCacheTick = now;
        threatCacheValue = computeUnderThreat();
        return threatCacheValue;
    }

    private boolean computeUnderThreat() {
        if (hurtTime > 0 || getLastHurtByMob() != null) {
            return true;
        }
        for (Zombie z : level().getEntitiesOfClass(Zombie.class, getBoundingBox().inflate(12.0))) {
            if (z.getTarget() == this) {
                return true;
            }
        }
        return false;
    }

    /** 지금 걸어가 설치할 다음 블록 좌표(없으면 null) — MimicBuildGoal 이 목적지로 사용. */
    @Nullable
    public BlockPos getBuildTargetPos() {
        return buildTargetPos;
    }

    /** 같은 거처를 함께 짓는 살아있는 구성원(나 포함) — id 순 정렬로 소유 판정 동률이 안정적. */
    private List<MimicEntity> buildCrew(ServerLevel sl) {
        List<MimicEntity> crew = new ArrayList<>();
        for (MimicEntity m : sl.getEntitiesOfClass(MimicEntity.class, getBoundingBox().inflate(24.0))) {
            if (m.isAlive() && m.building && homePos.equals(m.getHomePos())) {
                crew.add(m);
            }
        }
        crew.sort(Comparator.comparingInt(Entity::getId));
        return crew;
    }

    private boolean placeBuildBlock(ServerLevel sl, HomeStructure.Placement p) {
        if (!isPlaceable(sl, p.pos())) {
            return false; // 이미 채워짐/장애물
        }
        if (cellOccupiedByOther(sl, p.pos())) {
            nudgeOccupants(sl, p.pos()); // 파묻지 않되 살짝 밀어냄 — 마지막 칸에 동료가 계속 서 있으면
            return false;                // 완성 판정이 영영 안 나던 교착의 자연 해소를 가속
        }
        sl.setBlockAndUpdate(p.pos(), blockFor(p).defaultBlockState());
        return true;
    }

    /** 설치 예정 칸에 서 있는 다른 미믹을 바깥쪽으로 살짝 민다(질식 없는 비강제 해소). */
    private void nudgeOccupants(ServerLevel sl, BlockPos pos) {
        for (MimicEntity m : sl.getEntitiesOfClass(MimicEntity.class, new AABB(pos))) {
            if (m == this) {
                continue;
            }
            double dx = m.getX() - (pos.getX() + 0.5);
            double dz = m.getZ() - (pos.getZ() + 0.5);
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.05) { // 정중앙 — 무작위 방향으로
                double ang = getRandom().nextDouble() * Math.PI * 2.0;
                dx = Math.cos(ang);
                dz = Math.sin(ang);
                len = 1.0;
            }
            m.push(dx / len * 0.3, 0.05, dz / len * 0.3);
        }
    }

    /** 그 자리에 부지 블록을 놓을 수 있나(빈 칸·교체 가능 초목만 대체). 꽃·묘목·버섯·잎이
     *  벽 칸을 차지하면 그 칸이 영영 미설치된 채 완공되던(구멍 난 천막) 결함 — 초목은 치운다. */
    private static boolean isPlaceable(ServerLevel sl, BlockPos pos) {
        var cur = sl.getBlockState(pos);
        return cur.isAir() || isClearableVegetation(cur);
    }

    /** 건축·메움이 밀어내도 되는 자연 초목(재생 가능·비구조물). */
    private static boolean isClearableVegetation(net.minecraft.world.level.block.state.BlockState s) {
        return s.is(Blocks.GRASS) || s.is(Blocks.TALL_GRASS)
                || s.is(Blocks.FERN) || s.is(Blocks.LARGE_FERN) || s.is(Blocks.SNOW)
                || s.is(Blocks.DEAD_BUSH) || s.is(Blocks.BROWN_MUSHROOM) || s.is(Blocks.RED_MUSHROOM)
                || s.is(net.minecraft.tags.BlockTags.FLOWERS)
                || s.is(net.minecraft.tags.BlockTags.SAPLINGS)
                || s.is(net.minecraft.tags.BlockTags.LEAVES); // 이웃 나무의 드리운 잎이 벽 칸 차지 방지
    }

    /** 그 칸에 나 아닌 다른 미믹이 있나(질식 방지 — 남을 파묻지 않음). */
    private boolean cellOccupiedByOther(ServerLevel sl, BlockPos pos) {
        for (MimicEntity m : sl.getEntitiesOfClass(MimicEntity.class, new AABB(pos))) {
            if (m != this && m.isAlive()) {
                return true;
            }
        }
        return false;
    }

    private static Block blockFor(HomeStructure.Placement p) {
        return p.token() == HomeStructure.TOKEN_FENCE ? Blocks.OAK_FENCE : Blocks.WHITE_WOOL;
    }

    /** 지금 설치할 블럭을 손에 들려 보여준다(바뀔 때만 갱신해 동기화 절약). */
    private void showBuildItem(HomeStructure.Placement p) {
        ItemStack want = new ItemStack(blockFor(p));
        if (!ItemStack.matches(getItemBySlot(EquipmentSlot.MAINHAND), want)) {
            setItemSlot(EquipmentSlot.MAINHAND, want);
        }
    }

    /** 손에 든 블럭을 치운다(건축 종료·대기). */
    private void clearBuildItem() {
        if (!getItemBySlot(EquipmentSlot.MAINHAND).isEmpty()) {
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }

    /** 블록별 설치 효과음(양털=푹신, 울타리=나무) — 바닐라 블록 배치음과 동일 톤. */
    private void playPlaceSound(ServerLevel sl, HomeStructure.Placement p) {
        SoundType st = blockFor(p).defaultBlockState().getSoundType();
        sl.playSound(null, p.pos(), st.getPlaceSound(), SoundSource.BLOCKS,
                (st.getVolume() + 1.0F) / 2.0F, st.getPitch() * 0.8F);
    }

    /** 건축 완료 — 거처 구성원 전원 building 해제·손에 든 블럭 정리. */
    private void finishBuilding(ServerLevel sl) {
        for (MimicEntity m : sl.getEntitiesOfClass(MimicEntity.class, getBoundingBox().inflate(24.0))) {
            if (homePos.equals(m.getHomePos())) {
                m.building = false;
                m.clearBuildItem();
            }
        }
        building = false;
        SimEvents.event(this, "건축완료", "거처 @" + homePos.getX() + "," + homePos.getY() + "," + homePos.getZ());
    }

    /** 영구 제거(사망·아사) 시 거처 무인화되면 모닥불을 끈다(폐허로 남김). 청크 언로드엔 반응 안 함. */
    @Override
    public void remove(Entity.RemovalReason reason) {
        BlockPos home = homePos;
        byte facing = homeFacing;
        boolean destroy = reason.shouldDestroy();
        super.remove(reason);
        if (destroy && individual != null && level() instanceof ServerLevel sld) {
            // 혈통 원장 사망 마킹 — 전투사·아사·노년 소멸 전부 이 경로(청크 언로드는 destroy 아님).
            // 무대 개체는 등록이 없어 markDead 가 무시한다.
            FamilyLedger.get(sld).markDead(individual.id(), level().getGameTime() / 24000L);
            // 밭 상속(M6) — 장자→배우자→무주지. 소유가 없으면 inherit 가 즉시 반환.
            FarmStore.get(sld).inherit(sld, individual.id(), spouseId);
        }
        if (destroy && home != null && level() instanceof ServerLevel sl && !anyResidentAt(sl, home)) {
            BlockPos hp = HomeStructure.hearthPos(home, Direction.from2DDataValue(facing));
            var st = sl.getBlockState(hp);
            if (st.getBlock() instanceof MimicHearthBlock && st.getValue(MimicHearthBlock.LIT)) {
                sl.setBlockAndUpdate(hp, st.setValue(MimicHearthBlock.LIT, Boolean.FALSE));
                hearthLit(home, false);
                ABANDONED_HOMES.add(new int[] {home.getX(), home.getY(), home.getZ(), facing});
            } else if (building && !(st.getBlock() instanceof MimicHearthBlock)) {
                // 미완성 부지(모닥불은 완공 때 놓임)에서 마지막 건축자까지 죽음 — 폐가 목록은
                // 모닥불을 요구해 영영 재사용 불가였으므로, 잔해(양털·울타리)를 철거해 자연 복원.
                demolishUnfinished(sl, home, Direction.from2DDataValue(facing));
                LarderStore.get(sl).remove(home); // 집이 사라졌으니 저장고 엔트리도(고아 방지, M-9)
            }
        }
    }

    /** 미완성 부지의 구조 토큰 블록만 철거(자연 지형·타인 블록은 무접촉). */
    private static void demolishUnfinished(ServerLevel sl, BlockPos home, Direction facing) {
        for (HomeStructure.Placement p : HomeStructure.plan(home, facing)) {
            var cur = sl.getBlockState(p.pos());
            if (cur.is(Blocks.WHITE_WOOL) || cur.is(Blocks.OAK_FENCE)) {
                sl.setBlockAndUpdate(p.pos(), Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static void heartEffect(MimicEntity m) {
        if (m.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.HEART,
                    m.getX(), m.getY() + m.getBbHeight() * 0.6, m.getZ(),
                    7, m.getBbWidth() * 0.5, m.getBbHeight() * 0.4, m.getBbWidth() * 0.5, 0.02);
        }
    }

    /** 현재 최고 매력 후보(동점 랜덤). 무효 후보(사망·성사)는 정리하며 반환. */
    public MimicEntity currentBestCandidate() {
        if (!(level() instanceof ServerLevel sl)) {
            return null;
        }
        List<MimicEntity> topTies = new ArrayList<>();
        List<Integer> remove = new ArrayList<>();
        int best = Integer.MIN_VALUE;
        for (int id : candidates) {
            Entity e = sl.getEntity(id);
            // 유효 대상: 독신 성년, 또는 (내가 여성일 때) 기혼 성년 남성(일부다처 후보).
            boolean validMarried = e instanceof MimicEntity mm && isFemale() && !mm.isFemale()
                    && mm.getStage() == LifeStage.ADULT && !mm.isBuilding();
            if (!(e instanceof MimicEntity m) || !m.isAlive() || m.getIndividual() == null
                    || (!m.isSingleAdult() && !validMarried)) {
                remove.add(id);
                continue;
            }
            int charm = candidateCharm.getOrDefault(id, 0);
            if (charm > best) {
                best = charm;
                topTies.clear();
                topTies.add(m);
            } else if (charm == best) {
                topTies.add(m);
            }
        }
        for (int id : remove) {
            candidates.remove((Integer) id);
            candidateCharm.remove(id);
        }
        if (topTies.isEmpty()) {
            return null;
        }
        return topTies.get(getRandom().nextInt(topTies.size()));
    }

    /** 거절 쿨다운(틱) — 하루 뒤 재구애 허용. 영구 배제가 아닌 이유: 눈낮춤(§10)은 거절이 반복될수록
     *  수신자의 k 가 내려가 결국 성사되는 구조라, 구혼자가 재시도할 수 있어야 수렴한다(2인 마을 교착 방지).
     *  스팸 방지는 1일 간격으로 유지. */
    private static final long REJECT_RETRY_TICKS = 24000L;

    /** 거절/포기: 상대를 하루 쿨다운에 넣고 후보에서 제거. 접근 실패는 {@link #backOffFrom}(짧은 쿨다운). */
    public void giveUpOn(int id) {
        approachRetryAt.put(id, level().getGameTime() + REJECT_RETRY_TICKS);
        candidates.remove((Integer) id);
        candidateCharm.remove(id);
    }

    /** 접근 실패 쿨다운(틱) — 이 시간 뒤 같은 상대를 다시 후보로 고려(배회 한 주기 내 재시도). */
    private static final long APPROACH_RETRY_TICKS = 2400L;

    /**
     * 일시 회피(접근 실패 전용): 후보에서 빼되 재시도 시각만 걸어둔다 — 영구 거절과 구분.
     * 경로 실패·상대 이동 같은 일시 사유가 이웃 이성을 하나씩 영구 소진시키던 결함 방지.
     */
    public void backOffFrom(int id) {
        approachRetryAt.put(id, level().getGameTime() + APPROACH_RETRY_TICKS);
        candidates.remove((Integer) id);
        candidateCharm.remove(id);
    }

    /** 구애 풀 일괄 정리 — 성혼·사별·노년 전이 같은 상태 전환점에서 낡은 후보·거절 기록을 비운다
     *  (사별 재구애 시 과거 스침 상대가 영구 배제되던 결함 + 전이 후 stale 캐시 잔존 해소). */
    private void clearCourtshipPool() {
        candidates.clear();
        candidateCharm.clear();
        approachRetryAt.clear();
    }

    public MateState getMateState() {
        return mateState;
    }

    public void setMateState(MateState s) {
        this.mateState = s;
    }

    public boolean isSearchReady() {
        return individual != null
                && (StageObserver.isActive() || searchTimer >= individual.mateChoice().searchTicks());
    }

    public boolean hasCandidate() {
        return !candidates.isEmpty();
    }

    public void resetSearchTimer() {
        this.searchTimer = 0;
    }

    public void setCourtTargetId(int id) {
        this.courtTargetId = id;
    }

    public void addCourtLog(CourtRecord r) {
        courtLog.add(r);
        if (courtLog.size() > COURT_LOG_MAX) {
            courtLog.remove(0);
        }
    }

    /** GUI용: 후보 id 목록(매력 내림차순). */
    public List<Integer> getCandidateIds() {
        return candidates;
    }

    public int candidateCharmOf(int id) {
        return candidateCharm.getOrDefault(id, 0);
    }

    public List<CourtRecord> getCourtLog() {
        return courtLog;
    }

    /**
     * 등장 소개 — 로그가 켜져 있으면 개체당 1회, 변수 전부(성별·단계·세대·발현 특성·육아·짝고름)를
     * 남긴다. 로그를 켜는 순간 살아있는 전 개체가 한 번씩 자기소개 → 이후 사건 로그의 대조표가 된다.
     */
    private void introTick() {
        if (introLogged || individual == null || !SimEvents.enabled()) {
            return;
        }
        introLogged = true;
        SimEvents.event(this, "등장", String.format("세대%d 특성[%s] 육아=%s 짝고름=%s%s",
                individual.generation(), traitStr(individual),
                individual.parentingCare().label(), individual.mateChoice().label(),
                stageActor ? " [무대]" : "")); // 무대 표식 — 관측 로그에서 검증 개체 필터용
    }

    /**
     * 개인 허기 틱 (식량 v2, R1). 활동(취침0·대기0.4·이동1·사냥1.5·전투2)·특성·부상 차등으로 보유 H를
     * 연속(소수) 소모. <b>H는 배부름+소지 식량의 통합 추상</b>이라, H=0은 "소지 식량 고갈"이며 유예
     * ({@link FoodEconomy#GRACE_TICKS}) 초과 시 굶주림 피해 — 채집·급식으로 H>0이 되면 즉시 풀린다.
     */
    private void hungerTick() {
        if (individual == null) {
            return;
        }
        int interval = fastSettle ? FAST_HUNGER_INTERVAL : HUNGER_INTERVAL;
        if ((level().getGameTime() + getId()) % interval != 0) {
            return;
        }
        double scale = fastSettle ? FAST_TIME_SCALE : 1.0;
        // fast 무대는 시간 압축이라 취침 0배율이 끼면 검증이 멈춘다 → 이동 기준으로 고정.
        Activity act = fastSettle ? Activity.MOVE : deriveActivity();
        double perDay = FoodEconomy.consumptionPerDay(getStage(), act, individual,
                getHealth() < getMaxHealth())
                * FoodEconomy.maternalHungerMult(getStage(), cachedMaternal); // 모성애 축(자식 허기 효율)
        holding = Math.max(0.0, holding - perDay * interval * scale / 24000.0);
        // 위급 전이 로그(1회) — 진입 시 대응 방향(귀가/강행)까지 남겨 밸런싱 근거로.
        boolean crit = isCritical();
        if (crit != wasCritical) {
            wasCritical = crit;
            if (crit) {
                SimEvents.event(this, "위급", larderHasFood()
                        ? "소지 고갈 임박 — 저장고 있음(귀가 우선)" : "소지 고갈 임박 — 저장고 없음(채집 강행)");
            } else {
                SimEvents.event(this, "회복", "소지 회복 → 위급 해제");
            }
        }
        if (holding > 0.0) {
            hungerGraceTicks = 0;
            return;
        }
        hungerGraceTicks += interval; // 틱 단위 누적(B-4) — NBT 저장으로 재로그인 리셋 방지
        int grace = fastSettle ? FAST_STARVE_GRACE : FoodEconomy.GRACE_TICKS;
        if (hungerGraceTicks > grace) {
            if (hungerGraceTicks - interval <= grace) {
                SimEvents.event(this, "굶주림", "유예 " + grace + "틱 초과 → 피해 시작");
            }
            hurt(damageSources().starve(), fastSettle ? 2.0F : 0.5F); // 임시값, 게임 관찰로 확정
            if (isDeadOrDying()) {
                StageObserver.record(getId(), "settle:starved");
                SimEvents.event(this, "아사", "소지 식량 고갈 지속 → 굶어 죽음");
            }
        }
    }

    /** 현재 상태 → 활동 강도(소모 배율). 전투 > 취침(위급이면 R6로 깨어 있어 제외) > 이동 > 대기. */
    public Activity deriveActivity() {
        if (isUnderThreat()) {
            return Activity.COMBAT;
        }
        // 실제로 쉬고 있을 때만 소모 0 — 시간대만 보고 SLEEP 처리하면 귀가 보행이 공짜 대사가 된다.
        // 거처 보유자는 집 반경, 방랑자는 제자리(이동 없음)면 취침으로 인정(노숙).
        boolean resting = homePos == null ? !getNavigation().isInProgress() : isHome();
        if (individual != null && !isCritical() && resting
                && Schedule.phaseAt(individual, level().getDayTime()) == Schedule.Phase.SLEEP) {
            return Activity.SLEEP;
        }
        return getNavigation().isInProgress() ? Activity.MOVE : Activity.IDLE;
    }

    /**
     * 가족 정산 틱 (식량 v2, R3·R5). {@link FoodEconomy#FAMILY_TICK_INTERVAL} 주기(스태거)로 <b>대표</b>가
     * ① 집에 있는 구성원의 여분 정수를 저장고에 입금 ② 남편→자식→아내 순으로 배고픈 구성원 급식
     * ③ 출산 비용 선차감형 번식 판정 ④ 남는 저장고로 옆 정원 베리. 18000틱 시각 의존이 없어
     * "정산 창을 놓치면 번식 불가" 문제가 사라진다.
     */
    private void familyTick() {
        if (individual == null) {
            return; // 건축 중에도 정산은 허용 — 순수 회계라 건축과 충돌 없고, 부부 전원이
                    // 건축(이주·신축)인 가구의 급식·입출금이 완공까지 멎는 결함 방지
        }
        int interval = fastSettle ? FAST_SETTLE_INTERVAL : FoodEconomy.FAMILY_TICK_INTERVAL;
        if ((level().getGameTime() + getId()) % interval != 0) {
            return;
        }
        if (!(level() instanceof ServerLevel sl)) {
            return;
        }
        runFamilySettle(sl, false);
    }

    /** 점검용 — 주기·대표 게이트를 건너뛰고 즉시 1회 가족 정산(입금·급식·번식·베리 포함). */
    public void debugSettleOnce() {
        if (level() instanceof ServerLevel sl) {
            runFamilySettle(sl, true);
        }
    }

    /** 가족 정산 실체 — 순수 {@link FoodEconomy#settleHome}에 배선만 입힌다. */
    private void runFamilySettle(ServerLevel sl, boolean force) {
        List<MimicEntity> fam = householdMembers();
        if (!force && settleLeader(fam) != this) {
            return; // 대표만 실행(중복 방지)
        }

        // 우선순위 구성: 남편(혼인 링크 보유 남성 우선, 동급이면 UUID 최소) → 자식(미성년) → 아내(그 외 성년·노년).
        // 혼인 우선 — 성년 아들이 UUID 순으로 아버지를 밀어내면 어미 탐색(spouseId 대조)이 조용히 실패해
        // 번식이 막히는 결함 방지. 노년도 가장 자격 유지 — 노년은 나이(배율·번식 종료)일 뿐,
        // 남편·아내 포지션은 혼인 링크가 있는 한 그대로다.
        MimicEntity father = null;
        boolean fatherMarried = false;
        for (MimicEntity m : fam) {
            if (m.getIndividual() == null || m.isFemale()
                    || (m.getStage() != LifeStage.ADULT && m.getStage() != LifeStage.ELDER)) {
                continue;
            }
            boolean married = hasWifeIn(fam, m);
            if (father == null || (married && !fatherMarried)
                    || (married == fatherMarried && m.getUUID().compareTo(father.getUUID()) < 0)) {
                father = m;
                fatherMarried = married;
            }
        }
        List<MimicEntity> ordered = new ArrayList<>(fam.size());
        if (father != null) {
            ordered.add(father);
        }
        for (MimicEntity m : fam) {
            if (m.getIndividual() != null
                    && (m.getStage() == LifeStage.INFANT || m.getStage() == LifeStage.BOY)) {
                ordered.add(m); // 자식 칸 = 미성년만(노년은 성년 칸 — 자식 취급 종료)
            }
        }
        for (MimicEntity m : fam) {
            if (m.getIndividual() != null && m != father
                    && (m.getStage() == LifeStage.ADULT || m.getStage() == LifeStage.ELDER)) {
                ordered.add(m);
            }
        }
        if (ordered.isEmpty()) {
            return;
        }

        List<FoodEconomy.Eater> eaters = new ArrayList<>(ordered.size());
        for (MimicEntity m : ordered) {
            eaters.add(new FoodEconomy.Eater(m.getIndividual(), m.getStage(), m.holding, m.isHome()));
        }
        double need = FoodEconomy.nominalDailyNeed(eaters);

        LarderStore store = null;
        double larder = 0.0;
        if (homePos != null) {
            store = LarderStore.get(sl);
            // 시작 L = ceil(하루소모) 정수(B-3). 무대(fast)는 인위 세팅이라 0에서 시작.
            larder = store.getOrInit(homePos, fastSettle ? 0.0 : FoodEconomy.initialLarder(need));
        }
        larder = FoodEconomy.settleHome(larder, eaters);

        // 결과 반영 + goal 캐시 갱신(채집 goal이 매 틱 가족 스캔 없이 판단하도록).
        int adults = 0;
        int boys = 0;
        int infants = 0;
        int elders = 0;
        int deposited = 0;
        int withdrawn = 0;
        double holdSum = 0.0;
        MimicEntity fedInfant = null;
        for (int i = 0; i < ordered.size(); i++) {
            MimicEntity m = ordered.get(i);
            FoodEconomy.Eater e = eaters.get(i);
            double delta = e.holding - m.holding; // 정산 후 − 전(정수 입출금 집계용)
            if (delta >= 1.0 - 1.0E-9) {
                withdrawn += (int) Math.round(delta);
            } else if (delta <= -1.0 + 1.0E-9) {
                deposited += (int) Math.round(-delta);
            }
            if (m.getStage() == LifeStage.INFANT && delta > 1.0E-9 && fedInfant == null) {
                fedInfant = m;
            }
            m.holding = e.holding;
            m.lastSurplus = larder;
            m.lastFed = !m.isCritical();
            m.cachedFamilyNeed = need;
            m.cachedProvider = (m == father) || (father == null && m.getStage() == LifeStage.ADULT);
            // 모성애 축은 각 자식의 <b>친어미</b>(부모 링크 PA/PB)로 판정 — 명단 첫 성년 여성 추측은
            // 성년 딸·(일부다처의) 다른 부인 특성이 남의 자식에게 적용되는 오류였다.
            m.cachedMaternal = (m.getStage() == LifeStage.INFANT || m.getStage() == LifeStage.BOY)
                    ? maternalRank(motherIn(fam, m)) : 0;
            holdSum += m.holding;
            switch (m.getStage()) {
                case ADULT -> adults++;
                case BOY -> boys++;
                case INFANT -> infants++;
                case ELDER -> elders++; // 번식 성년수(adults)에 불포함 — 임계 왜곡 방지
            }
        }
        boolean starving = FoodEconomy.anyStarvingHome(eaters);

        // D 연출: 유아가 저장고에서 채워졌으면 <b>친어미</b>(부모 링크)의 행위로 귀속(숫자는 동일 — 로그만).
        // 친어미 부재 시 명단 첫 성년 여성(대리 양육), 그마저 없으면 대표.
        if (fedInfant != null) {
            MimicEntity mother = motherIn(fam, fedInfant);
            if (mother == null) {
                mother = firstAdultFemale(ordered);
            }
            SimEvents.event(mother != null ? mother : this, "육아", "저장고에서 꺼내 아기를 먹임");
        }
        // B-2: 과부 가구 붕괴는 허용 경로 — 로그만 남긴다(구제는 설계 결정 대기).
        // 가장 선출이 노년 남성도 포함하므로 father==null = 남성(성년·노년) 전무. 성년 여성 있는 가구만.
        if (father == null && adults > 0 && starving && homePos != null) {
            SimEvents.note(sl, "과부가구", "남편 없는 가구 굶주림 진행(허용된 붕괴 경로) — 거처 "
                    + homePos.toShortString());
        }
        // 성년 없는 노년 가구(노부부·노인+미성년)의 굶주림도 관찰 기록 — 과부가구 조건(성년 여성
        // 필요)에서 빠져 붕괴가 로그에 안 남던 관측 공백. 판정용 아님, 시계열 분석용 note.
        if (adults == 0 && elders > 0 && starving && homePos != null) {
            SimEvents.note(sl, "노년가구", "성년 없는 노년 가구 굶주림 진행 — 거처 "
                    + homePos.toShortString());
        }

        // R5 번식: (L − 출산비용 − 하루소모) ≥ 성년수+1(±특성) & 무굶주림 & 쿨다운·상한·과밀.
        // 어미 = 아버지와 실제 혼인한 아내 중 출산이 가장 오래된 쪽(일부다처 교대 출산).
        // 성년 딸(미혼 동거)은 배우자 링크가 없어 제외 — 부녀 교배 방지.
        // 노년 가장은 번식만 제외(청년기=번식기 설계) — 가장 포지션·급식 순서·동원 기준은 유지.
        if (homePos != null && father != null && father.getIndividual() != null
                && father.getStage() == LifeStage.ADULT) {
            MimicEntity mother = null;
            for (MimicEntity w : ordered) {
                if (w.isFemale() && w.getStage() == LifeStage.ADULT && w.getIndividual() != null
                        && w.spouseId == father.getIndividual().id()
                        && (mother == null || w.lastBirthTick < mother.lastBirthTick)) {
                    mother = w;
                }
            }
            if (mother != null && mother.getIndividual() != null) {
                double adj = Reproduction.threshold(father.getIndividual(), mother.getIndividual())
                        - Reproduction.BASE_THRESHOLD; // 번식선호/불호 보정만 추출
                long now = level().getGameTime();
                boolean cooldownOk = now - mother.lastBirthTick
                        >= (long) Reproduction.FEMALE_COOLDOWN_DAYS * 24000L;
                boolean underLimit = mother.childrenBorn
                        < Reproduction.birthLimit(mother.getIndividual(), father.getIndividual());
                boolean underPop = sl.getEntitiesOfClass(MimicEntity.class,
                        getBoundingBox().inflate(48.0)).size() <= LOCAL_POP_CAP;
                if (cooldownOk && underLimit && underPop
                        && FoodEconomy.canReproduce(larder, need, adults, adj, starving)
                        && mother.spawnChild(sl, father)) {
                    larder -= FoodEconomy.BIRTH_COST; // 비용은 출산이 실제 성사됐을 때만 차감(결과 기반)
                }
            }
        }

        // 옆 정원 베리: 최하위 — 하루소모·번식 몫(비용+임계)을 뺀 잔여로만. 투자 성향이 속도를 가른다.
        if (homePos != null) {
            int bushCount = countBerries(sl);
            double reproReserve = FoodEconomy.BIRTH_COST + adults + 1;
            // 투자 성향 배율도 가장(혼인 링크 아버지) 기준 — R4 동원 기준과 귀속 통일(정산 실행
            // 대표가 우연히 아내·아들이어도 정원 투자 속도가 흔들리지 않게). 가장 없으면 실행자.
            double costMult = BerryEconomy.costMult(
                    father != null && father.getIndividual() != null ? father.getIndividual()
                            : individual);
            int n = BerryEconomy.plant(larder, need, reproReserve, bushCount, BERRY_CAP, costMult);
            if (n > 0) {
                int done = plantBerries(sl, n);
                if (done > 0) {
                    // 심은 만큼 저장고에서 실제 차감(정수 반올림 — L 정수 불변식 유지). 차감 없인
                    // 저장고가 그대로인 채 베리만 늘어 공짜 식량 생성(착취 경로)이 된다.
                    int cost = Math.max(1, (int) Math.round(done * BerryEconomy.BUSH_COST * costMult));
                    larder = Math.max(0.0, larder - cost);
                    SimEvents.event(this, "베리", "옆 정원 +" + done + " (비용 " + cost
                            + " → 저장고 " + String.format("%.0f", larder)
                            + " · 누적 " + (bushCount + done) + "/" + BERRY_CAP + ")");
                }
            }
            store.set(homePos, larder);
            // 가계 시계열(≈1분/가구): 저장고·구성·소지합·하루소모·이번 입출금 — 밸런싱 근거의 근간.
            SimEvents.household(sl, homePos, larder, adults, boys, infants, elders, holdSum, need,
                    deposited, withdrawn);
            // R4 동원 전이: 저장고 넉넉↔부족이 뒤집힐 때만 1회 기록. 기준 일수는 <b>가장</b>(혼인 링크
            // 아버지)의 시간지향 특성 — 정산 실행자가 우연히 성년 아들이어도 기준이 흔들리지 않게.
            double comfort = need * FoodEconomy.comfortDays(
                    father != null && father.getIndividual() != null ? father.getIndividual()
                            : individual);
            int ms = larder >= comfort ? 0 : 1;
            if (ms != mobilizedState) {
                mobilizedState = ms;
                SimEvents.note(sl, "동원", String.format("@%d,%d %s (저장고%.0f vs 기준%.1f)",
                        homePos.getX(), homePos.getZ(),
                        ms == 1 ? "저장고 부족 → 온 가족 채집 합류" : "저장고 넉넉 → 비제공자 휴식",
                        larder, comfort));
            }
            // 기근 → 이주(이주 설계 §1–3): 채집자 전원 무수확 + 비축 바닥 + 쿨다운 경과 → 인지거리 외곽.
            if (!fastSettle && shouldFamilyMigrate(fam, larder, need)) {
                migrate(sl, fam);
            }
        }
    }

    // ── 이주(기근·동반 이주·족외혼) — 판정은 순수 Famine, 여기는 배선 ──

    /** 가족 기근 판정 — 채집 가능(비육아·비건축) 성원들의 성공 시각을 모아 순수 판정에 위임.
     *  정착 시각은 성년·노년만 집계(신생아 출생이 setHomePos로 쿨다운을 계속 리셋하는 결함 방지).
     *  인솔 성년·노년이 하나도 없는 가구는 이주하지 않는다(아이들만의 캐러밴 좌초 방지). */
    private boolean shouldFamilyMigrate(List<MimicEntity> fam, double larder, double need) {
        long now = level().getGameTime();
        long settled = 0L;
        int grown = 0;
        List<Long> success = new ArrayList<>();
        for (MimicEntity m : fam) {
            if (m.getIndividual() == null) {
                continue;
            }
            if (m.getStage() == LifeStage.ADULT || m.getStage() == LifeStage.ELDER) {
                grown++;
                settled = Math.max(settled, m.settledTick);
            }
            if (SurvivalRules.canGather(m.getStage(), m.getIndividual())
                    && !m.isCaregiverBound() && !m.isBuilding()) {
                success.add(m.lastForageSuccessTick);
            }
        }
        if (grown == 0) {
            return false;
        }
        // 안전핀: 명단이 나 혼자인데 배우자가 실제로 살아있으면 스캔 절단(원거리 고립) 의심 —
        // 가족을 두고 단독 이주(폐가화 동반)하지 않는다. 사별·미혼 1인 가구는 spouseAlive=false라
        // 정상적으로 이주 가능. 절단은 일시적(리시 복귀 후 정상 명단으로 재판정).
        if (fam.size() == 1 && fam.get(0).spouseAlive()) {
            return false;
        }
        long[] arr = new long[success.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = success.get(i);
        }
        return Famine.shouldMigrate(now, settled, arr, larder, need);
    }

    /**
     * 기근 이주 실행 — ① 목적지(마을 합의 동참 or 정찰·등록) ② 부지 선정(MIN_GAP) ③ 여행식량 인출
     * (남는 저장고는 폐가 유산) ④ 폐가화 → 전 가족 새 거처 귀속(리시가 캐러밴을 끈다) → 부부가 신축.
     * 유아는 어미가 업고 이동(방치 아사 방지), 도착하면 내려줌.
     */
    private void migrate(ServerLevel sl, List<MimicEntity> fam) {
        BlockPos oldHome = homePos;
        long now = level().getGameTime();

        MigrationDest consensus = MigrationDest.get(sl);
        BlockPos dest = consensus.resolve(oldHome, now);
        boolean pioneer = dest == null;
        if (pioneer) {
            dest = scoutDestination(sl, oldHome);
            consensus.register(oldHome, dest, now); // 길잡이 — 이후 이주 가족이 동참(캐러밴)
        }

        DeterministicRng rng = new DeterministicRng(getRandom().nextLong());
        int[] xz = Settlement.placeHome(new int[] {dest.getX(), dest.getZ()}, 8,
                collectExistingHomes(sl, dest.getX(), dest.getZ()), Settlement.MIN_GAP, rng);
        Direction facing = Direction.from2DDataValue(getRandom().nextInt(4));
        int baseY = terrainBaseY(sl, new BlockPos(xz[0], oldHome.getY(), xz[1]), facing);
        BlockPos newHome = new BlockPos(xz[0], baseY, xz[1]);

        // 여행 식량: 저장고에서 각자 상한(2)까지 정수 인출 — 남는 몫은 폐가 유산(재사용 시 계승).
        LarderStore store = LarderStore.get(sl);
        double left = store.get(oldHome);
        int packed = 0;
        for (MimicEntity m : fam) {
            // 밴드 상한을 일시 초과(최대 2.99)해도 무손실 — 거처 보유자는 addHarvest 컷이 없고,
            // 도착 정산이 여분 정수를 새 저장고에 그대로 입금한다(감사 LOW-6 검토 결과: 기각).
            while (left >= 1.0 && m.holding < FoodEconomy.BAND_HIGH) {
                left -= 1.0;
                m.holding += 1.0;
                packed++;
            }
        }
        store.set(oldHome, left);

        abandonHome(sl); // 모닥불 끔·재사용 목록 등록(폐가 로그 포함)

        // 업는 성년 목록: 어미 우선, 그 외 성년 순 — 유아가 여럿이면 순환 배정(부모가 나눠 업음).
        // 성년 여성이 없는 가족(홀아비+유아)도 아버지가 업어 좌초 방치가 없다.
        MimicEntity mother = firstAdultFemale(fam);
        List<MimicEntity> carriers = new ArrayList<>();
        if (mother != null) {
            carriers.add(mother);
        }
        for (MimicEntity m : fam) {
            if ((m.getStage() == LifeStage.ADULT || m.getStage() == LifeStage.ELDER) && m != mother) {
                carriers.add(m); // 노년도 업기·건축 가능 — 노부부 가구가 천막 없이 좌초하지 않게
            }
        }
        int builders = 0;
        int nextCarrier = 0;
        for (MimicEntity m : fam) {
            m.setHomePos(newHome); // settledTick 갱신 → 재이주 쿨다운 시작
            m.homeFacing = (byte) facing.get2DDataValue();
            if (m.getStage() == LifeStage.INFANT && !carriers.isEmpty()) {
                // 친어미(부모 링크)가 비어 있으면 그녀가 업고, 아니면 순환 배정(다둥이 분산 유지).
                MimicEntity own = motherIn(fam, m);
                MimicEntity ride = (own != null && own.getPassengers().isEmpty()) ? own
                        : carriers.get(nextCarrier++ % carriers.size());
                m.startRiding(ride, true);
            } else if ((m.getStage() == LifeStage.ADULT || m.getStage() == LifeStage.ELDER)
                    && builders < 2) {
                m.building = true; // 부부(최대 2)가 신축 담당 — 기존 buildTick 파이프라인
                m.buildReachTicks = 0;
                builders++;
            }
        }
        store.set(newHome, 0.0); // 새 저장고는 0에서 — 이주 반복으로 공짜 식량이 생기지 않게(착취 방지)
        flattenSite(sl, newHome, facing);

        SimEvents.event(this, "이주", String.format(
                "기근 → @%d,%d 출발 → @%d,%d 정착 · 가족%d · 여행식량 %d개%s",
                oldHome.getX(), oldHome.getZ(), newHome.getX(), newHome.getZ(),
                fam.size(), packed, pioneer ? " (길잡이·합의 등록)" : " (마을 합의 동참)"));
    }

    /** 8방위 × (활동반경×2) 지점의 풀 표본 → 최다 방위로 목적지(전부 0이면 무작위 — 잔류=죽음). */
    private BlockPos scoutDestination(ServerLevel sl, BlockPos from) {
        double dist = roamRadius() * Famine.MIGRATE_DISTANCE_MULT;
        int[] counts = new int[8];
        for (int d = 0; d < 8; d++) {
            double ang = d * Math.PI / 4.0;
            counts[d] = sampleGrass(sl,
                    from.getX() + (int) Math.round(Math.cos(ang) * dist),
                    from.getZ() + (int) Math.round(Math.sin(ang) * dist));
        }
        int best = Famine.bestDirection(counts);
        if (best < 0) {
            best = getRandom().nextInt(8);
        }
        double ang = best * Math.PI / 4.0;
        return new BlockPos(from.getX() + (int) Math.round(Math.cos(ang) * dist), from.getY(),
                from.getZ() + (int) Math.round(Math.sin(ang) * dist));
    }

    /** 지점 주변 ±12 무작위 24표본 중 채집 가능한 풀 개수(정찰 — findForage와 같은 표본 방식). */
    private int sampleGrass(ServerLevel sl, int cx, int cz) {
        int found = 0;
        for (int i = 0; i < 24; i++) {
            int x = cx + getRandom().nextInt(25) - 12;
            int z = cz + getRandom().nextInt(25) - 12;
            BlockPos p = sl.getHeightmapPos(SURFACE_MAP, new BlockPos(x, 0, z));
            var s = sl.getBlockState(p);
            if (s.is(Blocks.GRASS) || s.is(Blocks.TALL_GRASS) || s.is(Blocks.FERN)) {
                found++;
            }
        }
        return found;
    }

    /** 점검용 — 보유 H 직접 지정(입금/인출/나눔/위급 "직전" 상황 연출). */
    public void debugSetHolding(double h) {
        this.holding = h;
    }

    /** 점검용 — 즉시 '오래 외로움' 상태로: 다음 mateTick에서 구혼 여행이 바로 출발. /evosim suitor. */
    public void debugForceLonely() {
        this.lonelySinceTick = level().getGameTime() - Famine.LONELY_TRAVEL_AFTER - 1000L;
    }

    /** 점검용 — 온 가족을 즉시 기근 조건으로(성공·정착 시각 과거화, 저장고 비움). /evosim exodus. */
    public void debugForceFamine(ServerLevel sl) {
        for (MimicEntity m : householdMembers()) {
            m.lastForageSuccessTick = level().getGameTime() - Famine.STARVE_WINDOW - 1000L;
            m.settledTick = level().getGameTime() - Famine.RESETTLE_COOLDOWN - 1000L;
        }
        if (homePos != null) {
            LarderStore.get(sl).set(homePos, 0.0);
        }
    }

    /** 대표 선출(A-4, 결정론): 살아있는 <b>UUID 최소 성년</b>, 성년 없으면 UUID 최소 구성원. 매번 재계산.
     *  건축 여부는 무관 — familyTick 이 건축 중에도 정산을 허용하므로 제외할 이유가 없다(제외하면
     *  전원 건축 가구에서 대표가 없어 정산이 멎는다). */
    private static MimicEntity settleLeader(List<MimicEntity> fam) {
        MimicEntity bestAdult = null;
        MimicEntity best = null;
        for (MimicEntity m : fam) {
            if (!m.isAlive() || m.getIndividual() == null) {
                continue;
            }
            if (best == null || m.getUUID().compareTo(best.getUUID()) < 0) {
                best = m;
            }
            if (m.getStage() == LifeStage.ADULT
                    && (bestAdult == null || m.getUUID().compareTo(bestAdult.getUUID()) < 0)) {
                bestAdult = m;
            }
        }
        return bestAdult != null ? bestAdult : best;
    }

    /** fam 안에 이 남성을 배우자로 가리키는 아내(성년·노년)가 있나 — 가장(아버지) 선출의 혼인 우선 기준. */
    private static boolean hasWifeIn(List<MimicEntity> fam, MimicEntity male) {
        long id = male.getIndividual().id();
        for (MimicEntity w : fam) {
            if (w != male && w.isFemale() && w.getIndividual() != null && w.spouseId == id
                    && (w.getStage() == LifeStage.ADULT || w.getStage() == LifeStage.ELDER)) {
                return true; // 노년 아내도 아내 포지션 유지(노년=나이일 뿐)
            }
        }
        return false;
    }

    private static MimicEntity firstAdultFemale(List<MimicEntity> ordered) {
        for (MimicEntity m : ordered) {
            if (m.isFemale() && m.getStage() == LifeStage.ADULT) {
                return m;
            }
        }
        return null;
    }

    /**
     * 아이의 <b>친어미</b> — 가구 명단에서 부모 링크(PA/PB)와 id가 일치하는 성년·노년 여성.
     * 성별·나이·명단 순서로 추측하지 않는다(성년 딸/다른 부인 오인 방지). 없으면 null.
     */
    private static MimicEntity motherIn(List<MimicEntity> fam, MimicEntity child) {
        if (child.getIndividual() == null) {
            return null;
        }
        long pa = child.getIndividual().parentAId();
        long pb = child.getIndividual().parentBId();
        for (MimicEntity m : fam) {
            if (m != child && m.isFemale() && m.getIndividual() != null
                    && (m.getStage() == LifeStage.ADULT || m.getStage() == LifeStage.ELDER)) {
                long id = m.getIndividual().id();
                if (id == pa || id == pb) {
                    return m;
                }
            }
        }
        return null;
    }

    /** 어미의 모성애 등급(+1 강함/−1 없음/0 기본·부재) — 자식 허기·성장 캐시 입력. */
    private static int maternalRank(MimicEntity mother) {
        if (mother == null || mother.getIndividual() == null) {
            return 0;
        }
        var mt = ExpressionResolver.expressedTraits(mother.getIndividual());
        return mt.contains(com.evosim.core.Trait.STRONG_MATERNAL) ? 1
                : mt.contains(com.evosim.core.Trait.NO_MATERNAL) ? -1 : 0;
    }

    /**
     * 같은 거처(또는 독신이면 자기 자신)를 공유하는 가족 구성원 — <b>거처 중심</b> 반경 96 스캔.
     * 개체 중심 스캔(과거 24)은 부부가 반대편으로 채집 나가면 서로 안 보여 가구가 둘로 쪼개졌다
     * (대표 중복 정산·아버지 부재 오판·과부가구 오탐). 거처 중심이면 누가 호출해도 같은 명단이 나온다.
     * 반경 96 = {@link Roaming} 최대 활동반경(이주자×고독 32×2×1.5) — 리시 안의 성원은 항상 잡혀
     * "전원이 스캔 밖 → 각자 1인 가구 오판" 절단이 구조적으로 없다.
     */
    private List<MimicEntity> householdMembers() {
        List<MimicEntity> fam = new ArrayList<>();
        if (homePos == null) {
            fam.add(this); // 방랑자 = 1인 가정(자급자족)
            return fam;
        }
        for (MimicEntity m : level().getEntitiesOfClass(MimicEntity.class,
                new net.minecraft.world.phys.AABB(homePos).inflate(96.0))) {
            if (m.getIndividual() != null && homePos.equals(m.getHomePos())) {
                fam.add(m);
            }
        }
        if (fam.isEmpty()) {
            fam.add(this);
        }
        return fam;
    }

    /** 자식 하나를 낳아 거처에 배치 (저장고 잉여 확보 후). 어미 기준 기록. 실제 성사 여부 반환. */
    private boolean spawnChild(ServerLevel sl, MimicEntity father) {
        DeterministicRng rng = new DeterministicRng(getRandom().nextLong());
        int gen = Math.max(individual.generation(), father.getIndividual().generation()) + 1;
        long childId = Math.abs(getRandom().nextLong() | 1L);
        Individual childInd = Genetics.breed(childId, individual, father.getIndividual(), rng, gen, null);

        MimicEntity child = ModEntities.MIMIC.get().create(sl);
        if (child == null) {
            return false; // 생성 실패 — 비용도 기록도 없음(시도는 남기지 않는다)
        }
        child.setIndividual(childInd);
        child.setStage(LifeStage.INFANT);
        if (stageActor || father.stageActor) {
            child.markStageActor(); // 무대 혈통 전파 — addFreshEntity 전이라 원장 미등록이 보장됨
        }
        BlockPos where = homePos != null ? homePos : blockPosition();
        child.setHomePos(homePos);
        child.setBirthPos(where); // 태어난 위치 확정(애향심 신축 앵커·분가 기준)
        child.moveTo(where.getX() + 0.5, where.getY(), where.getZ() + 0.5, 0.0F, 0.0F);
        sl.addFreshEntity(child);

        lastBirthTick = level().getGameTime();
        childrenBorn++;
        StageObserver.record(this.getId(), "birth");
        // 신생아 변수를 정확히 기록: 성별·세대·발현 특성·부모 — 유전 흐름 검증의 근거.
        SimEvents.event(this, "출산", String.format(
                "자식 #%d(%s) 세대%d 특성[%s] · 부친 #%d 모친 #%d (누적 %d)",
                child.getId(), childInd.sex() == Sex.FEMALE ? "여" : "남", gen,
                traitStr(childInd), father.getId(), getId(), childrenBorn));
        return true;
    }

    /** 발현 특성 한글 나열(로그용) — 없으면 "무특성". */
    private static String traitStr(Individual ind) {
        var traits = ExpressionResolver.expressedTraits(ind);
        if (traits.isEmpty()) {
            return "무특성";
        }
        StringBuilder sb = new StringBuilder();
        for (var t : traits) {
            if (sb.length() > 0) {
                sb.append('·');
            }
            sb.append(t.koreanName());
        }
        return sb.toString();
    }

    /**
     * 검증 무대 개체 마킹 — 혈통 원장·인구 통계에서 제외된다. 스폰 헬퍼가
     * {@code addFreshEntity} <b>전에</b> 호출해야 첫 틱 원장 등록을 피한다(등록 취소는 없음).
     */
    /**
     * 새벽 동기 갱신(M7) — 잉여(거처 저장고+소유 밭 계정) vs 만족 기준(개인 하루소모 근사 ×
     * comfortDays × σ)을 캐시. 경쟁은 인지 48블록 내 타 가구 저장고 최대와 비교.
     */
    public void updateMotivation(ServerLevel sl) {
        if (individual == null) {
            return;
        }
        double wealth = homePos != null ? LarderStore.get(sl).get(homePos) : 0.0;
        int tiles = 0;
        for (FarmStore.Plot p : FarmStore.get(sl).all().values()) {
            if (p.ownerId == individual.id()) {
                wealth += p.account;
                tiles += p.tiles.length;
            }
        }
        double need = FoodEconomy.consumptionPerDay(getStage(), Activity.MOVE, individual, false);
        double neighborMax = 0.0;
        for (MimicEntity m : sl.getEntitiesOfClass(MimicEntity.class, getBoundingBox().inflate(48.0))) {
            if (m != this && m.getHomePos() != null && !m.getHomePos().equals(homePos)) {
                // 이웃 부도 자기 부와 같은 정의(저장고+소유 밭 계정) — 비대칭 비교 교정(R5).
                double nw = LarderStore.get(sl).get(m.getHomePos());
                if (m.getIndividual() != null) {
                    for (FarmStore.Plot p : FarmStore.get(sl).all().values()) {
                        if (p.ownerId == m.getIndividual().id()) {
                            nw += p.account;
                        }
                    }
                }
                neighborMax = Math.max(neighborMax, nw);
            }
        }
        boolean was = satisfiedToday;
        satisfiedToday = Satisfaction.satisfied(individual, need, wealth, neighborMax,
                tiles, satisfiedToday);
        if (was != satisfiedToday) {
            // 전환 순간만 기록(매일 스팸 방지) — 계층 심리 사슬: 분발→축적→만족(은퇴)→분발(몰락).
            SimEvents.event(this, satisfiedToday ? "만족" : "분발", String.format(
                    "잉여 %.1f 이웃최대 %.1f 밭 %d타일", wealth, neighborMax, tiles));
        }
        competitiveDriven = ExpressionResolver.isExpressed(individual, Trait.COMPETITIVE)
                && wealth <= neighborMax;
    }

    public boolean isSatisfiedToday() {
        return satisfiedToday;
    }

    public boolean isCompetitiveDriven() {
        return competitiveDriven;
    }

    /** 배우자 Individual.id (0=미혼) — 가족 노동(배우자 밭 수확)·케어 예산 합산용. */
    public long getSpouseId() {
        return spouseId;
    }

    public long getTenantFarm() {
        return tenantFarm;
    }

    public int getTenantStreak() {
        return tenantStreak;
    }

    /** 소작 관계 갱신(FarmTicker 전용 + 검증 조성) — 승격·해제·연속일. */
    public void setTenant(long farmId, int streak) {
        this.tenantFarm = farmId;
        this.tenantStreak = streak;
    }

    public void markStageActor() {
        this.stageActor = true;
    }

    public boolean isStageActor() {
        return stageActor;
    }

    public void setFastSettle(boolean fast) {
        this.fastSettle = fast;
    }

    public boolean isFastSettle() {
        return fastSettle;
    }

    /** 채집/사냥으로 확보한 식량을 소지분 H에 더한다(R2). 방랑자(집 없음)는 밴드 상한에서 컷. */
    public void addHarvest(double food) {
        // 섭취 효율(날로먹기 1.2) — 갓 딴 것을 바로 먹는 지점. 기근·쿼터 판정(dayGathered)은
        // 노동량 원량 기준을 유지해야 하므로 아래 누적에는 배율을 태우지 않는다.
        holding += food * (individual != null ? FoodEconomy.intakeMult(individual) : 1.0);
        if (food > 0.0) {
            lastForageSuccessTick = level().getGameTime(); // 기근 판정 근거(결과 기반)
            long day = level().getGameTime() / 24000L;
            if (day != gatherDay) {
                gatherDay = day;
                dayGathered = 0.0;
            }
            dayGathered += food; // 노년 쿼터 판정용 일일 누적
        }
        if ((homePos == null || isCourtTravel()) && holding > FoodEconomy.BAND_HIGH) {
            holding = FoodEconomy.BAND_HIGH; // 입금할 곳이 없으니(방랑/여행) 초과분은 버려짐
        }
    }

    /** 노년 쿼터 충족? — 오늘 채집 누적 ≥ dailyQuota(책임+2·부지런/게으름 반영)면 쉼. */
    public boolean elderQuotaMet() {
        if (getStage() != LifeStage.ELDER || individual == null) {
            return false;
        }
        if (level().getGameTime() / 24000L != gatherDay) {
            return false; // 새 날 — 아직 아무것도 못 범
        }
        double ownNeed = FoodEconomy.consumptionPerDay(LifeStage.ELDER, Activity.MOVE, individual, false);
        return dayGathered >= Elder.dailyQuota(individual, ownNeed);
    }

    /** 배달 자격(가드② 포함) — 노년·공유형·잉여 있음·자기 저장고가 부부 하루소모 이상. */
    public boolean canDeliverSurplus(ServerLevel sl) {
        return getStage() == LifeStage.ELDER && individual != null
                && Elder.sharesLeftover(individual)
                && holding >= FoodEconomy.BAND_HIGH
                && homePos != null
                && LarderStore.get(sl).get(homePos) >= cachedFamilyNeed * Elder.HOME_RESERVE_DAYS;
    }

    /** 잉여 정수를 대상 거처 저장고에 입금(노인 방문 배달). 실제 입금 개수 반환 — 결과값만 로그. */
    public int deliverSurplusTo(ServerLevel sl, BlockPos childHome) {
        LarderStore store = LarderStore.get(sl);
        double larder = store.get(childHome);
        double before = larder;
        int given = 0;
        while (holding >= FoodEconomy.BAND_HIGH) {
            holding -= 1.0;
            larder += 1.0;
            given++;
        }
        if (given > 0) {
            store.set(childHome, larder);
            SimEvents.event(this, "노인공유", String.format("자식 집 @%d,%d 저장고 %.0f→%.0f (+%d)",
                    childHome.getX(), childHome.getZ(), before, larder, given));
        }
        return given;
    }

    /** 무대 세팅용 — 보유 H를 직접 지정(구 setDayHarvest 호환). */
    public void setDayHarvest(double harvest) {
        this.holding = harvest;
    }

    public double getDayHarvest() {
        return holding;
    }

    public double getHolding() {
        return holding;
    }

    /** 위급(R6) — 소지 식량 고갈 임박. */
    public boolean isCritical() {
        return holding < FoodEconomy.CRITICAL;
    }

    /** 거처 반경 안인가(정산의 home 판정 — 길찾기 아님, 거리 체크 한 줄). */
    public boolean isHome() {
        return homePos != null && blockPosition().distSqr(homePos) <= 36.0;
    }

    /** 저장고에 정수 1개 이상 있나 — R6/A-3의 "귀가 vs 채집 강행" 분기. */
    public boolean larderHasFood() {
        return homePos != null && level() instanceof ServerLevel sl
                && LarderStore.get(sl).get(homePos) >= 1.0;
    }

    /** 저장고 넉넉(R4) — 가족 하루소모 × 시간지향 일수(미래3/기본2/현재1) 이상이면 비제공자는 쉼. */
    public boolean larderComfortable() {
        return homePos != null && level() instanceof ServerLevel sl && individual != null
                && LarderStore.get(sl).get(homePos)
                        >= cachedFamilyNeed * FoodEconomy.comfortDays(individual);
    }

    /** 제공자 역할(가족틱이 갱신) — 남편 또는 성년 홀로 가장. R4에서 항상 채집. */
    public boolean isProviderRole() {
        return cachedProvider;
    }

    /** 귀가 도착 시 즉석 입출금 — 가족틱(1200틱)을 기다리지 않고 순수 settleHome을 자신 1인으로. */
    public void selfSettle(ServerLevel sl) {
        if (homePos == null || individual == null) {
            return;
        }
        LarderStore store = LarderStore.get(sl);
        double larder = store.getOrInit(homePos,
                fastSettle ? 0.0 : FoodEconomy.initialLarder(cachedFamilyNeed));
        double before = holding;
        FoodEconomy.Eater self = new FoodEconomy.Eater(individual, getStage(), holding, true);
        larder = FoodEconomy.settleHome(larder, List.of(self));
        holding = self.holding;
        store.set(homePos, larder);
        double delta = holding - before; // 입금(−)이면 여분 저장, 인출(+)이면 꺼내 먹음
        if (delta <= -1.0 + 1.0E-9) {
            SimEvents.event(this, "입금", String.format("%d개 저장 → 저장고 %.0f",
                    (int) Math.round(-delta), larder));
        } else if (delta >= 1.0 - 1.0E-9) {
            SimEvents.event(this, "인출", String.format("%d개 꺼냄 → 저장고 %.0f",
                    (int) Math.round(delta), larder));
        }
    }

    /**
     * 위급 식구에게 소지분을 나눠준다(B 연출, 순수 재분배 — L 안 거침).
     *
     * @return <b>실제로 전달된 양</b>(보유 부족 시 요청보다 적거나 0) — 로그는 이 결과값만 기록할 것.
     */
    public double shareFoodTo(MimicEntity target, double amount) {
        double give = Math.min(amount, holding);
        if (give <= 0.0) {
            return 0.0;
        }
        holding -= give;
        target.holding += give;
        target.hungerGraceTicks = 0;
        return give;
    }

    public double getLastSurplus() {
        return lastSurplus;
    }

    public boolean wasLastFed() {
        return lastFed;
    }

    /**
     * 유아 돌봄/아사 (육아 클래스, §7). 유아는 소모 주기마다 곁(FEED_RADIUS)에 성인이 있어야 먹는다.
     * 없으면 굶주림↑ → 임계 초과 시 아사. 부모(육아 클래스)가 곁에 머물수록 유아 생존↑.
     */
    private void infantCareTick() {
        if (getStage() != LifeStage.INFANT) {
            return;
        }
        // 무대 검증(fastCare)은 종전 그대로 틱 주기 즉시 판정 — checkall ④⑯ 판정 불변.
        if (fastCare) {
            if ((level().getGameTime() + getId()) % CARE_INTERVAL == 0) {
                judgeCare(adultNear());
            }
            return;
        }
        // 평상: "저녁 진입 첫 틱 1회 샘플"은 귀가 타이밍의 우연으로 정상 가족 유아가 죽고,
        // 밤엔 온 가족이 모여 육아 특성이 생사를 못 가르던 결함 — 낮 시간대 다중 샘플로 교체.
        // 낮 동안 한 번이라도 성인이 곁(5블록)에 있었으면 그날은 돌봄 래치, 날이 바뀔 때 평가.
        // 시계는 절대시간(gameTime) 단일축 — 하늘 시계 정지 월드에서도 판정이 멈추지 않는다.
        // careTimeScale 은 하루 길이·창·간격을 같은 비율로 압축할 뿐, 샘플→래치→롤오버 평가라는
        // 실경로 코드를 그대로 지난다(검증 전용 훅이 별도 구현이 되지 않도록 — 규칙 9).
        long dayLen = 24000L / careTimeScale;
        long now = level().getGameTime();
        long day = now / dayLen;
        long tod = now % dayLen;
        if (lastCareDay == 0L) {
            lastCareDay = day; // 미초기화(신생아·구 세이브) — 태어난 부분일은 관찰만, 평가 없음
            return;
        }
        if (!attendedToday && tod >= CARE_SAMPLE_START / careTimeScale
                && tod < CARE_SAMPLE_END / careTimeScale
                && (now + getId()) % Math.max(1L, CARE_SAMPLE_INTERVAL / careTimeScale) == 0
                && adultNear()) {
            attendedToday = true;
        }
        if (day != lastCareDay) {
            lastCareDay = day;
            judgeCare(attendedToday); // 전일 래치 평가 — 낮에 실제로 방치됐는가가 기준
            attendedToday = false;
        }
    }

    /** 하루 돌봄 판정의 공통 결말 — 로그 문구·관찰 태그는 종전과 동일(판정 시점만 바뀜). */
    private void judgeCare(boolean attended) {
        if (attended) {
            careHunger = 0;
            StageObserver.record(this.getId(), "infant:fed");
            SimEvents.event(this, "급식", "곁에 성인 있음 → 굶주림 0");
        } else {
            careHunger++;
            SimEvents.event(this, "방치", "성인 부재 → 굶주림 " + careHunger + "/" + CARE_DEATH);
            if (careHunger >= CARE_DEATH) {
                StageObserver.record(this.getId(), "infant:starved");
                SimEvents.event(this, "아사", "연속 방치 " + careHunger + "일 → 사망");
                this.discard();
            }
        }
    }

    public void setFastCare(boolean fast) {
        this.fastCare = fast;
    }

    /** 검증용 — 낮 샘플 육아의 시간 압축 배율(600 = 하루 40틱). 실경로(샘플·래치·롤오버)를 그대로 지난다. */
    public void debugSetCareTimeScale(int scale) {
        this.careTimeScale = Math.max(1, scale);
        this.lastCareDay = 0L; // 압축 시계 기준으로 재초기화
    }

    private boolean adultNear() {
        for (MimicEntity m : level().getEntitiesOfClass(MimicEntity.class, getBoundingBox().inflate(FEED_RADIUS))) {
            if (m != this && m.getIndividual() != null
                    && (m.getStage() == LifeStage.ADULT || m.getStage() == LifeStage.ELDER)) {
                return true; // 노년도 돌봄 성인으로 인정 — 마실 육아(조부모)가 유효해지는 지점
            }
        }
        return false;
    }

    /**
     * 지금 육아에 매인 부모인가 (유아 자식 + 무시 아닌 육아 클래스 — <b>성별 무관</b>). 이때는 채집 대신
     * 육아. 남성이 매이면 채집 1.5×를 버리는 셈 → 경제가 남무심·여적극 조합을 자연선택하는지 관측 대상.
     */
    public boolean isCaregiverBound() {
        if (getStage() != LifeStage.ADULT || homePos == null || individual == null) {
            return false;
        }
        if (individual.parentingCare() == ParentingClass.NEGLECTFUL) {
            return false; // 무시 = 자유 배회 → 채집 가능
        }
        return hasInfantAtHome();
    }

    /** 같은 거처에 유아 자식이 있나 (육아 goal 판정용). */
    public boolean hasInfantAtHome() {
        if (homePos == null) {
            return false;
        }
        for (MimicEntity m : level().getEntitiesOfClass(MimicEntity.class, getBoundingBox().inflate(20.0))) {
            if (m.getStage() == LifeStage.INFANT && homePos.equals(m.getHomePos())) {
                return true;
            }
        }
        return false;
    }


    /**
     * 유아·소년이 몬스터 곁에서도 못 싸움을 무대 검증에 보고 (설계서 §7 무방비).
     * 무대 검증 중에만 스캔 → 평상시 오버헤드 0. 전투 goal 은 성년 전용이라 AI 를 안 건드림.
     */
    private void observeTooYoung() {
        if (tooYoungObserved || !StageObserver.isActive() || SurvivalRules.canFight(getStage())) {
            return;
        }
        for (Monster m : level().getEntitiesOfClass(Monster.class, getBoundingBox().inflate(5.0))) {
            if (m instanceof Zombie || m instanceof Skeleton) {
                StageObserver.record(getId(), "combat:tooyoung");
                tooYoungObserved = true;
                return;
            }
        }
    }

    /** 생애단계 성장 (설계서 §7). 임계 틱 경과 시 다음 단계로 전환하고 무대 검증에 보고. */
    private void growthTick() {
        LifeStage stage = getStage();
        growthTicks++;
        // 단계별 임계: 유아 2일·소년 3일(혼기·모성애 배율) · 청년 35일 · 노년 8일±특성. fast 무대 40틱.
        int threshold;
        switch (stage) {
            case INFANT -> threshold = fastGrowth ? 40
                    : (int) (2 * 24000 * SurvivalRules.growthMult(stage, individual, cachedMaternal));
            case BOY -> threshold = fastGrowth ? 40
                    : (int) (3 * 24000 * SurvivalRules.growthMult(stage, individual, cachedMaternal));
            case ADULT -> threshold = fastGrowth ? 40 : Elder.ADULT_DAYS * 24000;
            case ELDER -> threshold = fastGrowth ? 40
                    : (individual != null ? Elder.elderDays(individual) : Elder.ELDER_BASE_DAYS) * 24000;
            default -> throw new IllegalStateException();
        }
        if (growthTicks < threshold) {
            return;
        }
        growthTicks = 0;
        if (stage == LifeStage.ELDER) { // 노년 마감 → 자연사(소지 H는 함께 소멸, 저장고·건물은 유산)
            com.evosim.mod.stage.StageObserver.record(this.getId(), "elder:died");
            SimEvents.event(this, "자연사", String.format("노년 마감 — 세대%d · 자식 %d명",
                    individual != null ? individual.generation() : 0, childrenBorn));
            discard();
            return;
        }
        LifeStage next = switch (stage) {
            case INFANT -> LifeStage.BOY;
            case BOY -> LifeStage.ADULT;
            default -> LifeStage.ELDER; // ADULT → 노년
        };
        setStage(next);
        if (stage == LifeStage.INFANT) {
            careHunger = 0;        // 유아 전용 상태 청소 — 소년 이후 stale 값이 NBT 에 남지 않게
            attendedToday = false;
        }
        if (next == LifeStage.ELDER) {
            clearCourtshipPool(); // 노년 = 구애 은퇴 — 후보·거절 기록을 사망까지 들고 있지 않게
        }
        com.evosim.mod.stage.StageObserver.record(this.getId(), "grow:" + next.name());
        SimEvents.event(this, "성장", stageKo(stage) + "→" + stageKo(next));
    }

    /** 사망 순간 관찰 로그 (누가·무엇에·어디서 죽었나 §14). 전투 사망·몬스터 처치 등 원인 포함. */
    /** 질식(벽 낌) 데미지 무시 — 건축 연출 중 서로/블럭에 잠시 겹쳐도 질식사하지 않게 한다. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypes.IN_WALL) || source.is(DamageTypes.SWEET_BERRY_BUSH)) {
            return false; // 질식·베리 가시 무시(자기 밭에서 안 다침)
        }
        return super.hurt(source, amount);
    }

    @Override
    public void die(DamageSource source) {
        if (!level().isClientSide) {
            SimEvents.event(this, "사망", "원인=" + source.getMsgId()
                    + (individual != null ? " · 세대" + individual.generation() : "")
                    + (stageActor ? " [무대]" : ""));
        }
        super.die(source);
    }

    private static String stageKo(LifeStage s) {
        return switch (s) {
            case INFANT -> "유아";
            case BOY -> "소년";
            case ADULT -> "성년";
            case ELDER -> "노년";
        };
    }

    /** 생애단계 배율만큼 히트박스도 축소(외형과 대략 일치). */
    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return super.getDimensions(pose).scale(getStage().modelScale());
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        MobSpawnType reason, @Nullable SpawnGroupData data,
                                        @Nullable CompoundTag tag) {
        if (individual == null) {
            // 게임 스폰용(월드 랜덤 시드). 헤드리스 검증은 시드 고정 별도.
            DeterministicRng rng = new DeterministicRng(this.random.nextLong());
            // 1세대 id 도 자식과 같은 난수 long 공간으로(홀수·비영) — 엔티티 id(작은 정수)와의
            // 공간 혼용이 이론상 조상 명단/배우자 링크 충돌을 만들 수 있던 것을 통일(L-9).
            setIndividual(Genetics.randomFirstGen(Math.abs(this.random.nextLong() | 1L), rng));
        }
        return super.finalizeSpawn(level, difficulty, reason, data, tag);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("Female", isFemale());
        tag.putInt("Stage", this.entityData.get(STAGE));
        tag.putInt("GrowthTicks", growthTicks);
        tag.putBoolean("FastGrowth", fastGrowth);
        tag.putLong("LastBirth", lastBirthTick);
        tag.putInt("ChildrenBorn", childrenBorn);
        tag.putInt("CareHunger", careHunger);
        tag.putLong("LastCareDay", lastCareDay);
        tag.putBoolean("CareLatch", attendedToday); // 낮 돌봄 래치 — 리로드로 그날 돌봄이 증발하지 않게
        tag.putBoolean("FastCare", fastCare);
        tag.putDouble("Holding", holding);
        tag.putInt("HungerGrace", hungerGraceTicks); // 재로그인해도 아사 클럭 유지(B-4)
        tag.putLong("LastForage", lastForageSuccessTick);
        tag.putLong("SettledTick", settledTick);
        tag.putLong("TravelUntil", courtTravelUntil);
        tag.putLong("TravelTarget", courtTravelTarget);
        tag.putDouble("LastSurplus", lastSurplus);
        tag.putBoolean("LastFed", lastFed);
        tag.putBoolean("FastSettle", fastSettle);
        tag.putBoolean("StageActor", stageActor); // 무대 표식 유지 — 리로드 후 원장 재등록 방지
        tag.putLong("TenantFarm", tenantFarm);    // 봉건 소작 관계(상시) — 리로드에도 유지
        tag.putInt("TenantStreak", tenantStreak);
        tag.putLong("SpouseId", spouseId);
        tag.putBoolean("Widowed", widowed);
        tag.putLong("LonelySince", lonelySinceTick); // 족외혼 클럭 — 리로드로 3일 재대기 방지
        tag.putInt("RejGiven", rejectionsGiven);     // 눈낮춤 진행도 — 리로드로 수렴이 리셋되지 않게
        tag.putBoolean("WasCrit", wasCritical);      // 위급 전이 감지 — 리로드 직후 중복 로그 방지
        tag.putByte("HomeFacing", homeFacing);
        tag.putBoolean("Building", building);
        if (individual != null) {
            tag.put("Individual", IndividualNbt.save(individual)); // 특성·육아·가계 지속(Phase 6)
        }
        if (homePos != null) {
            tag.putInt("HomeX", homePos.getX());
            tag.putInt("HomeY", homePos.getY());
            tag.putInt("HomeZ", homePos.getZ());
        }
        if (birthPos != null) {
            tag.putInt("BirthX", birthPos.getX());
            tag.putInt("BirthY", birthPos.getY());
            tag.putInt("BirthZ", birthPos.getZ());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Female")) {
            setFemale(tag.getBoolean("Female"));
        }
        if (tag.contains("Stage")) {
            this.entityData.set(STAGE, tag.getInt("Stage"));
        }
        growthTicks = tag.getInt("GrowthTicks");
        fastGrowth = tag.getBoolean("FastGrowth");
        if (tag.contains("LastBirth")) {
            lastBirthTick = tag.getLong("LastBirth");
        }
        childrenBorn = tag.getInt("ChildrenBorn");
        careHunger = tag.getInt("CareHunger");
        if (tag.contains("LastCareDay")) {
            lastCareDay = tag.getLong("LastCareDay");
        }
        attendedToday = tag.getBoolean("CareLatch");
        fastCare = tag.getBoolean("FastCare");
        holding = tag.contains("Holding") ? tag.getDouble("Holding") : 1.5; // 구 세이브 호환(시작값)
        hungerGraceTicks = tag.getInt("HungerGrace");
        lastForageSuccessTick = tag.getLong("LastForage"); // 0(구 세이브)이면 첫 틱에 now로 초기화
        settledTick = tag.getLong("SettledTick");
        courtTravelUntil = tag.getLong("TravelUntil");
        courtTravelTarget = tag.getLong("TravelTarget");
        lastSurplus = tag.getDouble("LastSurplus");
        if (tag.contains("LastFed")) {
            lastFed = tag.getBoolean("LastFed");
        }
        fastSettle = tag.getBoolean("FastSettle");
        stageActor = tag.getBoolean("StageActor");
        tenantFarm = tag.getLong("TenantFarm");
        tenantStreak = tag.getInt("TenantStreak");
        spouseId = tag.getLong("SpouseId");
        widowed = tag.getBoolean("Widowed");
        lonelySinceTick = tag.contains("LonelySince") ? tag.getLong("LonelySince") : -1L;
        rejectionsGiven = tag.getInt("RejGiven");
        wasCritical = tag.getBoolean("WasCrit");
        homeFacing = tag.getByte("HomeFacing");
        building = tag.getBoolean("Building");
        if (tag.contains("Individual")) {
            this.individual = IndividualNbt.load(tag.getCompound("Individual"));
            refreshStageAttributes(); // 성별 배율 등 재적용
        }
        if (tag.contains("HomeX")) {
            homePos = new BlockPos(tag.getInt("HomeX"), tag.getInt("HomeY"), tag.getInt("HomeZ"));
        }
        if (tag.contains("BirthX")) {
            birthPos = new BlockPos(tag.getInt("BirthX"), tag.getInt("BirthY"), tag.getInt("BirthZ"));
        }
    }
}
