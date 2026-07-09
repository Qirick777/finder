package com.evosim.mod;

import com.evosim.test.EvoTest;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
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
        CommandSourceStack src = ctx.getSource();

        // 마크 기본 폰트엔 이모지(✅❌)가 없어 네모로 뜬다 → 색상 + ASCII 마커([O]/[X])로 렌더.
        List<EvoTest.Check> checks = report.checks();
        long total = checks.size();
        long passed = checks.stream().filter(EvoTest.Check::pass).count();
        long failed = total - passed;

        src.sendSuccess(() -> Component.literal("=== 검증: /evotest " + kind + " ===")
                .withStyle(ChatFormatting.AQUA), false);
        src.sendSuccess(() -> Component.literal(
                        "총 " + total + " · 통과 " + passed + " · 실패 " + failed)
                .withStyle(failed == 0 ? ChatFormatting.GREEN : ChatFormatting.RED), false);

        for (EvoTest.Check c : checks) {
            final String line = (c.pass() ? "[O] " : "[X] ")
                    + "[" + c.id() + "] 기대 " + c.expected() + " / 실제 " + c.actual();
            src.sendSuccess(() -> Component.literal(line)
                    .withStyle(c.pass() ? ChatFormatting.GREEN : ChatFormatting.RED), false);
        }
        // 성공 시 1, 실패 있으면 0 (명령 결과값 — /execute store 등으로 회귀 자동화 가능).
        return report.hasFailures() ? 0 : 1;
    }
}
