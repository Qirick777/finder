package com.evosim.mod.stage;

import com.evosim.core.Category;
import com.evosim.core.ExpressionResolver;
import com.evosim.core.Individual;
import com.evosim.core.LifeStage;
import com.evosim.core.ParentingClass;
import com.evosim.core.Sex;
import com.evosim.core.Trait;
import com.evosim.core.TraitInstance;
import com.evosim.mod.entity.MimicEntity;
import com.evosim.mod.reg.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 무대 시나리오 등록소 (설계서 §17). 표현층 행동(성장·전투)을 명령 한 번으로 자동 검증.
 *
 * <p>각 시나리오는 개체를 강제 특성으로 소환하고, 엔티티 AI가 실제로 한 행동(성장 전환·진입/도망)을
 * {@link StageObserver}로 관측한다. 눈으로 안 봐도 성공/실패가 로그로 나온다.
 */
public final class Stages {

    private static final Map<String, Stage> REGISTRY = new LinkedHashMap<>();
    private static int idCounter = 900_000;

    static {
        put(new GrowthStage());
        put(new CombatStage("combat_brave", "용감 → 몬스터 처치 진입", Trait.BRAVE, "combat:engage"));
        put(new CombatStage("combat_coward", "겁쟁이 → 몬스터 회피 도망", Trait.COWARD, "combat:flee"));
        put(new CombatRetreatStage());
        put(new InfantStage());
        put(new TraitAuditStage());
        put(new MatingStage());
        put(new SettlementStage());
        put(new ReproductionStage());
        put(new StarvationStage());
        put(new ParentingCareStage());
        put(new ParentingNeglectStage());
    }

    /** 서로 매력 3점(선호↔특성 일치)인 짝짓기 준비 개체 — 신중(여) 기준선도 통과. */
    static Individual matingReady(Sex sex) {
        Individual ind = new Individual(idCounter++, sex, 0, 0, 1);
        ind.addTrait(TraitInstance.of(Trait.PREF_STRENGTH));
        ind.addTrait(TraitInstance.of(Trait.PREF_ABILITY));
        ind.addTrait(TraitInstance.of(Trait.PREF_VITALITY));
        ind.addTrait(TraitInstance.of(Trait.STRONG));
        ind.addTrait(TraitInstance.of(Trait.BRIGHT));
        ind.addTrait(TraitInstance.of(Trait.NIMBLE));
        return ind;
    }

    private Stages() {
    }

    private static void put(Stage s) {
        REGISTRY.put(s.name(), s);
    }

    public static Collection<Stage> all() {
        return REGISTRY.values();
    }

    public static Stage get(String name) {
        return REGISTRY.get(name);
    }

    private static Individual soloIndividual(Sex sex, Trait trait) {
        Individual ind = new Individual(idCounter++, sex, 0, 0, 1);
        ind.addTrait(TraitInstance.of(trait));
        return ind;
    }

    private static MimicEntity spawnMimic(ServerLevel level, Vec3 pos, Individual ind, LifeStage stage) {
        MimicEntity e = ModEntities.MIMIC.get().create(level);
        if (e == null) {
            return null;
        }
        e.setIndividual(ind);
        e.setStage(stage);
        e.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        level.addFreshEntity(e);
        return e;
    }

    // ── 시나리오: 성장 (유아 → 소년 → 성년) ──
    static final class GrowthStage implements Stage {
        @Override public String name() { return "growth"; }
        @Override public String description() { return "유아 → 소년 → 성년 성장 전환"; }
        @Override public List<String> expected() { return List.of("grow:BOY", "grow:ADULT"); }
        @Override public int tickBudget() { return 300; }

        @Override
        public void setup(ServerLevel level, Vec3 anchor, StageRun run) {
            MimicEntity e = spawnMimic(level, anchor, soloIndividual(Sex.MALE, Trait.DILIGENT), LifeStage.INFANT);
            if (e != null) {
                e.setFastGrowth(true); // 검증용 초고속 성장(스테이지당 ~40틱)
                run.watch(e);
            }
        }
    }

    // ── 시나리오: 전투 진입/도망 (강제 특성 + 몬스터 소환) ──
    static final class CombatStage implements Stage {
        private final String name;
        private final String desc;
        private final Trait trait;
        private final String expectedTag;

        CombatStage(String name, String desc, Trait trait, String expectedTag) {
            this.name = name;
            this.desc = desc;
            this.trait = trait;
            this.expectedTag = expectedTag;
        }

        @Override public String name() { return name; }
        @Override public String description() { return desc; }
        @Override public List<String> expected() { return List.of(expectedTag); }
        @Override public int tickBudget() { return 120; }

