package com.evosim.mod;

import com.evosim.mod.stage.Stage;
import com.evosim.mod.stage.StageRun;
import com.evosim.mod.stage.StageRunner;
import com.evosim.mod.stage.Stages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * 게임 내 {@code /evostage} — 표현층 자동 검증 (설계서 §17 "무대 세팅식 자동 검증").
 *
 * <p>명령 한 번이면 개체를 알아서 소환하고, 실제 행동(성장·전투)을 관찰해 모든 기대 행동이 관측되면
 * <b>성공</b>, 아니면 <b>실패</b>로 로그 출력. 사람이 눈으로 소환·확인할 필요 없음.
 *
 * <ul>
 *   <li>{@code /evostage all} — 전체 시나리오 순차 실행.</li>
 *   <li>{@code /evostage <시나리오>} — growth · combat_brave · combat_coward.</li>
 * </ul>
 */
public final class EvoStageCommand {

    private EvoStageCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root =
                Commands.literal("evostage").requires(src -> src.hasPermission(2));

        root.executes(EvoStageCommand::runAll);
        root.then(Commands.literal("all").executes(EvoStageCommand::runAll));
        for (Stage s : Stages.all()) {
            String name = s.name();
            root.then(Commands.literal(name).executes(ctx -> runOne(ctx, name)));
        }
        dispatcher.register(root);
    }

    private static int runOne(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack src = ctx.getSource();
        Stage s = Stages.get(name);
        if (s == null) {
            src.sendFailure(Component.literal("알 수 없는 무대: " + name));
            return 0;
        }
        enqueue(src, s);
        src.sendSuccess(() -> Component.literal("무대 검증 시작: " + name + " (곧 결과 출력)")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int runAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        int n = 0;
        for (Stage s : Stages.all()) {
            enqueue(src, s);
            n++;
        }
        final int count = n;
        src.sendSuccess(() -> Component.literal("무대 검증 시작: 전체 " + count + "종 (순차 진행)")
                .withStyle(ChatFormatting.AQUA), false);
        return n;
    }

    private static void enqueue(CommandSourceStack src, Stage s) {
        ServerLevel level = src.getLevel();
        Vec3 anchor = src.getPosition();
        StageRunner.enqueue(new StageRun(s, level, src, anchor));
    }
}
