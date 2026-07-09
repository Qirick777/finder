package com.evosim.mod.stage;

import com.evosim.core.Individual;
import com.evosim.core.LifeStage;
import com.evosim.core.Sex;
import com.evosim.core.Trait;
import com.evosim.core.TraitInstance;
import com.evosim.mod.entity.MimicEntity;
import com.evosim.mod.reg.ModEntities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
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