        @Override
        public void setup(ServerLevel level, Vec3 anchor, StageRun run) {
            MimicEntity e = spawnMimic(level, anchor, soloIndividual(Sex.MALE, trait), LifeStage.ADULT);
            if (e != null) {
                run.watch(e);
            }
            spawnZombie(level, anchor, run);
        }
    }

    // ── 시나리오: 신중 저체력 → 퇴각 (전투 3층위 ② 검증) ──
    static final class CombatRetreatStage implements Stage {
        @Override public String name() { return "combat_retreat"; }
        @Override public String description() { return "신중 저체력 → 전투 퇴각"; }
        @Override public List<String> expected() { return List.of("combat:retreat"); }
        @Override public int tickBudget() { return 120; }

        @Override
        public void setup(ServerLevel level, Vec3 anchor, StageRun run) {
            MimicEntity e = spawnMimic(level, anchor, soloIndividual(Sex.MALE, Trait.PRUDENT), LifeStage.ADULT);
            if (e != null) {
                e.setHealth(e.getMaxHealth() * 0.2F); // 체력 하한(30%) 이하 → 퇴각 트리거
                run.watch(e);
            }
            spawnZombie(level, anchor, run);
        }
    }

    // ── 시나리오: 유아 무방비(전투 불가) + 매우 느림 (설계서 §7) ──
    static final class InfantStage implements Stage {
        private int infantId;
        private Vec3 start;

        @Override public String name() { return "infant"; }
        @Override public String description() { return "유아 전투 불가 + 매우 느림"; }
        @Override public List<String> expected() { return List.of("combat:tooyoung", "slow:confirmed"); }
        @Override public int tickBudget() { return 100; }

        @Override
        public void setup(ServerLevel level, Vec3 anchor, StageRun run) {
            MimicEntity e = spawnMimic(level, anchor, soloIndividual(Sex.MALE, Trait.DILIGENT), LifeStage.INFANT);
            if (e != null) {
                run.watch(e);
                infantId = e.getId();
                start = e.position();
            }
            spawnZombie(level, anchor, run);
        }

        @Override
        public void tick(ServerLevel level, StageRun run, int tick) {
            if (tick == 60 && start != null) {
                Entity e = level.getEntity(infantId);
                if (e != null && e.position().distanceTo(start) < 3.0) {
                    run.mark("slow:confirmed"); // 60틱 동안 3블록 미만 이동 = 사실상 정지(성년은 훨씬 멀리)
                }
            }
        }
    }

    // ── 시나리오: 특성 부여 자동 감사 (소환 개체를 코드로 읽어 우성비율·발현수·반발 집계) ──
    static final class TraitAuditStage implements Stage {
        private static final int SAMPLE = 30;

        @Override public String name() { return "trait_audit"; }
        @Override public String description() { return "소환 개체 특성 부여 감사(우성비율·발현수·반발)"; }
        @Override public List<String> expected() { return List.of("audit:ok"); }
        @Override public int tickBudget() { return 10; }

        @Override
        public void setup(ServerLevel level, Vec3 anchor, StageRun run) {
            int totalExpressed = 0;
            int dominantExpressed = 0;
            int conflicts = 0;
            int maxPerCat = 0;
            int maxTotal = 0;
            int missingIndividual = 0;

            for (int i = 0; i < SAMPLE; i++) {
                MimicEntity e = spawnRandomAdult(level, anchor.add(i * 0.3, 0, 0));
                if (e == null) {
                    continue;
                }
                run.track(e); // 감사 후 정리
                Individual ind = e.getIndividual();
                if (ind == null) {
                    missingIndividual++;
                    continue;
                }
                List<TraitInstance> expressed = ExpressionResolver.expressed(ind);
                int total = 0;
                int[] perCat = new int[Category.values().length];
                for (int x = 0; x < expressed.size(); x++) {
                    TraitInstance ti = expressed.get(x);
                    total++;
                    totalExpressed++;
                    perCat[ti.category().ordinal()]++;
                    if (ti.isDominant()) {
                        dominantExpressed++;
                    }
                    for (int y = x + 1; y < expressed.size(); y++) {
                        if (ti.trait().conflictsWith(expressed.get(y).trait())) {
                            conflicts++;
                        }
                    }
                }
                for (int c : perCat) {
                    maxPerCat = Math.max(maxPerCat, c);
                }
                maxTotal = Math.max(maxTotal, total);
            }

            double domFrac = totalExpressed == 0 ? 0.0 : (double) dominantExpressed / totalExpressed;
            // 기준: 반발위반 0 · 발현 카테고리≤3·총≤9 · 개체데이터 결손 0 · 우성비율 적당(≤35%, 시드 20%).
            boolean pass = conflicts == 0 && maxPerCat <= 3 && maxTotal <= 9
                    && missingIndividual == 0 && domFrac <= 0.35;

            run.detail(String.format("표본 %d · 발현총 %d · 우성 %.1f%% · 반발위반 %d · 최대발현 %d/%d · 데이터결손 %d",
                    SAMPLE, totalExpressed, domFrac * 100.0, conflicts, maxPerCat, maxTotal, missingIndividual));
            if (pass) {
                run.mark("audit:ok");
            }
        }
    }

