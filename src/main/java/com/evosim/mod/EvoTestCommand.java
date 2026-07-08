package com.evosim.mod;

import com.evosim.test.EvoTest;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 게임 내 {@code /evotest <종류>} 명령어 (설계서 §17 원터치 검증).
 *
 * <p>헤드리스 하니스({@link EvoTest})와 <b>같은 로직</b>({@link EvoTest#runReport(String)})을 호출해
 * 결과를 채팅으로 출력한다 → CLI 와 게임 결과가 항상 일치. 사용: {@code /evotest all}, {@code /evotest genetics}.
 */
public final class EvoTestCommand {

    private EvoTestCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("evotest")
                .requires(src -> src.hasPermission(2)) // 오퍼레이터만
                .executes(ctx -> run(ctx, "all"))
                .then(Commands.argument("kind", StringArgumentType.word())
                        .executes(ctx -> run(ctx, StringArgumentType.getString(ctx, "kind")))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, String kind) {
        EvoTest.Report report = EvoTest.runReport(kind);
        List<String> lines = report.render();
        for (String line : lines) {
            final String l = line;
            ctx.getSource().sendSuccess(() -> Component.literal(l), false);
        }
        // 성공 시 1, 실패 있으면 0 (명령 결과값 — /execute store 등으로 회귀 자동화 가능).
        return report.hasFailures() ? 0 : 1;
    }
}
