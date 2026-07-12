package com.evosim.mod;

import com.evosim.core.DeterministicRng;
import com.evosim.core.Genetics;
import com.evosim.core.Individual;
import com.evosim.core.LifeStage;
import com.evosim.core.Sex;
import com.evosim.core.Trait;
import com.evosim.core.TraitInstance;
import com.evosim.mod.entity.MimicEntity;
import com.evosim.mod.log.SimEvents;
import com.evosim.mod.reg.ModEntities;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;

/**
 * 게임 내 {@code /evosim} 명령어 — 무대 세팅(개체 소환). 렌더링·외형은 눈으로 확인하되 소환은 명령이 대신(설계서 §17).
 *
 * <ul>
 *   <li>{@code /evosim spawn <male|female> <infant|boy|adult> [수]} — 지정 성별·단계 소환.</li>
 *   <li>{@code /evosim gallery} — 성별×단계 6종을 한 줄로 소환(외형 비교용).</li>
 * </ul>
 */
public final class EvoSimCommand {

    private EvoSimCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var spawn = Commands.literal("spawn");
        for (Sex sex : Sex.values()) {
            var sexNode = Commands.literal(sex == Sex.MALE ? "male" : "female");
            sexNode.executes(ctx -> spawn(ctx, sex, LifeStage.ADULT, 1)); // 단계 생략 → 성년
            for (LifeStage stage : LifeStage.values()) {
                sexNode.then(Commands.literal(stageName(stage))
                        .executes(ctx -> spawn(ctx, sex, stage, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 20))
                                .executes(ctx -> spawn(ctx, sex, stage,
                                        IntegerArgumentType.getInteger(ctx, "count")))));
            }
            spawn.then(sexNode);
        }

        dispatcher.register(Commands.literal("evosim")
                .requires(src -> src.hasPermission(2))
                .then(spawn)
                .then(Commands.literal("gallery").executes(EvoSimCommand::gallery))
                .then(Commands.literal("village")
                        .executes(ctx -> village(ctx, 4))
                        .then(Commands.argument("pairs", IntegerArgumentType.integer(1, 12))
                                .executes(ctx -> village(ctx, IntegerArgumentType.getInteger(ctx, "pairs")))))
                .then(Commands.literal("wildpairs")
                        .executes(ctx -> wildPairs(ctx, 4))
                        .then(Commands.argument("pairs", IntegerArgumentType.integer(1, 20))
                                .executes(ctx -> wildPairs(ctx, IntegerArgumentType.getInteger(ctx, "pairs")))))
                // ── 신규 기능 직접 점검(상황 일보직전 세팅) ──
                .then(Commands.literal("build").executes(EvoSimCommand::stageBuild))
                .then(Commands.literal("widow").executes(EvoSimCommand::stageWidow))
                .then(Commands.literal("family").executes(EvoSimCommand::stageFamily))
                .then(Commands.literal("lonepair").executes(EvoSimCommand::stageLonePair))
                .then(Commands.literal("abandon").executes(EvoSimCommand::stageAbandon))
                .then(Commands.literal("reuse").executes(EvoSimCommand::stageReuse))
                .then(Commands.literal("migrate").executes(EvoSimCommand::stageMigrate))
                .then(Commands.literal("berry").executes(EvoSimCommand::stageBerry))
                .then(Commands.literal("food").executes(EvoSimCommand::stageFood))
                .then(Commands.literal("exodus").executes(EvoSimCommand::stageExodus)));
    }

    /** 매력 맞는 방랑자 남녀를 흩뿌려 소환 → 자기들끼리 짝 형성·거처 정착을 눈으로 관찰. */
    private static int village(CommandContext<CommandSourceStack> ctx, int pairs) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Vec3 base = src.getPosition();
        for (int i = 0; i < pairs; i++) {
            spawnMatingReady(level, scatter(level, base), Sex.MALE);
            spawnMatingReady(level, scatter(level, base), Sex.FEMALE);
        }
        src.sendSuccess(() -> Component.literal(
                        "마을 소환: 남 " + pairs + " · 여 " + pairs + " (방랑자) — 짝짓기·거처 정착 관찰")
                .withStyle(ChatFormatting.GREEN), false);
        return pairs * 2;
    }

    /**
     * 무작위 특성 남녀쌍 소환 (설계서 §2 §14 관찰). village 와 달리 특성을 <b>완전 랜덤</b>으로 부여 →
     * 매력·기준선이 제각각이라 짝이 되기도/안 되기도 함. 자연스러운 개체군을 관찰·로그로 검증.
     */
    private static int wildPairs(CommandContext<CommandSourceStack> ctx, int pairs) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Vec3 base = src.getPosition();
        for (int i = 0; i < pairs; i++) {
            spawnWild(level, scatter(level, base), Sex.MALE);
            spawnWild(level, scatter(level, base), Sex.FEMALE);
        }
        src.sendSuccess(() -> Component.literal(
                        "무작위 남녀쌍 소환: 남 " + pairs + " · 여 " + pairs
                                + " (완전 랜덤 특성) — /evolog on 으로 관찰 권장")
                .withStyle(ChatFormatting.GREEN), false);
        return pairs * 2;
    }

    // ── 신규 기능 점검 스테이징 ──

    /** matingReady(서로 매력 매칭) 성년 하나 소환해 반환. */
    private static MimicEntity spawnAdult(ServerLevel level, Vec3 pos, Sex sex) {
        return spawnAdult(level, pos, sex, new Trait[0]);
    }

    /** matingReady 성년 + 지정 추가 특성(정착 성향 등) 부여해 소환. */
    private static MimicEntity spawnAdult(ServerLevel level, Vec3 pos, Sex sex, Trait... extra) {
        MimicEntity e = ModEntities.MIMIC.get().create(level);
        if (e == null) {
            return null;
        }
        long id = Math.abs((int) level.getGameTime()) + level.random.nextInt(1_000_000);
        Individual ind = new Individual(id, sex, 0, 0, 1);
        ind.addTrait(TraitInstance.of(Trait.PREF_STRENGTH));
        ind.addTrait(TraitInstance.of(Trait.PREF_ABILITY));
        ind.addTrait(TraitInstance.of(Trait.PREF_VITALITY));
        ind.addTrait(TraitInstance.of(Trait.STRONG));
        ind.addTrait(TraitInstance.of(Trait.BRIGHT));
        ind.addTrait(TraitInstance.of(Trait.NIMBLE));
        for (Trait t : extra) {
            ind.addTrait(TraitInstance.of(t));
        }
        e.setIndividual(ind);
        e.setStage(LifeStage.ADULT);
        e.moveTo(pos.x, pos.y, pos.z, level.random.nextFloat() * 360f, 0f);
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()),
                MobSpawnType.COMMAND, null, null);
        level.addFreshEntity(e);
        return e;
    }

    private static void tell(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg).withStyle(ChatFormatting.AQUA), false);
    }

    /** 건축 연출: 즉시 짝 성사 → 두 미믹이 천막을 직접 지음. */
    private static int stageBuild(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        MimicEntity m = spawnAdult(level, b, Sex.MALE);
        MimicEntity f = spawnAdult(level, b.add(1, 0, 0), Sex.FEMALE);
        if (m != null && f != null) {
            m.debugForcePair(f);
        }
        tell(ctx.getSource(), "건축 점검: 즉시 짝 성사 → 두 미믹이 부지로 가 천막을 한 칸씩 짓습니다(≈20초). "
                + "완성 시 모닥불 점화. 랜덤 방향 확인.");
        return 1;
    }

    /** 재혼(입주): 홀거처주(천막) + 방랑자 → 방랑자가 거처로 입주. */
    private static int stageWidow(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        level.setDayTime(10000L); // 배회(구애) 시간
        BlockPos homeA = BlockPos.containing(b.add(-5, 0, 0));
        MimicEntity a = spawnAdult(level, Vec3.atBottomCenterOf(homeA), Sex.FEMALE);
        if (a != null) {
            a.debugSettleWithTent(homeA, Direction.NORTH); // 여성 홀거처주(사별 상정)
        }
        spawnAdult(level, b.add(2, 0, 0), Sex.MALE); // 남성 방랑자
        tell(ctx.getSource(), "재혼 점검: 여성 홀거처주(천막·모닥불) + 남성 방랑자. 배회 시간이라 곧 구애 성사 → "
                + "남성이 여성 거처로 입주(새 집 신축 없음, 모닥불 유지).");
        return 1;
    }

    /** 자식 분가: 부모 부부 + 성년 자식(동거) + 방랑자 → 자식이 새 거처로 분가. */
    private static int stageFamily(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        level.setDayTime(10000L);
        BlockPos home = BlockPos.containing(b.add(-6, 0, 0));
        MimicEntity dad = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        MimicEntity mom = spawnAdult(level, Vec3.atBottomCenterOf(home).add(0.5, 0, 0), Sex.FEMALE);
        MimicEntity son = spawnAdult(level, Vec3.atBottomCenterOf(home).add(-0.5, 0, 0), Sex.MALE);
        if (dad != null && mom != null && son != null) {
            dad.debugSettleWithTent(home, Direction.NORTH);
            mom.debugSettleWithTent(home, Direction.NORTH);
            son.setHomePos(home);           // 성년 자식(부모와 동거 = FAMILY)
            dad.debugMarryTo(mom);          // 부모 부부(재구애 안 함)
        }
        spawnAdult(level, b.add(2, 0, 0), Sex.FEMALE); // 자식이 구애할 방랑 여성
        tell(ctx.getSource(), "분가 점검: 부모 부부 + 성년 아들(동거) + 방랑 여성. 아들이 구애 성사 시 "
                + "새 천막 신축(분가). 부모 거처는 유지(모닥불 켜짐).");
        return 1;
    }

    /** 둘 다 홀거처주: 짝 성사 시 한쪽 랜덤 폐기·합류. */
    private static int stageLonePair(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        level.setDayTime(10000L);
        BlockPos homeA = BlockPos.containing(b.add(-6, 0, -3));
        BlockPos homeB = BlockPos.containing(b.add(-6, 0, 3));
        MimicEntity a = spawnAdult(level, Vec3.atBottomCenterOf(homeA), Sex.MALE);
        MimicEntity bb = spawnAdult(level, Vec3.atBottomCenterOf(homeB), Sex.FEMALE);
        if (a != null) {
            a.debugSettleWithTent(homeA, Direction.NORTH);
        }
        if (bb != null) {
            bb.debugSettleWithTent(homeB, Direction.SOUTH);
        }
        tell(ctx.getSource(), "합류 점검: 각자 홀거처(천막) 둘. 배회 시 구애 성사 → 한쪽 거처 랜덤 폐기"
                + "(모닥불 꺼짐)·다른쪽으로 합류.");
        return 1;
    }

    /** 모닥불 소화: 홀거처주(천막) 소환 → 처치하면 거주자 0 → 모닥불 꺼짐. */
    private static int stageAbandon(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        BlockPos home = BlockPos.containing(b.add(-4, 0, 0));
        MimicEntity a = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        if (a != null) {
            a.debugSettleWithTent(home, Direction.NORTH);
        }
        tell(ctx.getSource(), "소화 점검: 홀거처주(천막·모닥불 켜짐). 이 미믹을 처치하면 거주자 0 → "
                + "모닥불이 꺼집니다(건물은 폐허로 남음).");
        return 1;
    }

    /** 애향심 재사용: 빈 거처(꺼진 모닥불) 하나 + 애향심 부부 → 신축 대신 그 빈 거처로 입주(재점화). */
    private static int stageReuse(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        level.setDayTime(10000L);
        BlockPos empty = BlockPos.containing(b.add(-8, 0, 0));
        MimicEntity.debugPlaceAbandonedHome(level, empty, Direction.NORTH); // 빈 거처 준비
        MimicEntity m = spawnAdult(level, b.add(2, 0, 0), Sex.MALE, Trait.HOMEBOUND);
        MimicEntity f = spawnAdult(level, b.add(3, 0, 0), Sex.FEMALE, Trait.HOMEBOUND);
        if (m != null && f != null) {
            m.debugForcePair(f);
        }
        tell(ctx.getSource(), "재사용 점검: 근처에 빈 거처(꺼진 모닥불) 1채 + 애향심(愛鄕) 부부 즉시 성사. "
                + "애향심×애향심=100%로 빈 거처 재사용 → 신축 없이 그 모닥불이 다시 켜집니다.");
        return 1;
    }

    /** 이주자: 빈 거처가 옆에 있어도 무시하고 멀리 신축. reuse 와 대조. */
    private static int stageMigrate(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        level.setDayTime(10000L);
        BlockPos empty = BlockPos.containing(b.add(-8, 0, 0));
        MimicEntity.debugPlaceAbandonedHome(level, empty, Direction.NORTH); // 빈 거처(무시될 것)
        MimicEntity m = spawnAdult(level, b.add(2, 0, 0), Sex.MALE, Trait.MIGRATORY);
        MimicEntity f = spawnAdult(level, b.add(3, 0, 0), Sex.FEMALE, Trait.MIGRATORY);
        if (m != null && f != null) {
            m.debugForcePair(f);
        }
        tell(ctx.getSource(), "이주자 점검: 근처 빈 거처가 있어도 이주자×이주자는 반드시 신축(재사용 0%). "
                + "기본×2보다 더 멀리(×3) 부지를 잡아 천막을 새로 짓습니다.");
        return 1;
    }

    /**
     * 옆 정원 베리 원터치 <b>실연</b>: 부부 1쌍(천막·거처)을 세우고 실시간 실연을 켠다. 아무것도 미리 깔지
     * 않는다 — ① 약 2초 뒤 정산 1회로 <b>번식하고 남은 잉여만큼</b> 옆 정원(x=±3)에 베리를 여러 그루 심고,
     * ② 심은 베리가 실시간으로 익으면 ③ 아버지가 낮에 수확(age3→age1, 재성장)하는 것까지 눈으로 확인한다.
     */
    private static int stageBerry(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        BlockPos home = BlockPos.containing(b.add(-6, 0, 0));
        MimicEntity m = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        MimicEntity f = spawnAdult(level, Vec3.atBottomCenterOf(home).add(0.5, 0, 0), Sex.FEMALE);
        if (m != null && f != null) {
            m.debugSettleWithTent(home, Direction.NORTH);
            f.debugSettleWithTent(home, Direction.NORTH);
            m.debugMarryTo(f);      // 부부 → 번식 몫을 먼저 떼고 남는 잉여로만 베리
            m.startBerryDemo();     // 실시간 실연 시작(잠시 후 정산→심기, 이어 성장→수확)
        }
        level.setDayTime(4000L);    // 낮(노동 시간 한복판): 아버지가 익은 베리를 수확하러 나감
        tell(ctx.getSource(), "베리 실연 시작: 옆 정원(천막 좌우 x=±3)은 지금 비어 있습니다. ① 약 2초 뒤 정산 "
                + "→ 번식하고 남은 잉여만큼 베리가 실시간으로 심기고(로그 [베리] +N), ② 심은 베리가 점점 익어 "
                + "③ 아버지가 낮에 수확합니다(로그 [수확], age3→age1 재성장). 잉여가 많을수록 더 여러 그루(상한 8).");
        return 1;
    }

    /**
     * 식량 경제 원터치 관찰: 부부 정착(천막·저장고) + <b>관찰 로그 자동 ON</b> + 기상 시각. 시간 가속 없이
     * 자연 그대로 살게 두고, 채집→입금→인출→가계 시계열→번식까지 로그로 체킹한다(밸런싱 근거 수집).
     */
    private static int stageFood(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath()); // 로그 자동 ON
        BlockPos home = BlockPos.containing(b.add(-6, 0, 0));
        MimicEntity m = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        MimicEntity f = spawnAdult(level, Vec3.atBottomCenterOf(home).add(0.5, 0, 0), Sex.FEMALE);
        if (m != null && f != null) {
            m.debugSettleWithTent(home, Direction.NORTH);
            f.debugSettleWithTent(home, Direction.NORTH);
            m.debugMarryTo(f);
        }
        level.setDayTime(1000L); // 기상 직후 — 하루 풀 사이클(노동→배회→귀가→취침) 관찰
        tell(ctx.getSource(), "식량 관찰 시작: 부부 정착 + 관찰 로그 자동 ON(evosim-events.log). "
                + "남편이 채집(여 0.5×/남 1.5×)→H≥2면 귀가 입금, 배고프면(H<0.8) 인출. 가계 시계열은 "
                + "1분/가구, 인구+식량 통계는 매일 아침 자동 기록. 즉석 확인: /evolog food · /evolog dump");
        return 1;
    }

    /**
     * 이주 원터치 관찰: 두 부부 마을 + <b>즉시 기근</b> + 로그 자동 ON. 첫 가족이 목적지 정찰·등록
     * (길잡이) → 둘째 가족이 같은 목적지에 동참(캐러밴) → 폐가 2채·새 마을 신축을 로그로 확인.
     */
    private static int stageExodus(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        BlockPos homeA = BlockPos.containing(b.add(-8, 0, -6));
        BlockPos homeB = BlockPos.containing(b.add(-8, 0, 6));
        MimicEntity m1 = spawnAdult(level, Vec3.atBottomCenterOf(homeA), Sex.MALE);
        MimicEntity f1 = spawnAdult(level, Vec3.atBottomCenterOf(homeA).add(0.5, 0, 0), Sex.FEMALE);
        MimicEntity m2 = spawnAdult(level, Vec3.atBottomCenterOf(homeB), Sex.MALE);
        MimicEntity f2 = spawnAdult(level, Vec3.atBottomCenterOf(homeB).add(0.5, 0, 0), Sex.FEMALE);
        if (m1 != null && f1 != null && m2 != null && f2 != null) {
            m1.debugSettleWithTent(homeA, Direction.NORTH);
            f1.debugSettleWithTent(homeA, Direction.NORTH);
            m1.debugMarryTo(f1);
            m2.debugSettleWithTent(homeB, Direction.NORTH);
            f2.debugSettleWithTent(homeB, Direction.NORTH);
            m2.debugMarryTo(f2);
            m1.debugForceFamine(level); // 정착 뒤에 호출(정착이 쿨다운을 리셋하므로 순서 중요)
            m2.debugForceFamine(level);
        }
        level.setDayTime(1000L);
        tell(ctx.getSource(), "이주 관찰: 두 부부 마을이 즉시 기근 상태. 가족틱(≈1분)마다 판정 → 첫 가족이 "
                + "활동반경×2 바깥을 정찰해 목적지 등록(로그 [이주] 길잡이), 둘째 가족은 같은 목적지 동참"
                + "(캐러밴). 옛집 2채는 모닥불 꺼진 폐가([폐가], 저장고 유산 남음), 새 부지에 천막 신축"
                + "([건축완료]). /evolog dump 로 진행 확인.");
        return 1;
    }

    private static void spawnWild(ServerLevel level, Vec3 pos, Sex sex) {
        MimicEntity e = ModEntities.MIMIC.get().create(level);
        if (e == null) {
            return;
        }
        long id = Math.abs((int) level.getGameTime()) + level.random.nextInt(1_000_000);
        Individual ind = Genetics.randomFirstGen(id, new DeterministicRng(level.random.nextLong()), sex);
        e.setIndividual(ind);
        e.setStage(LifeStage.ADULT);
        e.moveTo(pos.x, pos.y, pos.z, level.random.nextFloat() * 360f, 0f);
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()),
                MobSpawnType.COMMAND, null, null);
        level.addFreshEntity(e);
    }

    private static Vec3 scatter(ServerLevel level, Vec3 base) {
        double r = 12.0;
        return base.add((level.random.nextDouble() - 0.5) * r, 0, (level.random.nextDouble() - 0.5) * r);
    }

    private static void spawnMatingReady(ServerLevel level, Vec3 pos, Sex sex) {
        MimicEntity e = ModEntities.MIMIC.get().create(level);
        if (e == null) {
            return;
        }
        // 서로 매력 3점(선호↔특성 일치) → 신중(여) 기준선도 통과해 짝 잘 형성.
        long id = Math.abs((int) level.getGameTime()) + level.random.nextInt(1_000_000);
        Individual ind = new Individual(id, sex, 0, 0, 1);
        ind.addTrait(TraitInstance.of(Trait.PREF_STRENGTH));
        ind.addTrait(TraitInstance.of(Trait.PREF_ABILITY));
        ind.addTrait(TraitInstance.of(Trait.PREF_VITALITY));
        ind.addTrait(TraitInstance.of(Trait.STRONG));
        ind.addTrait(TraitInstance.of(Trait.BRIGHT));
        ind.addTrait(TraitInstance.of(Trait.NIMBLE));
        e.setIndividual(ind);
        e.setStage(LifeStage.ADULT);
        e.moveTo(pos.x, pos.y, pos.z, level.random.nextFloat() * 360f, 0f);
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()),
                MobSpawnType.COMMAND, null, null);
        level.addFreshEntity(e);
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, Sex sex, LifeStage stage, int count) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Vec3 base = src.getPosition();
        for (int i = 0; i < count; i++) {
            spawnOne(level, base.add(i * 1.0, 0, 0), sex, stage);
        }
        src.sendSuccess(() -> Component.literal(
                        "소환: " + (sex == Sex.MALE ? "남" : "여") + " " + stageName(stage) + " ×" + count)
                .withStyle(ChatFormatting.GREEN), false);
        return count;
    }

    private static int gallery(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Vec3 base = src.getPosition();
        int i = 0;
        for (Sex sex : Sex.values()) {
            for (LifeStage stage : LifeStage.values()) {
                spawnOne(level, base.add(i * 1.5, 0, 0), sex, stage);
                i++;
            }
        }
        src.sendSuccess(() -> Component.literal("갤러리 소환: 남/여 × 유아/소년/성년 (6종)")
                .withStyle(ChatFormatting.GREEN), false);
        return i;
    }

    private static void spawnOne(ServerLevel level, Vec3 pos, Sex sex, LifeStage stage) {
        MimicEntity e = ModEntities.MIMIC.get().create(level);
        if (e == null) {
            return;
        }
        Individual ind = Genetics.randomFirstGen(
                Math.abs((int) level.getGameTime()) + level.random.nextInt(100000),
                new DeterministicRng(level.random.nextLong()), sex);
        e.setIndividual(ind);
        e.setStage(stage);
        e.moveTo(pos.x, pos.y, pos.z, level.random.nextFloat() * 360f, 0f);
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()),
                MobSpawnType.COMMAND, null, null);
        level.addFreshEntity(e);
    }

    private static String stageName(LifeStage stage) {
        return switch (stage) {
            case INFANT -> "infant";
            case BOY -> "boy";
            case ADULT -> "adult";
        };
    }
}
