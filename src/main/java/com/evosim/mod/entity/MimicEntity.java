package com.evosim.mod.entity;

import com.evosim.core.Courtship;
import com.evosim.core.DailyCycle;
import com.evosim.core.DeterministicRng;
import com.evosim.core.HomeResolution;
import com.evosim.core.Feeding;
import com.evosim.core.Genetics;
import com.evosim.core.Individual;
import com.evosim.core.Kinship;
import com.evosim.core.LifeStage;
import com.evosim.core.MateHome;
import com.evosim.core.Multipliers;
import com.evosim.core.ParentingClass;
import com.evosim.core.Reproduction;
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
    private static final int BUILD_INTERVAL = 8; // 설치 박자(틱) — 손이 닿아 있을 때 한 칸씩
    private static final double BUILD_REACH = 2.4; // 이 수평 거리 안이어야 설치(밖이면 걸어감)
    private static final int BUILD_REACH_TIMEOUT = 60; // 이만큼 못 닿으면 강제 설치(막힌 자리 교착 방지)
    // 인식 범위 = 신중도(엄격할수록 넓음). 노동은 근접 위주, 배회는 넓게(구애 사양서 v2 확장).
    private static final double WORK_PERCEPT_BASE = 3.0;
    private static final double WORK_PERCEPT_PER = 2.0;   // 레벨당(0~4)
    private static final double WANDER_PERCEPT_BASE = 8.0;
    private static final double WANDER_PERCEPT_PER = 4.0;
    private static final double COURT_CONTACT = 2.5;      // 이 거리면 구애 요청

    // 번식(§6): 마지막 출산 시각 + 출산 수. 실제 발동은 밤 정산의 잉여식량 게이트(settlementTick).
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

    // 식량 사이클(§4 §6): 낮에 수확 누적 → 밤에 가족 정산(DailyCycle) → 잉여로 번식 게이트.
    private double dayHarvest = 0.0;            // 오늘 채집/사냥 누적(밤 정산 후 0)
    private double dayActivity = 0.0;           // 오늘 활동 가산 소모(이동·전투)
    private double lastSurplus = 0.0;           // 마지막 정산 후 가족 창고 잔량(스캐너·번식 표시)
    private boolean lastFed = true;             // 마지막 정산에서 먹었나(스캐너 표시)
    private long lastSettleDay = Long.MIN_VALUE; // 마지막 정산한 절대 일자(하루 1회 보장)
    private boolean fastSettle = false;         // 무대 검증용 초고속 정산(틱 주기)
    private static final int SETTLE_TIME = 18000;    // 하루 중 밤 정산 시각(취침 중 — 귀가 완료 후)
    private static final int FAST_SETTLE_INTERVAL = 40; // fast 모드 정산 주기(틱)

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
        var speed = getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(BASE_SPEED * SurvivalRules.moveSpeedFactor(stage));
        }
        double fem = SurvivalRules.physicalFactor(isFemale() ? Sex.FEMALE : Sex.MALE);
        var attack = getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null) {
            attack.setBaseValue(BASE_ATTACK * fem);
        }
        var health = getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            double newMax = BASE_HEALTH * fem;
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
            settlementTick();  // 밤: 가족 정산 → 잉여로 번식(§4 §6). 낮 채집/사냥은 MimicForageGoal 담당
            infantCareTick();
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
        BlockPos home = new BlockPos(pos[0], anchor[1], pos[1]);
        Direction facing = Direction.from2DDataValue(getRandom().nextInt(4));

        setHomePos(home);
        other.setHomePos(home);
        homeFacing = (byte) facing.get2DDataValue();
        other.homeFacing = homeFacing;
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
     * 짓는 연출 — 부부가 <b>각자 맡은 다음 블록 자리로 직접 걸어가</b>(MimicBuildGoal) 손에 든 그 블럭을
     * 손이 닿았을 때 설치한다(설치음 재생). 담당은 인덱스 패리티로 나눠 <b>동시에·충돌 없이</b> 짓고, 다른
     * 구성원이 있는 칸엔 놓지 않아(질식 방지) 파묻지 않는다. 이미 채워진 칸은 건너뛰고, 닿을 수 없는 자리는
     * 폴백 타이머로 강제 설치해 교착을 막는다. 전부 설치되면 모닥불을 점화하고 완료 처리한다.
     */
    private void buildTick() {
        if (!building || homePos == null || individual == null
                || !(level() instanceof ServerLevel sl)) {
            return;
        }
        Direction facing = getHomeFacingDir();
        List<HomeStructure.Placement> plan = HomeStructure.plan(homePos, facing);

        // 함께 짓는 동료(나 포함)를 id 순으로 정렬 → 안정적 레인 배정으로 동시에·충돌 없이 짓는다.
        List<MimicEntity> crew = buildCrew(sl);
        int lanes = Math.max(1, crew.size());
        int lane = Math.max(0, crew.indexOf(this));

        // ① 완성 판정 — 남은 설치 가능 블록이 없으면 종료(레인0가 점화·완료 처리).
        boolean anyLeft = false;
        for (HomeStructure.Placement p : plan) {
            if (isPlaceable(sl, p.pos())) {
                anyLeft = true;
                break;
            }
        }
        if (!anyLeft) {
            buildTargetPos = null;
            clearBuildItem();
            if (lane == 0) {
                placeHearth(sl, homePos, facing, true); // 완성 → 점화
                finishBuilding(sl);
            } else {
                building = false; // 동료가 마무리(점화)를 맡음
            }
            return;
        }

        // ② 내 레인이 담당하는 다음 설치 가능한 블록(고정 인덱스 패리티로 분담 → 서로 다른 칸).
        HomeStructure.Placement target = null;
        for (int i = 0; i < plan.size(); i++) {
            if (i % lanes != lane) {
                continue;
            }
            if (isPlaceable(sl, plan.get(i).pos())) {
                target = plan.get(i);
                break;
            }
        }
        if (target == null) {
            buildTargetPos = null;
            clearBuildItem();
            return; // 내 몫은 끝, 남은 건 동료가 마저 짓는다
        }
        buildTargetPos = target.pos(); // MimicBuildGoal 이 이 좌표 옆으로 데려감
        showBuildItem(target);         // 그 블럭을 손에 들고 이동(설치 연출)

        // ③ 손이 닿아야 설치. 못 닿으면 접근 대기(단, 오래 못 닿으면 강제 설치로 교착 방지).
        buildReachTicks++;
        boolean forced = buildReachTicks >= BUILD_REACH_TIMEOUT;
        if (!withinReach(target.pos()) && !forced) {
            return; // 아직 걸어가는 중
        }
        // ④ 설치 박자 — 닿아 있을 때만 소모해 한 칸씩 리듬으로 놓는다.
        if ((level().getGameTime() + getId()) % BUILD_INTERVAL != 0) {
            return;
        }
        if (placeBuildBlock(sl, target)) {
            swing(InteractionHand.MAIN_HAND);
            playPlaceSound(sl, target); // 블록별 설치 효과음
        }
        buildReachTicks = 0;
        buildTargetPos = null;
    }

    /** 이 블록 자리에 손이 닿는가(수평 거리 기준 — 지붕 등 높은 칸은 폴백 타이머가 보완). */
    private boolean withinReach(BlockPos p) {
        double dx = (p.getX() + 0.5) - getX();
        double dz = (p.getZ() + 0.5) - getZ();
        return dx * dx + dz * dz <= BUILD_REACH * BUILD_REACH;
    }

    /** 지금 걸어가 설치할 다음 블록 좌표(없으면 null) — MimicBuildGoal 이 목적지로 사용. */
    @Nullable
    public BlockPos getBuildTargetPos() {
        return buildTargetPos;
    }

    /** 같은 거처를 함께 짓는 살아있는 구성원(나 포함) — id 순 정렬로 레인 배정이 안정적. */
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
     * 밤 배치 정산 + 식량 게이트 번식 (설계서 §4 §6). 하루 한 번(정산 시각), 가족의 <b>대표(같은 거처
     * 최소 id 성년)</b>가 가정을 꾸려 {@link DailyCycle#settleFamily}를 돌린다. 결과대로 굶주림·아사·먹임을
     * 반영하고, 잉여가 임계 이상이면 그때만 자식을 낳는다("식량 확보 시 번식").
     */
    private void settlementTick() {
        if (individual == null || getStage() != LifeStage.ADULT || building) {
            return; // 정산은 성년이 주도(건축 중엔 대기)
        }
        long day = level().getDayTime() / 24000L;
        if (fastSettle) {
            if ((level().getGameTime() + getId()) % FAST_SETTLE_INTERVAL != 0) {
                return;
            }
        } else {
            long tod = level().getDayTime() % 24000L;
            if (day == lastSettleDay || tod < SETTLE_TIME) {
                return;
            }
        }
        if (!(level() instanceof ServerLevel sl)) {
            return;
        }

        List<MimicEntity> fam = householdMembers();
        MimicEntity driver = lowestIdAdult(fam);
        if (driver != this) {
            return; // 대표만 정산(중복 방지). 비대표는 대표가 lastSettleDay 를 찍어줌
        }

        // 가정 구성: 첫 성년 남성=남편, 나머지 성년 여성=아내, 미성년=자식.
        Feeding.Household h = new Feeding.Household();
        java.util.IdentityHashMap<Feeding.Member, MimicEntity> back = new java.util.IdentityHashMap<>();
        MimicEntity father = null;
        for (MimicEntity m : fam) {
            if (m.getIndividual() == null) {
                continue;
            }
            if (father == null && m.getStage() == LifeStage.ADULT && !m.isFemale()) {
                father = m;
            }
        }
        for (MimicEntity m : fam) {
            Individual mi = m.getIndividual();
            if (mi == null) {
                continue;
            }
            Feeding.Member mem = new Feeding.Member(mi, m.getStage(), m.dayHarvest, m.dayActivity);
            back.put(mem, m);
            if (m == father) {
                h.father = mem;
            } else if (m.getStage() == LifeStage.ADULT) {
                h.wives.add(mem);
            } else {
                h.children.add(mem);
            }
        }
        if (h.father == null && !h.wives.isEmpty()) {
            // 남편 없는 가정(홀어미/독신녀): 대표를 남편 슬롯에 올려 스스로 먼저 먹게 한다.
            Feeding.Member head = h.wives.remove(0);
            h.father = head;
        }

        DailyCycle.DayResult res = DailyCycle.settleFamily(h);

        // 결과 반영: 사망 → 제거, 생존 → 수확 카운터 리셋 + 먹음/굶음 기록.
        for (Feeding.Member m : res.feeding.died) {
            MimicEntity e = back.get(m);
            if (e != null) {
                StageObserver.record(e.getId(), "settle:starved");
                SimEvents.event(e, "아사", "밤 정산 식량 부족 → 사망");
                e.discard();
            }
        }
        boolean starvedAny = false;
        for (var entry : back.entrySet()) {
            MimicEntity e = entry.getValue();
            boolean fed = res.feeding.fed.contains(entry.getKey());
            e.dayHarvest = 0.0;
            e.dayActivity = 0.0;
            e.lastFed = fed;
            e.lastSurplus = res.surplus;
            e.lastSettleDay = day;
            if (!fed) {
                starvedAny = true;
            }
        }
        SimEvents.note(sl, "정산", String.format(
                "가족 %d명 · 잉여 %.1f · 먹음 %d · 굶음 %d · 번식해금 %s",
                back.size(), res.surplus, res.feeding.fed.size(), res.feeding.starved.size(),
                res.reproductionUnlocked ? "O" : "X"));

        // 식량 게이트 번식: 잉여≥임계일 때만 (설계서 §6). 굶은 가정은 절대 번식 안 함.
        if (res.reproductionUnlocked && !starvedAny) {
            tryReproduce(sl, father);
        }
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

    private static MimicEntity lowestIdAdult(List<MimicEntity> fam) {
        MimicEntity best = null;
        for (MimicEntity m : fam) {
            if (m.getStage() == LifeStage.ADULT
                    && (best == null || m.getId() < best.getId())) {
                best = m;
            }
        }
        return best;
    }

    /** 정산 잉여가 확보됐을 때만 호출되는 실제 출산 (쿨다운·출산상한 내). */
    private void tryReproduce(ServerLevel sl, MimicEntity father) {
        MimicEntity mother = null;
        for (MimicEntity m : householdMembers()) {
            if (m.isFemale() && m.getStage() == LifeStage.ADULT && m.getIndividual() != null) {
                mother = m;
                break;
            }
        }
        if (mother == null || father == null || father.getIndividual() == null) {
            return;
        }
        long now = level().getGameTime();
        if (now - mother.lastBirthTick < (long) Reproduction.FEMALE_COOLDOWN_DAYS * 24000L) {
            return; // 여성 쿨다운(3일)
        }
        if (mother.childrenBorn >= Reproduction.birthLimit(mother.getIndividual(), father.getIndividual())) {
            return; // 출산 상한
        }
        if (sl.getEntitiesOfClass(MimicEntity.class, getBoundingBox().inflate(48.0)).size() > LOCAL_POP_CAP) {
            return; // 지역 과밀(임시)
        }
        mother.spawnChild(sl, father);
    }

    /** 자식 하나를 낳아 거처에 배치 (밤 정산 잉여 확보 후). 어미 기준으로 기록. */
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

    /** 채집/사냥으로 확보한 식량을 오늘치에 더한다 (MimicForageGoal). */
    public void addHarvest(double food) {
        this.dayHarvest += food;
    }

    public void setDayHarvest(double harvest) {
        this.dayHarvest = harvest;
    }

    public double getDayHarvest() {
        return dayHarvest;
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
        if (source.is(DamageTypes.IN_WALL)) {
            return false;
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
        tag.putDouble("DayHarvest", dayHarvest);
        tag.putDouble("DayActivity", dayActivity);
        tag.putDouble("LastSurplus", lastSurplus);
        tag.putBoolean("LastFed", lastFed);
        tag.putLong("LastSettleDay", lastSettleDay);
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
        dayHarvest = tag.getDouble("DayHarvest");
        dayActivity = tag.getDouble("DayActivity");
        lastSurplus = tag.getDouble("LastSurplus");
        if (tag.contains("LastFed")) {
            lastFed = tag.getBoolean("LastFed");
        }
        if (tag.contains("LastSettleDay")) {
            lastSettleDay = tag.getLong("LastSettleDay");
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
