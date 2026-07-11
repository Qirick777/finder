package com.evosim.mod.entity;

import com.evosim.core.Activity;
import com.evosim.core.Courtship;
import com.evosim.core.DeterministicRng;
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
import com.evosim.core.ParentingClass;
import com.evosim.core.Reproduction;
import com.evosim.core.Roaming;
import com.evosim.core.Schedule;
import com.evosim.core.Settlement;
import com.evosim.core.Sex;
import com.evosim.core.SurvivalRules;
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
    private final Set<Integer> rejectedBy = new HashSet<>();            // 내가 포기/거절당한 상대 id
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
    private static final double ZOMBIE_AGGRO_RANGE = 20.0; // 이 안의 임자 없는 좀비가 미믹을 노림

    // 유아 돌봄/아사 (육아 클래스): 하루 급식 시각에 곁에 성인 없으면 굶주림↑, 임계 초과 시 아사.
    private int careHunger = 0;
    private long lastCareDay = Long.MIN_VALUE; // 마지막 급식 판정한 절대 일자(하루 1회 보장)
    private boolean fastCare = false;          // 무대 검증용 초고속 급식(틱 주기)
    private static final int FEEDING_TIME = 13000; // 하루 중 급식 시각(밤, 가족 수렴 §4)
    private static final int CARE_INTERVAL = 20;   // fast 모드 소모 주기(틱)
    private static final int CARE_DEATH = 3;       // 연속 방치 임계 → 아사 (평상시 3일)
    private static final double FEED_RADIUS = 5.0; // 이 반경 내 성인이 있으면 먹여줌

    // 식량 경제 v2 (FoodEconomy): 개인 보유 H(배부름+소지 통합) + 거처 저장고 L(정수 입출금).
    private double holding = 1.5;               // H — 시작 1.5(밴드 [1,2) 안, 콜드스타트 완충)
    private int hungerGraceTicks = 0;           // H=0 지속 틱(아사 유예 클럭, NBT 저장 — B-4)
    private boolean wasCritical = false;        // 위급 전이 감지(로그 1회용, 휘발)
    private double lastSurplus = 0.0;           // 마지막 정산 후 저장고 잔량(스캐너 표시)
    private boolean lastFed = true;             // 위급 아님(스캐너 표시)
    private boolean fastSettle = false;         // 무대 검증용 초고속(시간 600배 압축)
    private double cachedFamilyNeed = 6.0;      // 가족틱이 갱신하는 가족 하루소모 캐시(goal용)
    private boolean cachedProvider = true;      // 가족틱이 갱신하는 제공자 역할 캐시(R4)
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
     * 여성은 힘/체력 40%↓. 단계 성장·개체 부여 때마다 호출.
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
            attack.setBaseValue(BASE_ATTACK * fem);
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
        this.goalSelector.addGoal(3, new MimicReturnGoal(this));    // 식량 귀가: 넣으러/꺼내러(v2, 밥이 구애보다 먼저)
        this.goalSelector.addGoal(3, new MimicShareGoal(this));     // 가족 나눔: 위급 식구에게 배달(v2 B)
        this.goalSelector.addGoal(3, new MimicCourtshipGoal(this)); // 방랑자 구애(§10, 배회 시간)
        this.goalSelector.addGoal(4, new MimicHomeGoal(this));      // 밤 귀가(§3, 취침·정산 대비)
        this.goalSelector.addGoal(5, new MimicRestGoal(this));      // 취침(집에서 밤새 쉼)
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

    /** 활동반경 앵커 — 거처가 있으면 거처, 없으면(방랑자) 태어난 곳. 리시가 이 지점 반경으로 묶는다. */
    public BlockPos roamAnchor() {
        return homePos != null ? homePos : getBirthPos();
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
        return countAdultsAtHome() <= 1 ? MateHome.Status.LONE_OWNER : MateHome.Status.FAMILY_MEMBER;
    }

    private int countAdultsAtHome() {
        if (homePos == null) {
            return 0;
        }
        int n = 0;
        for (MimicEntity m : level().getEntitiesOfClass(MimicEntity.class, getBoundingBox().inflate(48.0))) {
            if (m.isAlive() && m.getStage() == LifeStage.ADULT && homePos.equals(m.getHomePos())) {
                n++;
            }
        }
        return n;
    }

    /** 배우자가 아직 살아있나(거처/근처에 같은 Individual.id 존재). */
    private boolean spouseAlive() {
        if (spouseId == 0L) {
            return false;
        }
        for (MimicEntity m : level().getEntitiesOfClass(MimicEntity.class, getBoundingBox().inflate(64.0))) {
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
            growthTick();
            observeTooYoung();
            attractZombies();  // 근처 좀비가 미믹을 공격 대상으로 삼게 함
            mateTick();        // 구애 인식·후보 등록(노동/배회). 실제 구애 이동은 MimicCourtshipGoal
            buildTick();       // 거처 건축(짓는 연출) — 리더가 한 칸씩
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
        for (Zombie z : level().getEntitiesOfClass(Zombie.class, getBoundingBox().inflate(ZOMBIE_AGGRO_RANGE))) {
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
        if (individual == null || getStage() != LifeStage.ADULT) {
            mateState = MateState.IDLE;
            return;
        }
        // 사별 감지: 배우자가 살아있지 않으면 과부/홀아비 → 재구애 참여.
        if (spouseId != 0L && !widowed && (level().getGameTime() + getId()) % 40 == 0) {
            if (!spouseAlive()) {
                widowed = true;
                mateState = MateState.SEARCHING;
            }
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
        if (active) {
            perceive(phase);
            searchTimer++;
            if (mateState == MateState.IDLE || mateState == MateState.PAIRED) {
                mateState = MateState.SEARCHING;
            }
        }
    }

    /** 인식 범위 내 이성 독신 성년(비근친·비거절)을 후보로 추가하고 매력 내림차순 유지. */
    private void perceive(Schedule.Phase phase) {
        int lvl = individual.mateChoice().ordinal();
        double range = phase == Schedule.Phase.WORK
                ? WORK_PERCEPT_BASE + lvl * WORK_PERCEPT_PER
                : WANDER_PERCEPT_BASE + lvl * WANDER_PERCEPT_PER;
        for (MimicEntity m : level().getEntitiesOfClass(MimicEntity.class, getBoundingBox().inflate(range))) {
            if (m == this || m.getIndividual() == null || !m.isSingleAdult()
                    || m.isFemale() == isFemale()) {
                continue;
            }
            int id = m.getId();
            if (rejectedBy.contains(id) || candidateCharm.containsKey(id)) {
                continue;
            }
            if (Kinship.isRelated(individual, m.getIndividual())) {
                continue; // 근친 회피 §13-E
            }
            candidateCharm.put(id, Multipliers.charmScore(individual, m.getIndividual()));
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
            return false; // 이미 짝 있음/건축 중 → 자동 거절
        }
        // 상호구애 특례: 내가 이 상대를 구애 중이면 판정 없이 성사.
        if (mateState == MateState.COURTING && courtTargetId == suitor.getId()) {
            logCourt(suitor, true, Multipliers.charmScore(individual, si), 1, candidates.size(), 100);
            pairWith(suitor);
            return true;
        }
        int charm = Multipliers.charmScore(individual, si);
        int n = candidates.size();
        int better = 0;
        for (int c : candidateCharm.values()) {
            if (c > charm) {
                better++;
            }
        }
        int k = individual.mateChoice().k();
        double p = Courtship.acceptChance(better, n, k); // 밸런싱 스케일 적용값(GUI %도 이 값)
        boolean accept = getRandom().nextDouble() < p;
        logCourt(suitor, accept, charm, better + 1, n, (int) Math.round(p * 100));
        if (accept) {
            pairWith(suitor);
            return true;
        }
        return false;
    }

    /** 수락/거절을 양쪽 기록에 남긴다 (내 RECEIVED + 구애자 COURTED). */
    private void logCourt(MimicEntity suitor, boolean accepted, int charm, int rank, int pool, int percent) {
        long now = level().getDayTime();
        addCourtLog(new CourtRecord(now, CourtRecord.Kind.RECEIVED, suitor.getId(),
                "미믹#" + suitor.getId(), accepted, charm, rank, pool, percent));
        suitor.addCourtLog(new CourtRecord(now, CourtRecord.Kind.COURTED, getId(),
                "미믹#" + getId(), accepted, charm, rank, pool, percent));
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
        heartEffect(this);
        heartEffect(other);
        if (level() instanceof ServerLevel sl) {
            resolveHome(sl, other);
        }
        StageObserver.record(getId(), "mating:pair");
        SimEvents.event(this, "짝성립", "상대 #" + other.getId());
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

    /** guest를 host의 거처로 입주. guest가 단독 거처주였다면 그 집은 폐기(모닥불 끔). */
    private static void moveInto(ServerLevel sl, MimicEntity host, MimicEntity guest) {
        if (guest.getHomePos() != null && guest.homeStatus() == MateHome.Status.LONE_OWNER) {
            guest.abandonHome(sl); // 원래 단독 거처 폐기
        }
        guest.setHomePos(host.getHomePos());
        guest.homeFacing = host.homeFacing;
        relightHearth(sl, host.getHomePos(), host.getHomeFacingDir());
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
        List<int[]> existing = collectExistingHomes(sl);
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
    private List<int[]> collectExistingHomes(ServerLevel sl) {
        List<int[]> existing = new ArrayList<>();
        for (MimicEntity m : sl.getEntitiesOfClass(MimicEntity.class, getBoundingBox().inflate(160.0))) {
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
            ABANDONED_HOMES.add(new int[] {homePos.getX(), homePos.getY(), homePos.getZ(),
                    facing.get2DDataValue()});
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
            if (!(st.getBlock() instanceof MimicHearthBlock) || st.getValue(MimicHearthBlock.LIT)) {
                ABANDONED_HOMES.remove(i);
                return null; // 무효 항목 정리
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

    /** 흙으로 메울 수 있는 칸인가(빈 칸·유체·풀·눈만 — 기존 지면은 건드리지 않음). */
    private static boolean isFillable(net.minecraft.world.level.block.state.BlockState st) {
        return st.isAir() || !st.getFluidState().isEmpty()
                || st.is(Blocks.GRASS) || st.is(Blocks.TALL_GRASS)
                || st.is(Blocks.FERN) || st.is(Blocks.LARGE_FERN) || st.is(Blocks.SNOW);
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

    /** 전투/피격 중인가 — 피격 직후·최근 가해자 기억·나를 노리는 근처 좀비. 건축 정지 판단용. */
    public boolean isUnderThreat() {
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
        if (!isPlaceable(sl, p.pos()) || cellOccupiedByOther(sl, p.pos())) {
            return false; // 이미 채워짐/장애물 · 다른 구성원이 그 칸에 있으면 파묻지 않음
        }
        sl.setBlockAndUpdate(p.pos(), blockFor(p).defaultBlockState());
        return true;
    }

    /** 그 자리에 부지 블록을 놓을 수 있나(빈 칸·풀·눈만 대체). */
    private static boolean isPlaceable(ServerLevel sl, BlockPos pos) {
        var cur = sl.getBlockState(pos);
        return cur.isAir() || cur.is(Blocks.GRASS) || cur.is(Blocks.TALL_GRASS)
                || cur.is(Blocks.FERN) || cur.is(Blocks.SNOW);
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
        if (destroy && home != null && level() instanceof ServerLevel sl && !anyResidentAt(sl, home)) {
            BlockPos hp = HomeStructure.hearthPos(home, Direction.from2DDataValue(facing));
            var st = sl.getBlockState(hp);
            if (st.getBlock() instanceof MimicHearthBlock && st.getValue(MimicHearthBlock.LIT)) {
                sl.setBlockAndUpdate(hp, st.setValue(MimicHearthBlock.LIT, Boolean.FALSE));
                ABANDONED_HOMES.add(new int[] {home.getX(), home.getY(), home.getZ(), facing});
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
            if (!(e instanceof MimicEntity m) || !m.isAlive() || !m.isSingleAdult()
                    || m.getIndividual() == null) {
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

    /** 거절/포기: 상대를 rejectedBy에 넣고 후보에서 제거(재구애 방지). */
    public void giveUpOn(int id) {
        rejectedBy.add(id);
        candidates.remove((Integer) id);
        candidateCharm.remove(id);
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
                getHealth() < getMaxHealth());
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
        if (individual != null && !isCritical()
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
        if (individual == null || building) {
            return;
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

        // 우선순위 구성: 남편(UUID 최소 성년 남성) → 자식(미성년) → 아내(그 외 성년).
        MimicEntity father = null;
        for (MimicEntity m : fam) {
            if (m.getIndividual() != null && m.getStage() == LifeStage.ADULT && !m.isFemale()
                    && (father == null || m.getUUID().compareTo(father.getUUID()) < 0)) {
                father = m;
            }
        }
        List<MimicEntity> ordered = new ArrayList<>(fam.size());
        if (father != null) {
            ordered.add(father);
        }
        for (MimicEntity m : fam) {
            if (m.getIndividual() != null && m.getStage() != LifeStage.ADULT) {
                ordered.add(m);
            }
        }
        for (MimicEntity m : fam) {
            if (m.getIndividual() != null && m.getStage() == LifeStage.ADULT && m != father) {
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
        int deposited = 0;
        int withdrawn = 0;
        double holdSum = 0.0;
        boolean infantFed = false;
        for (int i = 0; i < ordered.size(); i++) {
            MimicEntity m = ordered.get(i);
            FoodEconomy.Eater e = eaters.get(i);
            double delta = e.holding - m.holding; // 정산 후 − 전(정수 입출금 집계용)
            if (delta >= 1.0 - 1.0E-9) {
                withdrawn += (int) Math.round(delta);
            } else if (delta <= -1.0 + 1.0E-9) {
                deposited += (int) Math.round(-delta);
            }
            if (m.getStage() == LifeStage.INFANT && delta > 1.0E-9) {
                infantFed = true;
            }
            m.holding = e.holding;
            m.lastSurplus = larder;
            m.lastFed = !m.isCritical();
            m.cachedFamilyNeed = need;
            m.cachedProvider = (m == father) || (father == null && m.getStage() == LifeStage.ADULT);
            holdSum += m.holding;
            switch (m.getStage()) {
                case ADULT -> adults++;
                case BOY -> boys++;
                case INFANT -> infants++;
            }
        }
        boolean starving = FoodEconomy.anyStarvingHome(eaters);

        // D 연출: 유아가 저장고에서 채워졌으면 어미의 행위로 귀속(숫자는 동일 — 로그만).
        if (infantFed) {
            MimicEntity mother = firstAdultFemale(ordered);
            SimEvents.event(mother != null ? mother : this, "육아", "저장고에서 꺼내 아기를 먹임");
        }
        // B-2: 과부 가구 붕괴는 허용 경로 — 로그만 남긴다(구제는 설계 결정 대기).
        if (father == null && starving && homePos != null) {
            SimEvents.note(sl, "과부가구", "남편 없는 가구 굶주림 진행(허용된 붕괴 경로) — 거처 "
                    + homePos.toShortString());
        }

        // R5 번식: (L − 출산비용 − 하루소모) ≥ 성년수+1(±특성) & 무굶주림 & 쿨다운·상한·과밀.
        if (homePos != null && father != null && father.getIndividual() != null) {
            MimicEntity mother = firstAdultFemale(ordered);
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
                        && FoodEconomy.canReproduce(larder, need, adults, adj, starving)) {
                    larder -= FoodEconomy.BIRTH_COST; // 출산 비용 차감(A-2) — 연쇄 출산 제동
                    mother.spawnChild(sl, father);
                }
            }
        }

        // 옆 정원 베리: 최하위 — 하루소모·번식 몫(비용+임계)을 뺀 잔여로만.
        if (homePos != null) {
            int bushCount = countBerries(sl);
            double reproReserve = FoodEconomy.BIRTH_COST + adults + 1;
            int n = BerryEconomy.plant(larder, need, reproReserve, bushCount, BERRY_CAP);
            if (n > 0) {
                int done = plantBerries(sl, n);
                if (done > 0) {
                    SimEvents.event(this, "베리",
                            "옆 정원 +" + done + " (누적 " + (bushCount + done) + "/" + BERRY_CAP + ")");
                }
            }
            store.set(homePos, larder);
            // 가계 시계열(≈1분/가구): 저장고·구성·소지합·하루소모·이번 입출금 — 밸런싱 근거의 근간.
            SimEvents.household(sl, homePos, larder, adults, boys, infants, holdSum, need,
                    deposited, withdrawn);
        }
    }

    /** 대표 선출(A-4, 결정론): 살아있는 <b>UUID 최소 성년</b>, 성년 없으면 UUID 최소 구성원. 매번 재계산. */
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

    private static MimicEntity firstAdultFemale(List<MimicEntity> ordered) {
        for (MimicEntity m : ordered) {
            if (m.isFemale() && m.getStage() == LifeStage.ADULT) {
                return m;
            }
        }
        return null;
    }

    /** 같은 거처(또는 독신이면 자기 자신)를 공유하는 가족 구성원. */
    private List<MimicEntity> householdMembers() {
        List<MimicEntity> fam = new ArrayList<>();
        if (homePos == null) {
            fam.add(this); // 방랑자 = 1인 가정(자급자족)
            return fam;
        }
        for (MimicEntity m : level().getEntitiesOfClass(MimicEntity.class, getBoundingBox().inflate(24.0))) {
            if (m.getIndividual() != null && homePos.equals(m.getHomePos())) {
                fam.add(m);
            }
        }
        if (fam.isEmpty()) {
            fam.add(this);
        }
        return fam;
    }

    /** 자식 하나를 낳아 거처에 배치 (저장고 잉여 확보 후). 어미 기준으로 기록. */
    private void spawnChild(ServerLevel sl, MimicEntity father) {
        DeterministicRng rng = new DeterministicRng(getRandom().nextLong());
        int gen = Math.max(individual.generation(), father.getIndividual().generation()) + 1;
        long childId = Math.abs(getRandom().nextLong() | 1L);
        Individual childInd = Genetics.breed(childId, individual, father.getIndividual(), rng, gen, null);

        MimicEntity child = ModEntities.MIMIC.get().create(sl);
        if (child == null) {
            return;
        }
        child.setIndividual(childInd);
        child.setStage(LifeStage.INFANT);
        BlockPos where = homePos != null ? homePos : blockPosition();
        child.setHomePos(homePos);
        child.setBirthPos(where); // 태어난 위치 확정(애향심 신축 앵커·분가 기준)
        child.moveTo(where.getX() + 0.5, where.getY(), where.getZ() + 0.5, 0.0F, 0.0F);
        sl.addFreshEntity(child);

        lastBirthTick = level().getGameTime();
        childrenBorn++;
        StageObserver.record(this.getId(), "birth");
        SimEvents.event(this, "출산", "자식 #" + child.getId() + " 세대" + gen
                + " · 부친 #" + father.getId() + " (누적 " + childrenBorn + ") — 잉여식량 확보");
    }

    public void setFastSettle(boolean fast) {
        this.fastSettle = fast;
    }

    public boolean isFastSettle() {
        return fastSettle;
    }

    /** 채집/사냥으로 확보한 식량을 소지분 H에 더한다(R2). 방랑자(집 없음)는 밴드 상한에서 컷. */
    public void addHarvest(double food) {
        holding += food;
        if (homePos == null && holding > FoodEconomy.BAND_HIGH) {
            holding = FoodEconomy.BAND_HIGH; // 입금할 곳이 없으니 초과분은 버려짐(과다 확장 방지)
        }
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

    /** 저장고 넉넉(R4) — 가족 하루소모 2일치 이상이면 비제공자는 쉼. */
    public boolean larderComfortable() {
        return homePos != null && level() instanceof ServerLevel sl
                && LarderStore.get(sl).get(homePos) >= cachedFamilyNeed * 2.0;
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

    /** 위급 식구에게 소지분을 나눠준다(B 연출, 순수 재분배 — L 안 거침). */
    public void shareFoodTo(MimicEntity target, double amount) {
        double give = Math.min(amount, holding);
        if (give <= 0.0) {
            return;
        }
        holding -= give;
        target.holding += give;
        target.hungerGraceTicks = 0;
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
        // 급식 타이밍: 평상시엔 하루 1회(급식 시각 이후), 무대 검증은 fast(틱 주기). 연산 절약.
        boolean feedNow;
        if (fastCare) {
            feedNow = (level().getGameTime() + getId()) % CARE_INTERVAL == 0;
        } else {
            long day = level().getGameTime() / 24000L;
            long timeOfDay = level().getDayTime() % 24000L;
            feedNow = day != lastCareDay && timeOfDay >= FEEDING_TIME;
            if (feedNow) {
                lastCareDay = day;
            }
        }
        if (!feedNow) {
            return;
        }
        if (adultNear()) {
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

    private boolean adultNear() {
        for (MimicEntity m : level().getEntitiesOfClass(MimicEntity.class, getBoundingBox().inflate(FEED_RADIUS))) {
            if (m != this && m.getStage() == LifeStage.ADULT && m.getIndividual() != null) {
                return true;
            }
        }
        return false;
    }

    /** 지금 육아에 매인 어미인가 (유아 자식 + 무시 아닌 육아 클래스). 이때는 채집 대신 육아. */
    public boolean isCaregiverBound() {
        if (!isFemale() || getStage() != LifeStage.ADULT || homePos == null || individual == null) {
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
        if (stage == LifeStage.ADULT) {
            return;
        }
        growthTicks++;
        int threshold = fastGrowth ? 40 : (stage == LifeStage.INFANT ? 2 * 24000 : 3 * 24000);
        if (growthTicks >= threshold) {
            growthTicks = 0;
            LifeStage next = stage == LifeStage.INFANT ? LifeStage.BOY : LifeStage.ADULT;
            setStage(next);
            com.evosim.mod.stage.StageObserver.record(this.getId(), "grow:" + next.name());
            SimEvents.event(this, "성장", stageKo(stage) + "→" + stageKo(next));
        }
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
                    + (individual != null ? " · 세대" + individual.generation() : ""));
        }
        super.die(source);
    }

    private static String stageKo(LifeStage s) {
        return switch (s) {
            case INFANT -> "유아";
            case BOY -> "소년";
            case ADULT -> "성년";
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
            setIndividual(Genetics.randomFirstGen(this.getId(), rng));
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
        tag.putBoolean("FastCare", fastCare);
        tag.putDouble("Holding", holding);
        tag.putInt("HungerGrace", hungerGraceTicks); // 재로그인해도 아사 클럭 유지(B-4)
        tag.putDouble("LastSurplus", lastSurplus);
        tag.putBoolean("LastFed", lastFed);
        tag.putBoolean("FastSettle", fastSettle);
        tag.putLong("SpouseId", spouseId);
        tag.putBoolean("Widowed", widowed);
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
        fastCare = tag.getBoolean("FastCare");
        holding = tag.contains("Holding") ? tag.getDouble("Holding") : 1.5; // 구 세이브 호환(시작값)
        hungerGraceTicks = tag.getInt("HungerGrace");
        lastSurplus = tag.getDouble("LastSurplus");
        if (tag.contains("LastFed")) {
            lastFed = tag.getBoolean("LastFed");
        }
        fastSettle = tag.getBoolean("FastSettle");
        spouseId = tag.getLong("SpouseId");
        widowed = tag.getBoolean("Widowed");
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