    private static MimicEntity spawnRandomAdult(ServerLevel level, Vec3 pos) {
        MimicEntity e = ModEntities.MIMIC.get().create(level);
        if (e == null) {
            return null;
        }
        e.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        // 실제 스폰 경로로 랜덤 개체 부여(finalizeSpawn → randomFirstGen).
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()),
                MobSpawnType.COMMAND, null, null);
        level.addFreshEntity(e);
        return e;
    }

    // ── 시나리오: 짝짓기 — 매력 맞는 방랑자 남녀가 조우해 짝 성립(거처 형성) ──
    static final class MatingStage implements Stage {
        @Override public String name() { return "mating"; }
        @Override public String description() { return "매력 맞는 방랑자 남녀 → 짝 성립"; }
        @Override public List<String> expected() { return List.of("mating:pair"); }
        @Override public int tickBudget() { return 220; }

        @Override
        public void setup(ServerLevel level, Vec3 anchor, StageRun run) {
            MimicEntity m = spawnMimic(level, anchor, matingReady(Sex.MALE), LifeStage.ADULT);
            MimicEntity f = spawnMimic(level, anchor.add(2.0, 0, 0), matingReady(Sex.FEMALE), LifeStage.ADULT);
            if (m != null) {
                run.watch(m);
            }
            if (f != null) {
                run.watch(f);
            }
        }
    }

    // ── 시나리오: 거처 비겹침 — 여러 쌍이 정착하면 거처가 최소간격 이상 벌어지나 ──
    static final class SettlementStage implements Stage {
        private final List<Integer> ids = new java.util.ArrayList<>();
        private boolean checked;

        @Override public String name() { return "settlement"; }
        @Override public String description() { return "여러 쌍 정착 → 거처 비겹침"; }
        @Override public List<String> expected() { return List.of("settlement:ok"); }
        @Override public int tickBudget() { return 320; }

        @Override
        public void setup(ServerLevel level, Vec3 anchor, StageRun run) {
            ids.clear();
            checked = false;
            for (int i = 0; i < 3; i++) {
                MimicEntity m = spawnMimic(level, anchor.add(i * 1.5, 0, 0), matingReady(Sex.MALE), LifeStage.ADULT);
                MimicEntity f = spawnMimic(level, anchor.add(i * 1.5, 0, 2.0), matingReady(Sex.FEMALE), LifeStage.ADULT);
                if (m != null) {
                    run.track(m);
                    ids.add(m.getId());
                }
                if (f != null) {
                    run.track(f);
                    ids.add(f.getId());
                }
            }
        }

        @Override
        public void tick(ServerLevel level, StageRun run, int tick) {
            if (checked || tick < 280) {
                return;
            }
            checked = true;
            List<int[]> homes = new java.util.ArrayList<>();
            for (int id : ids) {
                Entity e = level.getEntity(id);
                if (e instanceof MimicEntity m && m.getHomePos() != null) {
                    int[] h = {m.getHomePos().getX(), m.getHomePos().getZ()};
                    if (homes.stream().noneMatch(o -> o[0] == h[0] && o[1] == h[1])) {
                        homes.add(h);
                    }
                }
            }
            boolean nonOverlap = true;
            for (int i = 0; i < homes.size(); i++) {
                for (int j = i + 1; j < homes.size(); j++) {
                    long dx = homes.get(i)[0] - homes.get(j)[0];
                    long dz = homes.get(i)[1] - homes.get(j)[1];
                    if (dx * dx + dz * dz < (long) com.evosim.core.Settlement.MIN_GAP
                            * com.evosim.core.Settlement.MIN_GAP) {
                        nonOverlap = false;
                    }
                }
            }
            run.detail("형성된 거처 " + homes.size() + "곳 · 비겹침 " + (nonOverlap ? "O" : "X"));
            if (homes.size() >= 2 && nonOverlap) {
                run.mark("settlement:ok");
            }
        }
    }

    // ── 시나리오: 번식 — 정착 부부가 식량 잉여 확보 시 자식을 출산(세대 이어짐) ──
    static final class ReproductionStage implements Stage {
        @Override public String name() { return "reproduction"; }
        @Override public String description() { return "정착 부부 + 잉여식량 → 자식 출산"; }
        @Override public List<String> expected() { return List.of("birth"); }
        @Override public int tickBudget() { return 200; }

        @Override
        public void setup(ServerLevel level, Vec3 anchor, StageRun run) {
            BlockPos home = BlockPos.containing(anchor);
            MimicEntity male = spawnMimic(level, anchor, matingReady(Sex.MALE), LifeStage.ADULT);
            MimicEntity female = spawnMimic(level, anchor.add(1.0, 0, 0), matingReady(Sex.FEMALE), LifeStage.ADULT);
            if (male != null) {
                male.setHomePos(home); // 이미 정착(짝짓기 생략) → 바로 번식 관찰
                male.setFastSettle(true);
                male.setDayHarvest(10.0); // 잉여식량 확보 → 밤 정산 번식 게이트 통과
                run.track(male);
            }
            if (female != null) {
                female.setHomePos(home);
                female.setFastSettle(true);
                female.setDayHarvest(10.0);
                run.watch(female);
            }
        }
    }

    // ── 시나리오: 흉년 — 식량 없는 정착 부부는 굶어 죽고 번식하지 않는다(식량 게이트 검증) ──
    static final class StarvationStage implements Stage {
        @Override public String name() { return "cycle_starve"; }
        @Override public String description() { return "수확 0 정착 부부 → 아사(번식 없음)"; }
        @Override public List<String> expected() { return List.of("settle:starved"); }
        @Override public int tickBudget() { return 220; }

        @Override
        public void setup(ServerLevel level, Vec3 anchor, StageRun run) {
            BlockPos home = BlockPos.containing(anchor);
            MimicEntity male = spawnMimic(level, anchor, matingReady(Sex.MALE), LifeStage.ADULT);
            MimicEntity female = spawnMimic(level, anchor.add(1.0, 0, 0), matingReady(Sex.FEMALE), LifeStage.ADULT);
            if (male != null) {
                male.setHomePos(home);
                male.setFastSettle(true); // 수확 0 → 매 정산 굶주림↑ → 3회째 아사
                run.watch(male);
            }
            if (female != null) {
                female.setHomePos(home);
                female.setFastSettle(true);
                run.watch(female);
            }
        }
    }

    // ── 시나리오: 적극 육아 → 유아 생존(먹여짐) ──
    static final class ParentingCareStage implements Stage {
        @Override public String name() { return "parenting_care"; }
        @Override public String description() { return "적극 육아(곁에 머묾) → 유아 먹여짐"; }
        @Override public List<String> expected() { return List.of("infant:fed"); }
        @Override public int tickBudget() { return 100; }

        @Override
        public void setup(ServerLevel level, Vec3 anchor, StageRun run) {
            BlockPos home = BlockPos.containing(anchor);
            Individual momInd = matingReady(Sex.FEMALE);
            momInd.setParentingCareFemale(ParentingClass.DEVOTED); // 여성발현 적극 → 거처에서 안 나옴
            MimicEntity mom = spawnMimic(level, anchor, momInd, LifeStage.ADULT);
            MimicEntity baby = spawnMimic(level, anchor.add(1.0, 0, 0), matingReady(Sex.MALE), LifeStage.INFANT);
            if (mom != null) {
                mom.setHomePos(home);
                run.track(mom);
            }
            if (baby != null) {
                baby.setHomePos(home);
                baby.setFastCare(true); // 무대 검증 초고속 급식
                run.watch(baby);
            }
        }
    }

    // ── 시나리오: 방치된 유아 → 아사 ──
    static final class ParentingNeglectStage implements Stage {
        @Override public String name() { return "parenting_neglect"; }
        @Override public String description() { return "곁에 성인 없는 유아 → 아사"; }
        @Override public List<String> expected() { return List.of("infant:starved"); }
        @Override public int tickBudget() { return 200; }

        @Override
        public void setup(ServerLevel level, Vec3 anchor, StageRun run) {
            BlockPos home = BlockPos.containing(anchor);
            MimicEntity baby = spawnMimic(level, anchor, matingReady(Sex.MALE), LifeStage.INFANT);
            if (baby != null) {
                baby.setHomePos(home); // 거처는 있으나 돌볼 성인이 없음
                baby.setFastCare(true); // 무대 검증 초고속 급식(방치 → 아사 관측)
                run.watch(baby);
            }
        }
    }

    private static void spawnZombie(ServerLevel level, Vec3 anchor, StageRun run) {
        Zombie z = EntityType.ZOMBIE.create(level);
        if (z != null) {
            z.moveTo(anchor.x + 1.5, anchor.y, anchor.z, 0.0F, 0.0F);
            z.setPersistenceRequired();
            level.addFreshEntity(z);
            run.track(z);
        }
    }
}
