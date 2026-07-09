package com.evosim.mod;

import com.evosim.core.DeterministicRng;
import com.evosim.core.Genetics;
import com.evosim.core.Individual;
import com.evosim.core.LifeStage;
import com.evosim.core.Sex;
import com.evosim.mod.entity.MimicEntity;
import com.evosim.mod.reg.ModEntities;
import com.mojang.brigadier.CommandDispatcher;
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
                .then(Commands.literal("gallery").executes(EvoSimCommand::gallery)));
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
