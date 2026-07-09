package com.evosim.mod.entity;

import com.evosim.core.DailyCycle;
import com.evosim.core.DeterministicRng;
import com.evosim.core.Feeding;
import com.evosim.core.Genetics;
import com.evosim.core.Individual;
import com.evosim.core.LifeStage;
import com.evosim.core.Mating;
import com.evosim.core.Multipliers;
import com.evosim.core.Reproduction;
import com.evosim.core.Schedule;
import com.evosim.core.Sex;
import com.evosim.core.SurvivalRules;
import com.evosim.mod.log.SimEvents;
import com.evosim.mod.reg.ModEntities;
import com.evosim.mod.stage.StageObserver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.DifficultyInstance;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

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

    // 사회(§3, §10): 거처 포인터(null=방랑자) + 짝 기준선.
    @Nullable
    private BlockPos homePos = null;
    private int matingBaseline = Mating.NORMAL;

    // 번식(§6): 마지막 출산 시각 + 출산 수. 실제 발동은 밤 정산의 잉여식량 게이트(settlementTick).
    private long lastBirthTick = -100_000L;
    private int childrenBorn = 0;
    private static final int LOCAL_POP_CAP = 60;     // 지역 과밀 방지(임시)

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
    private static final int HARVEST_INTERVAL = 20;  // 수확 누적 주기(틱)
    private static final double HARVEST_RATE = 0.01; // 수확틱당 = 배율 × 이 값 (평상 성년 ≈ 3.5/일)
    private static final int FAST_SETTLE_INTERVAL = 40; // fast 모드 정산 주기(틱)

    // 기준값 — 생애단계·성별 배율의 곱으로 실제 속성 산출(설계서 §7 §1).
    private static final double BASE_SPEED = 0.28D;
    private static final double BASE_ATTACK = 2.0D;
    private static final double BASE_HEALTH = 20.0D;

    public MimicEntity(EntityType<? extends MimicEntity> type, Level level) {
        super(type, level);
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
        this.goalSelector.addGoal(1, new MimicParentingGoal(this)); // 유아 돌봄(거처 반경 구속)
        this.goalSelector.addGoal(2, new MimicCombatGoal(this));    // 전투 진입/도망(§13-B)
        this.goalSelector.addGoal(3, new MimicMatingGoal(this));    // 방랑자 짝짓기(§10, 낮 배회)
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
        this.matingBaseline = Mating.startingBaseline(ind);
        refreshStageAttributes();
    }

    @Nullable
    public BlockPos getHomePos() {
        return homePos;
    }

    public void setHomePos(@Nullable BlockPos pos) {
        this.homePos = pos;
    }

    /** 방랑자 = 성년이면서 거처 없음 (짝 구애 대상, §9). */
    public boolean isWanderer() {
        return homePos == null && getStage() == LifeStage.ADULT;
    }

    public int getMatingBaseline() {
        return matingBaseline;
    }

    public void setMatingBaseline(int baseline) {
        this.matingBaseline = baseline;
    }

    public void setFastGrowth(boolean fast) {
        this.fastGrowth = fast;
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) {
            growthTick();
            observeTooYoung();
            forageTick();      // 낮: 채집/사냥 수확 누적
            settlementTick();  // 밤: 가족 정산 → 잉여로 번식(§4 §6)
            infantCareTick();
        }
    }

    /**
     * 수확 누적 (설계서 §4). 노동 구간(WORK)에 성년·만혼소년이 채집/사냥 기대치({@link Multipliers})만큼
     * 오늘치 수확을 쌓는다 — 밤 정산 전까지는 "카운트만 쌓기". 유아·일반소년은 못 함(자급 불가).
     */
    private void forageTick() {
        if (individual == null || !SurvivalRules.canGather(getStage(), individual)) {
            return;
        }
        boolean working = fastSettle
                || Schedule.phaseAt(individual, level().getDayTime()) == Schedule.Phase.WORK;
        if (!working) {
            return;
        }
        if ((level().getGameTime() + getId()) % HARVEST_INTERVAL != 0) {
            return; // 스태거(부하 분산) + 수확 주기
        }
        double rate = Math.max(Multipliers.gather(individual), Multipliers.hunt(individual));
        dayHarvest += rate * HARVEST_RATE;
    }

    /**
     * 밤 배치 정산 + 식량 게이트 번식 (설계서 §4 §6). 하루 한 번(정산 시각), 가족의 <b>대표(같은 거처
     * 최소 id 성년)</b>가 가정을 꾸려 {@link DailyCycle#settleFamily}를 돌린다. 결과대로 굶주림·아사·먹임을
     * 반영하고, 잉여가 임계 이상이면 그때만 자식을 낳는다("식량 확보 시 번식").
     */
    private void settlementTick() {
        if (individual == null || getStage() != LifeStage.ADULT) {
            return; // 정산은 성년이 주도(대표 선출)
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
        tag.putInt("MatingBaseline", matingBaseline);
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
        if (homePos != null) {
            tag.putInt("HomeX", homePos.getX());
            tag.putInt("HomeY", homePos.getY());
            tag.putInt("HomeZ", homePos.getZ());
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
        if (tag.contains("MatingBaseline")) {
            matingBaseline = tag.getInt("MatingBaseline");
        }
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
        if (tag.contains("HomeX")) {
            homePos = new BlockPos(tag.getInt("HomeX"), tag.getInt("HomeY"), tag.getInt("HomeZ"));
        }
    }
}
