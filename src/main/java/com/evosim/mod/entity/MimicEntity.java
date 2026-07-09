package com.evosim.mod.entity;

import com.evosim.core.DeterministicRng;
import com.evosim.core.Genetics;
import com.evosim.core.Individual;
import com.evosim.core.LifeStage;
import com.evosim.core.Mating;
import com.evosim.core.Reproduction;
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

    // 번식(§6): 마지막 출산 시각 + 출산 수. 데모 쿨다운(밤 정산·잉여식량 연동은 후속).
    private long lastBirthTick = -100_000L;
    private int childrenBorn = 0;
    private static final int REPRO_COOLDOWN = 1200; // 데모용(1분). 잉여식량 게이트는 밤 정산 연동 후.
    private static final int LOCAL_POP_CAP = 60;     // 지역 과밀 방지(임시, 식량 게이트 전)

    // 유아 돌봄/아사 (육아 클래스): 하루 급식 시각에 곁에 성인 없으면 굶주림↑, 임계 초과 시 아사.
    private int careHunger = 0;
    private long lastCareDay = Long.MIN_VALUE; // 마지막 급식 판정한 절대 일자(하루 1회 보장)
    private boolean fastCare = false;          // 무대 검증용 초고속 급식(틱 주기)
    private static final int FEEDING_TIME = 13000; // 하루 중 급식 시각(밤, 가족 수렴 §4)
    private static final int CARE_INTERVAL = 20;   // fast 모드 소모 주기(틱)
    private static final int CARE_DEATH = 3;       // 연속 방치 임계 → 아사 (평상시 3일)
    private static final double FEED_RADIUS = 5.0; // 이 반경 내 성인이 있으면 먹여줌

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
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new MimicParentingGoal(this)); // 유아 돌봄(거처 반경 구속)
        this.goalSelector.addGoal(2, new MimicCombatGoal(this)); // 전투 진입/도망(§13-B)
        this.goalSelector.addGoal(3, new MimicMatingGoal(this)); // 방랑자 짝짓기(§10)
        this.goalSelector.addGoal(5, new MimicHomeGoal(this));   // 거처 귀환(§3)
        this.goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        // BehaviorDecision(시간대·채집/사냥) 연동은 Phase 3 후속(실제 잔디채집·동물사냥)과 함께.
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
            reproductionTick();
            infantCareTick();
        }
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
     * 번식 (설계서 §6). 정착한 성년 여성이 같은 거처의 성년 남성과, 쿨다운·출산상한 내에서 자식을 낳는다.
     * ※ 밤 판정·잉여식량 게이트는 밤 정산 in-world 연동 후 — 지금은 쿨다운+과밀상한으로 임시 유계.
     */
    private void reproductionTick() {
        if (!isFemale() || getStage() != LifeStage.ADULT || homePos == null || individual == null) {
            return;
        }
        long now = level().getGameTime();
        if ((now + getId()) % 40 != 0) {
            return; // 스태거(부하 분산)
        }
        if (now - lastBirthTick < REPRO_COOLDOWN) {
            return;
        }
        MimicEntity mate = findMate();
        if (mate == null || mate.getIndividual() == null) {
            return;
        }
        if (childrenBorn >= Reproduction.birthLimit(individual, mate.getIndividual())) {
            return;
        }
        if (!(level() instanceof ServerLevel sl)) {
            return;
        }
        if (sl.getEntitiesOfClass(MimicEntity.class, getBoundingBox().inflate(48.0)).size() > LOCAL_POP_CAP) {
            return; // 지역 과밀(임시)
        }

        DeterministicRng rng = new DeterministicRng(getRandom().nextLong());
        int gen = Math.max(individual.generation(), mate.getIndividual().generation()) + 1;
        long childId = Math.abs(getRandom().nextLong() | 1L);
        Individual childInd = Genetics.breed(childId, individual, mate.getIndividual(), rng, gen, null);

        MimicEntity child = ModEntities.MIMIC.get().create(sl);
        if (child == null) {
            return;
        }
        child.setIndividual(childInd);
        child.setStage(LifeStage.INFANT);
        child.setHomePos(homePos);
        child.moveTo(homePos.getX() + 0.5, homePos.getY(), homePos.getZ() + 0.5, 0.0F, 0.0F);
        sl.addFreshEntity(child);

        lastBirthTick = now;
        childrenBorn++;
        StageObserver.record(this.getId(), "birth");
        SimEvents.event(this, "출산", "자식 #" + child.getId() + " 세대" + gen
                + " · 부친 #" + mate.getId() + " (누적 " + childrenBorn + ")");
    }

    /** 같은 거처의 성년 남성(짝) 찾기. */
    @Nullable
    private MimicEntity findMate() {
        for (MimicEntity m : level().getEntitiesOfClass(MimicEntity.class, getBoundingBox().inflate(6.0))) {
            if (m == this || m.getIndividual() == null || m.isFemale()) {
                continue;
            }
            if (m.getStage() == LifeStage.ADULT && homePos.equals(m.getHomePos())) {
                return m;
            }
        }
        return null;
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
        if (tag.contains("HomeX")) {
            homePos = new BlockPos(tag.getInt("HomeX"), tag.getInt("HomeY"), tag.getInt("HomeZ"));
        }
    }
}
