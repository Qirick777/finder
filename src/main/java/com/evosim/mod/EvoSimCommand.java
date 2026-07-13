package com.evosim.mod;

import com.evosim.core.BerryEconomy;
import com.evosim.core.DeterministicRng;
import com.evosim.core.Genetics;
import com.evosim.core.Individual;
import com.evosim.core.LifeStage;
import com.evosim.core.Sex;
import com.evosim.core.Trait;
import com.evosim.core.TraitInstance;
import com.evosim.mod.entity.LarderStore;
import com.evosim.mod.entity.MigrationDest;
import com.evosim.mod.entity.MimicEntity;
import com.evosim.mod.log.SimEvents;
import com.evosim.mod.reg.ModEntities;
import com.evosim.mod.stage.LiveCheck;
import com.evosim.mod.stage.VerifySuite;

import java.util.ArrayList;
import java.util.List;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Zombie;
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
                .then(Commands.literal("exodus").executes(EvoSimCommand::stageExodus))
                .then(Commands.literal("trip").executes(EvoSimCommand::stageTrip))
                .then(Commands.literal("share").executes(EvoSimCommand::stageShare))
                .then(Commands.literal("birth").executes(EvoSimCommand::stageBirth))
                .then(Commands.literal("care").executes(EvoSimCommand::stageCare))
                .then(Commands.literal("r6").executes(EvoSimCommand::stageR6))
                .then(Commands.literal("suitor").executes(EvoSimCommand::stageSuitor))
                .then(Commands.literal("polygamy").executes(EvoSimCommand::stagePolygamy))
                .then(Commands.literal("elder").executes(EvoSimCommand::stageElder))
                .then(Commands.literal("eldercare").executes(EvoSimCommand::stageElderCare))
                .then(Commands.literal("checkall").executes(EvoSimCommand::stageCheckAll)));
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
        BlockPos homeA = groundAt(level, b, -5, 0);
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
        BlockPos home = groundAt(level, b, -6, 0);
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
        BlockPos homeA = groundAt(level, b, -6, -3);
        BlockPos homeB = groundAt(level, b, -6, 3);
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
        BlockPos home = groundAt(level, b, -4, 0);
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
        BlockPos empty = groundAt(level, b, -8, 0);
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
        BlockPos empty = groundAt(level, b, -8, 0);
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
        BlockPos home = groundAt(level, b, -6, 0);
        discardFamily(level, home); // 재실행·타 무대 잔재 정리(장기 관찰 시계열 오염 방지)
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
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지 // 로그 자동 ON
        BlockPos home = groundAt(level, b, -6, 0);
        discardFamily(level, home); // 재실행·타 무대 잔재 정리(장기 관찰 시계열 오염 방지)
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
     * 이주 즉시 연출: 세 가구(부부+유아2 / 부부 / 홀아비+유아1)를 즉시 기근으로 만들고 정산을 바로
     * 강제 → 명령 직후 [이주]가 터진다. 길잡이 등록→캐러밴 동참, 다둥이 분산 업기, 홀아비 업기,
     * 폐가 3채·신축까지 한 번에 성공/실패 판별.
     */
    private static int stageExodus(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        BlockPos homeA = groundAt(level, b, -8, -12);
        BlockPos homeB = groundAt(level, b, -8, 0);
        BlockPos homeC = groundAt(level, b, -8, 12);
        MimicEntity m1 = spawnAdult(level, Vec3.atBottomCenterOf(homeA), Sex.MALE);
        MimicEntity f1 = spawnAdult(level, Vec3.atBottomCenterOf(homeA).add(0.5, 0, 0), Sex.FEMALE);
        MimicEntity m2 = spawnAdult(level, Vec3.atBottomCenterOf(homeB), Sex.MALE);
        MimicEntity f2 = spawnAdult(level, Vec3.atBottomCenterOf(homeB).add(0.5, 0, 0), Sex.FEMALE);
        MimicEntity m3 = spawnAdult(level, Vec3.atBottomCenterOf(homeC), Sex.MALE); // 홀아비
        if (m1 == null || f1 == null || m2 == null || f2 == null || m3 == null) {
            return 0;
        }
        m1.debugSettleWithTent(homeA, Direction.NORTH);
        f1.debugSettleWithTent(homeA, Direction.NORTH);
        m1.debugMarryTo(f1);
        m2.debugSettleWithTent(homeB, Direction.NORTH);
        f2.debugSettleWithTent(homeB, Direction.NORTH);
        m2.debugMarryTo(f2);
        m3.debugSettleWithTent(homeC, Direction.NORTH);
        // 유아: 가구A 2명(순환 배정 — 부모가 나눠 업는지), 가구C 1명(홀아비가 업는지).
        stagedInfant(level, homeA, Sex.MALE);
        stagedInfant(level, homeA, Sex.FEMALE);
        stagedInfant(level, homeC, Sex.FEMALE);
        m1.debugForceFamine(level); // 정착 뒤 호출(정착이 쿨다운을 리셋하므로 순서 중요)
        m2.debugForceFamine(level);
        m3.debugForceFamine(level);
        level.setDayTime(1000L);
        m1.debugSettleOnce(); // 즉시 정산 강제 → 기근 판정 → [이주] 길잡이(정찰·합의 등록)
        m2.debugSettleOnce(); // → [이주] 마을 합의 동참(캐러밴)
        m3.debugSettleOnce(); // → 홀아비 이주(아버지가 유아 업기)
        tell(ctx.getSource(), "이주 즉시 연출 완료 — 판별: ① [이주] 3건(첫 건만 '길잡이', 나머지 '동참'이면 "
                + "캐러밴 성공) ② 가구A 유아 2명이 부모에게 나눠 업히고 가구C 유아가 홀아비에게 업혀 이동하면 "
                + "성공(안 업히고 옛집에 남으면 실패) ③ [폐가] 3건 + 새 부지 [건축완료] ④ 유아가 새 천막 "
                + "근처에서 자동 하차하면 성공. /evolog dump 30 으로 확인.");
        return 1;
    }

    /** 입금·인출 즉시 연출: 남편 H=2.5(여분) · 아내 H=0.7(부족)+저장고 3 — 수 초 내 결과 판별. */
    private static int stageTrip(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        BlockPos home = groundAt(level, b, -6, 0);
        discardFamily(level, home); // 재실행 잔재 정리(이전 부부 중첩 방지)
        MimicEntity m = spawnAdult(level, Vec3.atBottomCenterOf(home).add(8, 0, 0), Sex.MALE);
        MimicEntity f = spawnAdult(level, Vec3.atBottomCenterOf(home).add(-8, 0, 0), Sex.FEMALE);
        if (m == null || f == null) {
            return 0;
        }
        m.debugSettleWithTent(home, Direction.NORTH);
        f.debugSettleWithTent(home, Direction.NORTH);
        m.debugMarryTo(f);
        LarderStore.get(level).set(home, 3.0);
        m.debugSetHolding(2.5); // 여분 정수 → 즉시 "넣으러 귀가" 발동 직전
        f.debugSetHolding(0.7); // 귀가 임계(0.8) 미만 + 저장고 있음 → "꺼내러 귀가" 직전
        level.setDayTime(4000L);
        // 수치 자동 판별: 남편 H 2.5→[1,2)(입금) && 아내 H 0.7→≥1.5(인출)이면 성공. 2초마다 중계.
        LiveCheck.watch(ctx.getSource(), "입금·인출", 600,
                () -> String.format("남편 H %.2f(시작 2.50) · 아내 H %.2f(시작 0.70) · 저장고 %.0f(시작 3)",
                        m.getHolding(), f.getHolding(), LarderStore.get(level).get(home)),
                () -> m.getHolding() >= 1.0 && m.getHolding() < 2.0 && f.getHolding() >= 1.5,
                () -> discard(m, f)); // 판별 종료 시 무대 정리(잔존·세계 오염 방지)
        tell(ctx.getSource(), "입금·인출 연출 — 기대: 남편 H 2.50→1.50(1개 입금), 아내 H 0.70→1.70(1개 인출), "
                + "저장고 3→4→3. 아래 수치 중계로 자동 판정.");
        return 1;
    }

    /** 나눔(B) 즉시 연출: 아내 위급(H=0.25)·남편 여유(H=2.0)·저장고 0(가족틱 개입 차단). */
    private static int stageShare(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        BlockPos home = groundAt(level, b, -6, 0);
        discardFamily(level, home); // 재실행 잔재 정리
        MimicEntity m = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        MimicEntity f = spawnAdult(level, Vec3.atBottomCenterOf(home).add(2, 0, 0), Sex.FEMALE);
        if (m == null || f == null) {
            return 0;
        }
        m.debugSettleWithTent(home, Direction.NORTH);
        f.debugSettleWithTent(home, Direction.NORTH);
        m.debugMarryTo(f);
        LarderStore.get(level).set(home, 0.0); // 저장고 비움 — 가족틱 급식이 아니라 '나눔'이 구하는지 본다
        // 남편 H는 1.9 — 입금 문턱(2.0) 미달이라 가족틱이 저장고에 넣어 아내를 구하는
        // 우회 경로(위양성)가 원천 차단되고, 나눔 문턱(1.5)은 넘어 나눔만이 유일한 구조 경로.
        m.debugSetHolding(1.9);
        f.debugSetHolding(0.25); // 위급(<0.3) — 나눔 대상 직전
        level.setDayTime(4000L);
        // 수치 자동 판별: 아내 H 0.25→≥0.6(0.5 받음 — 자가 채집으론 이 시간 내 불가능한 상승폭)
        // && 남편 H 1.9→≤1.8(내어줌 — 자연 소모만으론 400틱 내 1.85까지밖에 안 떨어짐)이면 성공.
        LiveCheck.watch(ctx.getSource(), "나눔", 400,
                () -> String.format("아내 H %.2f(시작 0.25·위급) · 남편 H %.2f(시작 1.90)",
                        f.getHolding(), m.getHolding()),
                () -> f.getHolding() >= 0.6 && m.getHolding() <= 1.8,
                () -> discard(m, f));
        tell(ctx.getSource(), "나눔 연출 — 기대: 남편이 다가가 0.50 건넴 → 아내 H 0.25→0.75(위급 해제), "
                + "남편 H 1.90→1.40. 아래 수치 중계로 자동 판정.");
        return 1;
    }

    /** 번식 즉시 연출: 저장고 20 채우고 정산 강제 — 명령 직후 출산 여부 판별. */
    private static int stageBirth(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        BlockPos home = groundAt(level, b, -6, 0);
        discardFamily(level, home); // 재실행 개체 잔재 제거 — 이전 부부·유아가 남아 "유아 1" 판정 오염 방지
        MimicEntity m = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        MimicEntity f = spawnAdult(level, Vec3.atBottomCenterOf(home).add(0.5, 0, 0), Sex.FEMALE);
        if (m == null || f == null) {
            return 0;
        }
        m.debugSettleWithTent(home, Direction.NORTH);
        f.debugSettleWithTent(home, Direction.NORTH);
        m.debugMarryTo(f);
        m.debugClearBerries(level); // 재실행 잔재 제거 — "심은 수 = 현재 그루" 회계 대조 전제 복구
        LarderStore.get(level).set(home, 20.0); // 게이트(≈12) 여유 통과 직전
        level.setDayTime(4000L);
        m.debugSettleOnce(); // 즉시 정산 → 출산 판정
        // 수치 즉시 판별: 저장고 20 → 17(출산비용 3) − 베리비용(심은 그루 실측 × 그루당 비용) 회계 일치
        // && 거처 귀속 유아 1명 등장. 베리도 이제 저장고를 실제 차감하므로 기대값에 반영(회계 대조).
        double after = LarderStore.get(level).get(home);
        int infants = 0;
        for (MimicEntity e : level.getEntitiesOfClass(MimicEntity.class,
                new net.minecraft.world.phys.AABB(home).inflate(8.0))) {
            if (e.getStage() == LifeStage.INFANT && home.equals(e.getHomePos())) {
                infants++;
            }
        }
        int bCost = berryCost(level, m);
        double expect = 17.0 - bCost;
        boolean ok = infants == 1 && Math.abs(after - expect) < 1.0E-6;
        tell(ctx.getSource(), String.format(
                "번식 판별 — 저장고 20 → %.0f (기대 %.0f = 17 − 베리비용 %d) · 유아 %d명 (기대 1) ⇒ %s",
                after, expect, bCost, infants, ok ? "✅ 성공" : "❌ 실패"));
        return ok ? 1 : 0;
    }

    /** 정산이 심은 베리의 실제 저장고 차감액 — 본 코드와 동일 공식으로 회계 대조(결과값 기반 판정). */
    private static int berryCost(ServerLevel level, MimicEntity leader) {
        int bushes = leader.countBerries(level);
        if (bushes <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.round(
                bushes * BerryEconomy.BUSH_COST * BerryEconomy.costMult(leader.getIndividual())));
    }

    /** 육아 급식(D) 즉시 연출: 배고픈 유아(H=0.5)+저장고 5 → 정산 강제 → 어미 급식 판별. */
    private static int stageCare(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        BlockPos home = groundAt(level, b, -6, 0);
        MimicEntity m = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        MimicEntity f = spawnAdult(level, Vec3.atBottomCenterOf(home).add(0.5, 0, 0), Sex.FEMALE);
        if (m == null || f == null) {
            return 0;
        }
        m.debugSettleWithTent(home, Direction.NORTH);
        f.debugSettleWithTent(home, Direction.NORTH);
        m.debugMarryTo(f);
        MimicEntity baby = stagedInfant(level, home, Sex.FEMALE);
        if (baby == null) {
            return 0;
        }
        baby.debugSetHolding(0.5); // 채움 임계(1.0) 미만 — 급식 대상 직전
        LarderStore.get(level).set(home, 5.0);
        level.setDayTime(4000L);
        m.debugSettleOnce(); // 즉시 정산 → 유아 급식
        // 수치 즉시 판별: 유아 H 0.5→1.5(정수 1개 급식·목표 도달) && 저장고 5→4.
        double babyAfter = baby.getHolding();
        double larderAfter = LarderStore.get(level).get(home);
        boolean ok = babyAfter >= 1.5 - 1.0E-9 && Math.abs(larderAfter - 4.0) < 1.0E-6;
        tell(ctx.getSource(), String.format(
                "육아 급식 판별 — 유아 H 0.50 → %.2f (기대 1.50) · 저장고 5 → %.0f (기대 4) ⇒ %s",
                babyAfter, larderAfter, ok ? "✅ 성공" : "❌ 실패"));
        return ok ? 1 : 0;
    }

    /** R6 위급 분기 즉시 연출: 밤에 위급 2명 — 저장고 없는 쪽은 채집 강행, 있는 쪽은 귀가 인출. */
    private static int stageR6(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        BlockPos homeA = groundAt(level, b, -6, -6); // 저장고 없음 → 채집 강행
        BlockPos homeB = groundAt(level, b, -6, 6);  // 저장고 있음 → 귀가 인출
        MimicEntity a = spawnAdult(level, Vec3.atBottomCenterOf(homeA), Sex.MALE);
        MimicEntity bb = spawnAdult(level, Vec3.atBottomCenterOf(homeB).add(6, 0, 0), Sex.MALE);
        if (a == null || bb == null) {
            return 0;
        }
        a.debugSettleWithTent(homeA, Direction.NORTH);
        bb.debugSettleWithTent(homeB, Direction.NORTH);
        LarderStore.get(level).set(homeA, 0.0);
        LarderStore.get(level).set(homeB, 3.0);
        a.debugSetHolding(0.25);  // 위급 + 밥 없음 → 밤에도 안 자고 채집해야 성공
        bb.debugSetHolding(0.25); // 위급 + 밥 있음 → 귀가·인출해야 성공
        level.setDayTime(15000L); // 취침 시간대 — 평소라면 자야 함
        tell(ctx.getSource(), "R6 연출(밤) — 판별(수 초 내): A(북쪽 집)는 [위급] '저장고 없음(채집 강행)' 후 "
                + "자지 않고 풀을 뜯어 [채집]→[회복]. B(남쪽 집)는 [위급] '저장고 있음(귀가)' 후 집으로 걸어와 "
                + "[인출]→[회복]. 누워 자거나 위급인 채 가만있으면 실패.");
        return 1;
    }

    /** 구혼 여행(족외혼) 즉시 연출: 64블록 떨어진 두 마을, 고립 남성을 즉시 출발 직전으로. */
    private static int stageSuitor(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        BlockPos homeA = groundAt(level, b, -6, 0);
        BlockPos homeB = groundAt(level, b, 58, 0); // 64블록 동쪽 — 타향(48 초과)
        MimicEntity m = spawnAdult(level, Vec3.atBottomCenterOf(homeA), Sex.MALE);
        MimicEntity f = spawnAdult(level, Vec3.atBottomCenterOf(homeB), Sex.FEMALE);
        if (m == null || f == null) {
            return 0;
        }
        m.debugSettleWithTent(homeA, Direction.NORTH);  // 모닥불 A 점화(등록)
        f.debugSettleWithTent(homeB, Direction.NORTH);  // 모닥불 B 점화(등록) — 남성의 목적지
        m.debugForceLonely(); // '오래 외로움' 강제 → 다음 인식 틱에 출발 직전
        level.setDayTime(8200L); // 배회 시간 — 도착하면 바로 구애 가능
        tell(ctx.getSource(), "구혼 여행 연출 — 판별: 즉시 [구혼여행] '타향 모닥불 @…로 출발' 로그 + 남성이 "
                + "동쪽(64블록)으로 계속 걸어감 → 도착 후 [구애] 성사 → [짝성립] → 정착([합류]/신축). "
                + "출발 로그가 없거나 자기 집 주변만 맴돌면 실패.");
        return 1;
    }

    /** 중혼(일부다처) 즉시 연출: 관용 아내 부부 + 부양 증명 저장고 + 독신 여성 → 수치로 성립 판별. */
    private static int stagePolygamy(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        BlockPos home = groundAt(level, b, -6, 0);
        discardFamily(level, home); // 재실행 잔재 정리
        MimicEntity[] couple = coupleAt(level, home);
        MimicEntity bride = spawnAdult(level, Vec3.atBottomCenterOf(home).add(4, 0, 0), Sex.FEMALE);
        if (bride == null) {
            return 0;
        }
        // 부양 증명(하루소모 6 × 3일 = 18) 여유 통과: 감시 창 동안 가족틱이 자연 개입해 출산(−3)·
        // 베리(−최대 8)로 저장고를 깎아도 게이트가 흔들리지 않게 40으로(경계값 21은 이제 flaky).
        LarderStore.get(level).set(home, 40.0);
        level.setDayTime(9000L); // 배회 시간 — 구애 goal 활동
        LiveCheck.watch(ctx.getSource(), "중혼", 1200,
                () -> String.format("신부 %s · 거처 %s · 저장고 %.0f",
                        bride.isSingleAdult() ? "독신" : "혼인",
                        home.equals(bride.getHomePos()) ? "합류" : "미합류",
                        LarderStore.get(level).get(home)),
                () -> !bride.isSingleAdult() && home.equals(bride.getHomePos()),
                () -> discardFamily(level, home, couple[0], couple[1], bride)); // 감시 창 중 태어난 자손까지 회수
        tell(ctx.getSource(), "중혼 연출 — 주변 독신남 0·기혼남만 후보(감점에도 유일 후보). 기대: 신부가 "
                + "구애 → 아내 용인(인색·경쟁 없음)+저장고 40≥부양선(하루소모×3) → 수락·합류. 아내에 인색 "
                + "특성을 준 거절 케이스는 /evosim checkall 12단계에 포함.");
        return 1;
    }

    /**
     * 원트랙 전체 검증: 미검증 기능 12단계를 한 명령으로 차례차례 — 각 단계는 "발동 직전" 조건을 즉각
     * 조성하고, 별도 감지가 <b>결과값 변화만</b> 보고 ✅/❌ 판정(호출 여부 아님). 끝에 요약 출력.
     */
    private static int stageCheckAll(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "이미 검증이 진행 중 — 끝난 뒤 다시 실행.");
            return 0;
        }
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        List<VerifySuite.Step> steps = new ArrayList<>();

        // [1] 입금·인출 — 남편 H2.5→[1,2)·아내 H0.7→≥1.5 (결과값: H·저장고)
        {
            BlockPos home = ground(level, b, 1);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("입금·인출", 600, false, () -> {
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                LarderStore.get(level).set(home, 3.0);
                c[0].teleportTo(home.getX() + 8.5, home.getY(), home.getZ() + 0.5);
                c[1].teleportTo(home.getX() - 8.5, home.getY(), home.getZ() + 0.5);
                c[0].debugSetHolding(2.5);
                c[1].debugSetHolding(0.7);
                level.setDayTime(4000L);
            }, () -> String.format("남편H %.2f(시작2.5) 아내H %.2f(시작0.7) 저장고 %.0f",
                    c[0].getHolding(), c[1].getHolding(), LarderStore.get(level).get(home)),
                    () -> c[0].getHolding() >= 1.0 && c[0].getHolding() < 2.0 && c[1].getHolding() >= 1.5,
                    () -> discard(c)));
        }
        // [2] 나눔 — 아내 0.25→≥0.6(자가 채집 불가 상승폭)·남편 ≤1.8
        {
            BlockPos home = ground(level, b, 2);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("가족 나눔", 400, false, () -> {
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                LarderStore.get(level).set(home, 0.0);
                c[0].debugSetHolding(1.9); // 입금 문턱(2.0) 미달 — 가족틱 우회 구조(위양성) 차단
                c[1].debugSetHolding(0.25);
                level.setDayTime(4000L);
            }, () -> String.format("아내H %.2f(시작0.25) 남편H %.2f(시작1.9)",
                    c[1].getHolding(), c[0].getHolding()),
                    () -> c[1].getHolding() >= 0.6 && c[0].getHolding() <= 1.8,
                    () -> discard(c)));
        }
        // [3] 번식·베리 — 저장고 20 → 17(출산비용 3) − 베리비용(심은 그루 실측) 회계 일치 + 유아 1
        {
            BlockPos home = ground(level, b, 3);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("번식+출산비용", 100, false, () -> {
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[0].debugClearBerries(level); // 재실행 잔재 제거(회계 대조 전제)
                LarderStore.get(level).set(home, 20.0);
                level.setDayTime(4000L);
                c[0].debugSettleOnce();
            }, () -> String.format("저장고 %.0f(기대 %.0f=17−베리비용) 유아 %d(기대1) 베리 %d",
                    LarderStore.get(level).get(home), 17.0 - berryCost(level, c[0]),
                    infantsAt(level, home), c[0].countBerries(level)),
                    () -> Math.abs(LarderStore.get(level).get(home)
                            - (17.0 - berryCost(level, c[0]))) < 1.0E-6
                            && infantsAt(level, home) == 1,
                    () -> discardFamily(level, home, c)));
        }
        // [4] 육아 급식 — 유아 0.5→1.5 · 저장고 5→4
        {
            BlockPos home = ground(level, b, 4);
            MimicEntity[] c = new MimicEntity[3];
            steps.add(new VerifySuite.Step("육아 급식", 100, false, () -> {
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[2] = stagedInfant(level, home, Sex.FEMALE);
                c[2].debugSetHolding(0.5);
                LarderStore.get(level).set(home, 5.0);
                level.setDayTime(4000L);
                c[0].debugSettleOnce();
            }, () -> String.format("유아H %.2f(기대1.5) 저장고 %.0f(기대4)",
                    c[2].getHolding(), LarderStore.get(level).get(home)),
                    () -> c[2].getHolding() >= 1.5 - 1.0E-9
                            && Math.abs(LarderStore.get(level).get(home) - 4.0) < 1.0E-6,
                    () -> discard(c)));
        }
        // [5] R6 귀가 인출 — 밤·위급·저장고 있음 → H≥1.5·저장고 3→2
        {
            BlockPos home = ground(level, b, 5);
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("R6 위급 귀가", 600, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home).add(6, 0, 0), Sex.MALE);
                c[0].debugSettleWithTent(home, Direction.NORTH);
                LarderStore.get(level).set(home, 3.0);
                c[0].debugSetHolding(0.25);
                level.setDayTime(15000L); // 취침 시간 — 평소라면 자야 함
            }, () -> String.format("H %.2f(시작0.25) 저장고 %.0f(시작3)",
                    c[0].getHolding(), LarderStore.get(level).get(home)),
                    // 0.25→채움은 정수 2개 인출(1.25는 목표 1.5 미달) → H 2.25·L 1, 이후 재입금으로
                    // H 1.25·L 2 로 진동 가능. 판정은 "실제로 먹었고(H≥1.2) 저장고가 줄었다(L<3)"로.
                    () -> c[0].getHolding() >= 1.2
                            && LarderStore.get(level).get(home) <= 2.0 + 1.0E-6,
                    () -> discard(c)));
        }
        // [6] R6 채집 강행 — 밤·위급·저장고 없음 → H가 채집으로 회복(주변 풀 필요 — 환경 의존)
        {
            BlockPos home = ground(level, b, 6);
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("R6 채집 강행(풀 필요)", 900, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
                c[0].debugSettleWithTent(home, Direction.NORTH);
                LarderStore.get(level).set(home, 0.0);
                c[0].debugSetHolding(0.25);
                level.setDayTime(15000L);
            }, () -> String.format("H %.2f(시작0.25 — 채집 회복 대기)", c[0].getHolding()),
                    () -> c[0].getHolding() > 0.35,
                    () -> discard(c)));
        }
        // [7] 아사 클럭 — fast 압축(채집 차단) H0.1 → 유예 초과 후 체력 실감소
        {
            BlockPos spot = ground(level, b, 7);
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("아사 클럭(피해 발생)", 400, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(spot), Sex.MALE);
                c[0].setFastSettle(true); // 시간 압축 + 채집 goal 차단 → 자가 구조 불가(결정론)
                c[0].debugSetHolding(0.1);
            }, () -> String.format("H %.2f · 체력 %.1f/%.1f",
                    c[0].getHolding(), c[0].getHealth(), c[0].getMaxHealth()),
                    () -> !c[0].isAlive() || c[0].getHealth() < c[0].getMaxHealth() - 0.5,
                    () -> discard(c)));
        }
        // [8] 이주 — 두 가구+유아: 거처 좌표가 실제로 바뀌고, 두 새 거처가 같은 목적지권, 유아 업힘
        {
            BlockPos homeA = ground(level, b, 8);
            BlockPos homeB = homeA.offset(12, 0, 0);
            MimicEntity[] c = new MimicEntity[5];
            steps.add(new VerifySuite.Step("이주(캐러밴·유아 업기)", 200, false, () -> {
                MimicEntity[] f1 = coupleAt(level, homeA);
                MimicEntity[] f2 = coupleAt(level, homeB);
                c[0] = f1[0];
                c[1] = f1[1];
                c[2] = f2[0];
                c[3] = f2[1];
                c[4] = stagedInfant(level, homeA, Sex.MALE);
                level.setDayTime(1000L);
                c[0].debugForceFamine(level);
                c[2].debugForceFamine(level);
                c[0].debugSettleOnce(); // 길잡이
                c[2].debugSettleOnce(); // 동참
            }, () -> String.format("가구A %s 가구B %s 두 새집 거리 %.0f 유아 업힘 %s",
                    homeA.equals(c[0].getHomePos()) ? "잔류" : "이동",
                    homeB.equals(c[2].getHomePos()) ? "잔류" : "이동",
                    c[0].getHomePos() != null && c[2].getHomePos() != null
                            ? Math.sqrt(c[0].getHomePos().distSqr(c[2].getHomePos())) : -1,
                    c[4].isPassenger() ? "O" : "X"),
                    () -> c[0].getHomePos() != null && !homeA.equals(c[0].getHomePos())
                            && c[2].getHomePos() != null && !homeB.equals(c[2].getHomePos())
                            && c[0].getHomePos().distSqr(c[2].getHomePos()) <= 96.0 * 96.0
                            && c[4].isPassenger(),
                    () -> discard(c)));
        }
        // [9]·[10] 구혼 여행 — 64블록 이동(좌표 실측) → 혼인 상태 변화
        {
            BlockPos homeA = ground(level, b, 9);
            BlockPos homeB = homeA.offset(0, 0, 56);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("구혼 여행: 이동", 1800, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(homeA), Sex.MALE);
                c[0].debugSettleWithTent(homeA, Direction.NORTH);
                c[1] = spawnAdult(level, Vec3.atBottomCenterOf(homeB), Sex.FEMALE);
                c[1].debugSettleWithTent(homeB, Direction.NORTH);
                c[0].debugForceLonely();
                level.setDayTime(8200L); // 배회 — 도착 후 바로 구애 가능
            }, () -> String.format("남성↔타향 거리 %.0f(시작 56)",
                    Math.sqrt(c[0].blockPosition().distSqr(homeB))),
                    () -> c[0].blockPosition().distSqr(homeB) <= 24.0 * 24.0,
                    () -> { /* [10]과 공유 — 정리 없음 */ }));
            steps.add(new VerifySuite.Step("구혼 여행: 성사", 1200, false, () -> {
            }, () -> String.format("남성 %s · 여성 %s",
                    c[0].isSingleAdult() ? "독신" : "혼인", c[1].isSingleAdult() ? "독신" : "혼인"),
                    () -> !c[0].isSingleAdult(),
                    () -> discard(c)));
        }
        // [11] 중혼 성사 — 관용 아내 + 저장고 21 → 신부 혼인·합류
        {
            BlockPos home = ground(level, b, 11);
            MimicEntity[] c = new MimicEntity[3];
            steps.add(new VerifySuite.Step("중혼 성사(관용 아내)", 1200, false, () -> {
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[2] = spawnAdult(level, Vec3.atBottomCenterOf(home).add(4, 0, 0), Sex.FEMALE);
                // 부양 증명(6×3일=18) 여유 통과 — 감시 창 중 가족틱의 출산(−3)·베리(−최대 8) 자연
                // 개입에도 게이트 유지(경계값 21은 베리 실차감 도입 후 flaky).
                LarderStore.get(level).set(home, 40.0);
                level.setDayTime(9000L);
            }, () -> String.format("신부 %s · 합류 %s · 저장고 %.0f",
                    c[2].isSingleAdult() ? "독신" : "혼인",
                    home.equals(c[2].getHomePos()) ? "O" : "X", LarderStore.get(level).get(home)),
                    () -> !c[2].isSingleAdult() && home.equals(c[2].getHomePos()),
                    () -> discard(c)));
        }
        // [12] 중혼 거절(질투 아내) — 금지 결과 감시: 제한 시간 내 혼인이 일어나면 실패
        {
            BlockPos home = ground(level, b, 12);
            MimicEntity[] c = new MimicEntity[3];
            steps.add(new VerifySuite.Step("중혼 거절(인색 아내)", 600, true, () -> {
                MimicEntity[] cc = coupleAt(level, home, Trait.STINGY); // 질투 게이트
                c[0] = cc[0];
                c[1] = cc[1];
                c[2] = spawnAdult(level, Vec3.atBottomCenterOf(home).add(4, 0, 0), Sex.FEMALE);
                // 부양은 넉넉히(40) — 감시 창 중 저장고가 자연 감소해 '부양 미달'로도 거절되면
                // 인색 게이트가 고장나도 통과하는 위양성이 생기므로, 거절 사유를 아내 특성 하나로 고정.
                LarderStore.get(level).set(home, 40.0);
                level.setDayTime(9000L);
            }, () -> String.format("신부 %s(계속 독신이어야 성공)",
                    c[2].isSingleAdult() ? "독신" : "혼인"),
                    () -> !c[2].isSingleAdult(), // ← 금지 결과(혼인)가 감지되면 실패
                    () -> discard(c)));
        }

        // [13] 노년 전이·자연사 — fast 성장: 노년 경유(상태) 후 사망(결과값: isAlive)
        {
            BlockPos spot = ground(level, b, 13);
            MimicEntity[] c = new MimicEntity[1];
            boolean[] sawElder = new boolean[1];
            steps.add(new VerifySuite.Step("노년 전이·자연사", 400, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(spot), Sex.MALE);
                c[0].setFastGrowth(true);
                level.setDayTime(2000L);
            }, () -> {
                if (c[0].getStage() == LifeStage.ELDER) {
                    sawElder[0] = true;
                }
                return String.format("단계 %s · 노년경유 %s · 생존 %s",
                        stageName(c[0].getStage()), sawElder[0] ? "O" : "X", c[0].isAlive() ? "O" : "X");
            }, () -> {
                if (c[0].getStage() == LifeStage.ELDER) {
                    sawElder[0] = true;
                }
                return sawElder[0] && !c[0].isAlive();
            }, () -> discard(c)));
        }
        // [14] 노인 공유(책임) — 자식 집 저장고 0→2 실측
        {
            BlockPos homeE = ground(level, b, 14);
            // 자식 집을 활동반경(32) 밖 40블록에 — 노인 방문의 리시 앵커(활동반경 밖 도달)가 실제로
            // 발동하는 거리로 검증(과거 16블록은 앵커 수정을 한 번도 밟지 않았다).
            BlockPos homeC = homeE.offset(40, 0, 0);
            MimicEntity[] c = new MimicEntity[3];
            steps.add(new VerifySuite.Step("노인 공유(책임)", 1200, false, () -> {
                // 집 반경 밖 스폰 — 가족틱의 자기 입금이 배달 잉여를 흡수하는 race 차단(결정론).
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(homeE).add(8, 0, 4), Sex.MALE,
                        Trait.OVER_RESPONSIBLE);
                c[0].setStage(LifeStage.ELDER);
                c[0].debugSettleWithTent(homeE, Direction.NORTH);
                LarderStore.get(level).set(homeE, 8.0); // 가드② 통과
                c[0].debugSetHolding(3.5);              // 잉여 2개 — 배달 직전
                c[1] = spawnChildOf(level, Vec3.atBottomCenterOf(homeC), c[0], Sex.MALE);
                c[1].debugSettleWithTent(homeC, Direction.NORTH);
                c[2] = stagedInfant(level, homeC, Sex.FEMALE);
                LarderStore.get(level).set(homeC, 0.0);
                level.setDayTime(2000L);
            }, () -> String.format("자식집 저장고 %.0f(기대 2) · 노인 H %.2f",
                    LarderStore.get(level).get(homeC), c[0].getHolding()),
                    () -> LarderStore.get(level).get(homeC) >= 2.0 - 1.0E-6,
                    () -> discard(c)));
        }
        // [15] 노인 무공유(무책임) — 금지 결과 감시: 자식 집 저장고가 늘면 실패
        {
            BlockPos homeE = ground(level, b, 15);
            BlockPos homeC = homeE.offset(16, 0, 0);
            MimicEntity[] c = new MimicEntity[3];
            steps.add(new VerifySuite.Step("노인 무공유(무책임)", 600, true, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(homeE).add(8, 0, 4), Sex.MALE,
                        Trait.IRRESPONSIBLE);
                c[0].setStage(LifeStage.ELDER);
                c[0].debugSettleWithTent(homeE, Direction.NORTH);
                LarderStore.get(level).set(homeE, 8.0);
                c[0].debugSetHolding(3.5); // 잉여는 있으나 무책임 → 나누면 안 됨
                c[1] = spawnChildOf(level, Vec3.atBottomCenterOf(homeC), c[0], Sex.MALE);
                c[1].debugSettleWithTent(homeC, Direction.NORTH);
                c[2] = stagedInfant(level, homeC, Sex.FEMALE);
                LarderStore.get(level).set(homeC, 0.0);
                level.setDayTime(2000L);
            }, () -> String.format("자식집 저장고 %.0f(0 유지여야 성공)", LarderStore.get(level).get(homeC)),
                    () -> LarderStore.get(level).get(homeC) > 1.0E-6, // ← 금지 결과(공유 발생)
                    () -> discard(c)));
        }
        // [16] 마실 육아 — 노인만 곁에 있는 fastCare 유아가 방치 아사하지 않으면 성공(금지 결과=유아 사망)
        {
            BlockPos homeE = ground(level, b, 16);
            BlockPos homeC = homeE.offset(10, 0, 0);
            MimicEntity[] c = new MimicEntity[3];
            steps.add(new VerifySuite.Step("마실 육아(손자 생존)", 400, true, () -> {
                // 노인은 자기 집 없이 유아 곁에서 시작(마실 상태) — 대상 식별은 부모 링크+유아 대조.
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(homeC).add(1, 0, 0), Sex.MALE);
                c[0].setStage(LifeStage.ELDER);
                MimicEntity child = spawnChildOf(level, Vec3.atBottomCenterOf(homeC), c[0], Sex.MALE);
                child.debugSettleWithTent(homeC, Direction.NORTH);
                child.teleportTo(homeC.getX() + 100.5, homeC.getY(), homeC.getZ() + 0.5); // 부모는 먼 곳
                c[1] = child;
                c[2] = stagedInfant(level, homeC, Sex.FEMALE);
                c[2].setFastCare(true); // 20틱마다 급식 판정 — 성인 없으면 3회(60틱) 만에 아사
                LarderStore.get(level).set(homeC, 3.0);
                level.setDayTime(2000L);
            }, () -> String.format("유아 %s · 노인↔유아 %.0f블록 (유아 생존 유지여야 성공)",
                    c[2].isAlive() ? "생존" : "사망",
                    Math.sqrt(c[0].blockPosition().distSqr(c[2].blockPosition()))),
                    () -> !c[2].isAlive(), // ← 금지 결과(방치 아사)
                    () -> discard(c)));
        }

        // [17] 집앞 즉석 인출 — 집 안에 서 있는 배고픈 개체가 이동 없이 저장고에서 꺼내 먹는가
        //     (결과값: H 0.7→≥1.5 · 저장고 3→2. 가족틱이 대신 처리할 확률 ~3%가 있으나 결과 동일 경로)
        {
            BlockPos home = ground(level, b, 17);
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("집앞 즉석 인출", 60, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
                c[0].debugSettleWithTent(home, Direction.NORTH);
                LarderStore.get(level).set(home, 3.0);
                c[0].debugSetHolding(0.7); // 귀가 임계(0.8) 미만 + 이미 집 안(이동 불필요)
                level.setDayTime(4000L);
            }, () -> String.format("H %.2f(시작0.7·기대≥1.5) 저장고 %.0f(시작3·기대2)",
                    c[0].getHolding(), LarderStore.get(level).get(home)),
                    () -> c[0].getHolding() >= 1.5 - 1.0E-9
                            && LarderStore.get(level).get(home) <= 2.0 + 1.0E-6,
                    () -> discard(c)));
        }
        // [18] 혼인우선 가장 — 성년 아들이 동거해도 번식이 막히지 않는가(과거: UUID 순으로 아들이
        //     가장을 밀어내면 어미 탐색이 조용히 실패). 판정은 [3]과 같은 회계 항등식 + 유아 1.
        {
            BlockPos home = ground(level, b, 18);
            MimicEntity[] c = new MimicEntity[3];
            steps.add(new VerifySuite.Step("혼인우선 가장(아들 동거)", 100, false, () -> {
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[2] = spawnChildOf(level, Vec3.atBottomCenterOf(home).add(2, 0, 0), c[0], Sex.MALE);
                c[2].debugSettleWithTent(home, Direction.NORTH); // 성년 아들 동거(미혼 — 부녀교배 방지 대조군)
                c[0].debugClearBerries(level);
                LarderStore.get(level).set(home, 20.0);
                level.setDayTime(4000L);
                c[0].debugSettleOnce();
            }, () -> String.format("저장고 %.0f(기대 %.0f) 유아 %d(기대1)",
                    LarderStore.get(level).get(home), 17.0 - berryCost(level, c[0]),
                    infantsAt(level, home)),
                    () -> Math.abs(LarderStore.get(level).get(home)
                            - (17.0 - berryCost(level, c[0]))) < 1.0E-6
                            && infantsAt(level, home) == 1,
                    () -> discardFamily(level, home, c)));
        }
        // [19] 전투 도주 해제 — 겁쟁이가 좀비에게서 도망치다, 좀비가 멀어지면(어그로 무의미) 도주를
        //     멈추고 귀가하는가(과거: 좀비가 살아있는 한 무한 도주). 결과값 = 귀가 좌표.
        {
            BlockPos home = ground(level, b, 19);
            MimicEntity[] c = new MimicEntity[1];
            Zombie[] z = new Zombie[1];
            long[] t0 = new long[1];
            boolean[] moved = new boolean[1];
            steps.add(new VerifySuite.Step("전투 도주 해제(귀가)", 600, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE, Trait.COWARD);
                c[0].debugSettleWithTent(home, Direction.NORTH);
                level.setDayTime(15000L); // 밤 — 좀비 연소 방지
                z[0] = EntityType.ZOMBIE.create(level);
                z[0].moveTo(home.getX() + 4.5, home.getY(), home.getZ() + 0.5, 0f, 0f);
                z[0].setPersistenceRequired();
                z[0].setTarget(c[0]);
                level.addFreshEntity(z[0]);
                t0[0] = level.getGameTime();
                moved[0] = false;
            }, () -> String.format("도주거리 %.0f블록 · 좀비 %s",
                    Math.sqrt(c[0].blockPosition().distSqr(home)),
                    moved[0] ? "원격(해제 국면)" : "인접(도주 국면)"),
                    () -> {
                        // 국면 전환: 60틱 도주 후 좀비를 120블록 밖으로(살아있는 채) — 과거 코드라면
                        // 그래도 계속 도망친다. 신 코드는 해제 → 리시·귀가로 집 3블록 내 복귀 = 성공.
                        if (!moved[0] && level.getGameTime() - t0[0] > 60 && z[0] != null) {
                            z[0].teleportTo(home.getX() + 120.5, home.getY(), home.getZ() + 0.5);
                            moved[0] = true;
                        }
                        return moved[0] && c[0].blockPosition().distSqr(home) <= 9.0;
                    },
                    () -> {
                        discard(c);
                        if (z[0] != null && z[0].isAlive()) {
                            z[0].discard();
                        }
                    }));
        }
        // [20] 제자리 합의 기각 — 낡은 이주 합의가 "지금 굶는 자리"를 가리키면 따라가지 않고 딴 곳을
        //     정찰하는가. 결과값 = 새 거처가 합의 목적지에서 32블록 이상.
        {
            BlockPos home = ground(level, b, 20);
            BlockPos dest = home.offset(8, 0, 0);      // 합의 목적지 = 사실상 제자리
            BlockPos origin = home.offset(-60, 0, 0);  // 같은 마을권(256 내) 옛 출발지
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("제자리 합의 기각(이주)", 200, false, () -> {
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                MigrationDest.get(level).register(origin, dest, level.getGameTime()); // 유효한 낡은 합의
                c[0].debugForceFamine(level);
                c[0].debugSettleOnce(); // 기근 → 이주. resolve 는 dest(8) < origin(60) 로 기각해야 함
            }, () -> String.format("새집%s · 합의목적지와 %.0f블록",
                    home.equals(c[0].getHomePos()) ? " 미이동" : " 이동",
                    c[0].getHomePos() != null ? Math.sqrt(c[0].getHomePos().distSqr(dest)) : -1),
                    () -> c[0].getHomePos() != null && !home.equals(c[0].getHomePos())
                            && c[0].getHomePos().distSqr(dest) > 32.0 * 32.0,
                    () -> {
                        discardFamily(level, home, c);
                        // 합의 잔재 만료 처리 — 이후 스텝·플레이에 오염 없게
                        MigrationDest.get(level).register(origin, dest,
                                level.getGameTime() - MigrationDest.VALID_TICKS - 1);
                    }));
        }
        // [21] 아이들만 이주 금지 — 인솔 성년·노년 없는 가구는 기근이어도 이주하지 않는다(금지 결과 감시).
        {
            BlockPos home = ground(level, b, 21);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("아이들만 이주 금지", 200, true, () -> {
                c[0] = stagedInfant(level, home, Sex.MALE);
                c[1] = stagedInfant(level, home, Sex.FEMALE);
                c[0].debugSettleWithTent(home, Direction.NORTH);
                c[1].setHomePos(home);
                c[0].debugForceFamine(level);
                c[0].debugSettleOnce(); // 대표(아이)가 정산해도 grown==0 가드가 이주를 막아야 함
            }, () -> String.format("거처 %s(유지여야 성공)",
                    home.equals(c[0].getHomePos()) ? "유지" : "이동!"),
                    () -> c[0].getHomePos() == null || !home.equals(c[0].getHomePos()), // ← 금지 결과
                    () -> discardFamily(level, home, c)));
        }
        // [22] 낮 샘플 육아: 곁에 성인 → 생존 — 압축 시계(하루 40틱)로 실경로(샘플·래치·롤오버)를
        //     그대로 통과시켜, 어른이 곁에 있는 유아는 살아남는지(금지 결과 = 사망).
        {
            BlockPos home = ground(level, b, 22);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("낮샘플 육아: 곁 생존", 400, true, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home).add(1, 0, 0), Sex.FEMALE);
                c[0].setNoAi(true); // 결정론 — 성인을 유아 곁에 고정(배회로 이탈하는 우연 제거)
                c[1] = stagedInfant(level, home, Sex.MALE);
                c[1].setNoAi(true);
                c[1].debugSetCareTimeScale(600); // 하루=40틱 — 3일 방치면 ~160틱에 죽는 스케일
                level.setDayTime(2000L);
            }, () -> String.format("유아 %s (생존 유지여야 성공)", c[1].isAlive() ? "생존" : "사망"),
                    () -> !c[1].isAlive(), // ← 금지 결과(곁에 성인인데 아사)
                    () -> discard(c)));
        }
        // [23] 낮 샘플 육아: 방치 아사 — 같은 압축 시계에서 성인이 아예 없으면 3일(≈160틱) 내 죽는가
        //     (결과값 = 사망. [22]와 쌍으로 래치·롤오버·아사 카운터의 실경로를 완주시킨다).
        {
            BlockPos home = ground(level, b, 23);
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("낮샘플 육아: 방치 아사", 400, false, () -> {
                c[0] = stagedInfant(level, home, Sex.FEMALE);
                c[0].setNoAi(true);
                c[0].debugSetCareTimeScale(600);
                level.setDayTime(2000L);
            }, () -> String.format("유아 %s · 방치 누적 관찰 중", c[0].isAlive() ? "생존" : "사망(기대 결과)"),
                    () -> !c[0].isAlive(),
                    () -> discard(c)));
        }

        VerifySuite.start(ctx.getSource(), steps);
        tell(ctx.getSource(), "원트랙 검증 시작 — 23단계, 각 단계는 발동 직전 조건을 자동 조성하고 "
                + "결과값(H·저장고·좌표·혼인·생존 상태)의 변화로만 판정. 끝에 ✅/❌ 요약. (6번은 주변에 "
                + "풀이 있어야 함 — 초원에서 실행 권장)");
        return 1;
    }

    /** 노년 전이·자연사 즉시 판별: fast 성장 성년 → 노년 경유(상태 실측) → 사망. */
    private static int stageElder(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        MimicEntity e = spawnAdult(level, b.add(-4, 0, 0), Sex.MALE);
        if (e == null) {
            return 0;
        }
        e.setFastGrowth(true); // 단계당 40틱 — 성년→노년(2초)→자연사(4초)
        boolean[] sawElder = new boolean[1];
        LiveCheck.watch(ctx.getSource(), "노년·자연사", 400,
                () -> {
                    if (e.getStage() == LifeStage.ELDER) {
                        sawElder[0] = true;
                    }
                    return String.format("단계 %s · 노년경유 %s · 생존 %s",
                            stageName(e.getStage()), sawElder[0] ? "O" : "X", e.isAlive() ? "O" : "X");
                },
                () -> {
                    if (e.getStage() == LifeStage.ELDER) {
                        sawElder[0] = true;
                    }
                    return sawElder[0] && !e.isAlive(); // 결과값: 노년을 거쳐 실제로 죽었는가
                },
                () -> discard(e)); // 실패(생존) 시에도 무대 개체 정리
        tell(ctx.getSource(), "노년 판별 — 기대: 약 2초 뒤 성년→노년([성장]), 다시 약 2초 뒤 [자연사]. "
                + "노년을 거치지 않거나 살아있으면 실패.");
        return 1;
    }

    /** 노인 공유·마실 육아 즉시 판별: 책임 노인(잉여 H=3.5) + 친자식 집(유아) → 그 집 저장고 증가 실측. */
    private static int stageElderCare(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        BlockPos homeE = groundAt(level, b, -6, -8);
        BlockPos homeC = groundAt(level, b, -6, 36); // 자식 집 — 44블록(활동반경 32 밖: 마실 앵커 실검증)
        discardFamily(level, homeE); // 재실행 잔재 정리(두 집 모두)
        discardFamily(level, homeC);
        // 노인은 집 반경(6) 밖에서 스폰 — 가족틱이 잉여를 자기 저장고로 흡수하는 경쟁(race) 차단.
        MimicEntity elder = spawnAdult(level, Vec3.atBottomCenterOf(homeE).add(8, 0, 4), Sex.MALE,
                Trait.OVER_RESPONSIBLE); // 책임 — 잉여 목표·배달형
        if (elder == null) {
            return 0;
        }
        elder.setStage(LifeStage.ELDER);
        elder.debugSettleWithTent(homeE, Direction.NORTH);
        LarderStore.get(level).set(homeE, 8.0);  // 가드②(자기 저장고 ≥ 하루소모) 통과
        elder.debugSetHolding(3.5);              // 잉여 정수 2개 → 배달 직전
        MimicEntity child = spawnChildOf(level, Vec3.atBottomCenterOf(homeC), elder, Sex.MALE);
        child.debugSettleWithTent(homeC, Direction.NORTH);
        MimicEntity infant = stagedInfant(level, homeC, Sex.FEMALE); // 손자(유아) — 마실 우선 대상
        LarderStore.get(level).set(homeC, 0.0);
        level.setDayTime(2000L); // 노동 시간(마감 6000 전)
        LiveCheck.watch(ctx.getSource(), "노인공유", 1200,
                () -> String.format("자식집 저장고 %.0f(시작 0·기대 2) · 노인 H %.2f(시작 3.5) · 노인↔자식집 %.0f블록",
                        LarderStore.get(level).get(homeC), elder.getHolding(),
                        Math.sqrt(elder.blockPosition().distSqr(homeC))),
                () -> LarderStore.get(level).get(homeC) >= 2.0 - 1.0E-6, // 결과값: 자식 저장고 실증가
                () -> discard(elder, child, infant));
        tell(ctx.getSource(), "노인 공유 판별 — 기대: 책임 노인이 자식 집(유아 있음)으로 걸어가 [노인공유] "
                + "저장고 0→2, 이후 유아 곁 머묾(마실 육아). 저장고가 안 늘면 실패.");
        return 1;
    }

    /** 스테이징용 — 부모 링크가 걸린 성년 자식 소환(노인 방문 대상 성립 검증용). */
    private static MimicEntity spawnChildOf(ServerLevel level, Vec3 pos, MimicEntity parent, Sex sex) {
        MimicEntity e = ModEntities.MIMIC.get().create(level);
        if (e == null) {
            throw new IllegalStateException("자식 스폰 실패");
        }
        Individual p = parent.getIndividual();
        long id = Math.abs((int) level.getGameTime()) + level.random.nextInt(1_000_000);
        Individual ind = new Individual(id, sex, p.id(), 0, p.generation() + 1);
        e.setIndividual(ind);
        e.setStage(LifeStage.ADULT);
        e.moveTo(pos.x, pos.y, pos.z, 0f, 0f);
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()),
                MobSpawnType.COMMAND, null, null);
        level.addFreshEntity(e);
        return e;
    }

    /** 정착 부부 헬퍼 — 천막+혼인, 아내 추가 특성 지정 가능(질투 케이스). */
    private static MimicEntity[] coupleAt(ServerLevel level, BlockPos home, Trait... wifeTraits) {
        MimicEntity m = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        MimicEntity f = spawnAdult(level, Vec3.atBottomCenterOf(home).add(0.5, 0, 0), Sex.FEMALE, wifeTraits);
        if (m == null || f == null) {
            throw new IllegalStateException("부부 스폰 실패");
        }
        m.debugSettleWithTent(home, Direction.NORTH);
        f.debugSettleWithTent(home, Direction.NORTH);
        m.debugMarryTo(f);
        return new MimicEntity[] {m, f};
    }

    /** 지형 높이에 맞춘 무대 거처 좌표 — 단독 명령이 플레이어 발 Y를 그대로 써서 경사지에서
     *  천막이 공중/벽 속에 깔리던 것을 heightmap 으로 보정(위치 x/z 는 그대로 — 관찰성 유지). */
    private static BlockPos groundAt(ServerLevel level, Vec3 b, double dx, double dz) {
        return level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                BlockPos.containing(b.x + dx, 0, b.z + dz));
    }

    /** 지형 높이에 맞춘 검증 슬롯 좌표(슬롯당 z+64 — 인식·나눔 범위 밖으로 격리). */
    private static BlockPos ground(ServerLevel level, Vec3 b, int slot) {
        BlockPos p = BlockPos.containing(b.add(-8, 0, slot * 64));
        return level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p);
    }

    private static int infantsAt(ServerLevel level, BlockPos home) {
        int n = 0;
        for (MimicEntity e : level.getEntitiesOfClass(MimicEntity.class,
                new net.minecraft.world.phys.AABB(home).inflate(8.0))) {
            if (e.getStage() == LifeStage.INFANT && home.equals(e.getHomePos())) {
                n++;
            }
        }
        return n;
    }

    /** 무대 개체 정리(널 허용). */
    private static void discard(MimicEntity... es) {
        for (MimicEntity e : es) {
            if (e != null && e.isAlive()) {
                e.discard();
            }
        }
    }

    /** 무대 가족 정리 — 무대 중 태어난 유아까지 포함해 제거. */
    private static void discardFamily(ServerLevel level, BlockPos home, MimicEntity... es) {
        discard(es);
        for (MimicEntity e : level.getEntitiesOfClass(MimicEntity.class,
                new net.minecraft.world.phys.AABB(home).inflate(12.0))) {
            if (home.equals(e.getHomePos())) {
                e.discard();
            }
        }
    }

    /** 스테이징용 유아 소환 — 거처 귀속 + 개체 반환. */
    private static MimicEntity stagedInfant(ServerLevel level, BlockPos home, Sex sex) {
        MimicEntity e = ModEntities.MIMIC.get().create(level);
        if (e == null) {
            return null;
        }
        Individual ind = Genetics.randomFirstGen(
                Math.abs((int) level.getGameTime()) + level.random.nextInt(100000),
                new DeterministicRng(level.random.nextLong()), sex);
        e.setIndividual(ind);
        e.setStage(LifeStage.INFANT);
        e.moveTo(home.getX() + 1.5, home.getY(), home.getZ() + 0.5, 0f, 0f);
        e.setHomePos(home);
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()),
                MobSpawnType.COMMAND, null, null);
        level.addFreshEntity(e);
        return e;
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
            case ELDER -> "elder";
        };
    }
}
