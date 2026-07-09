package com.evosim.mod;

import com.evosim.test.EvoDebug;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 게임 내 {@code /evodebug} 진단 명령어 (설계서 §17). ✅/❌ 판정이 아니라 상세 로그를 채팅으로 뿌린다.
 *
 * <p>{@code /evodebug trace [개체수]}: 표본 개체의 하루 행동 타임라인 — 행동 우선순위 확인.
 * 헤드리스 {@link EvoDebug}와 같은 로직을 공유한다.
 */
public final class EvoDebugCommand {

    private EvoDebugCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("evodebug")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("trace")
                        .executes(ctx -> trace(ctx, 3))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 20))
                                .executes(ctx -> trace(ctx, IntegerArgumentType.getInteger(ctx, "count"))))));
    }

    private static int trace(CommandContext<CommandSourceStack> ctx, int count) {
        List<String> lines = EvoDebug.trace(count, 42L);
        for (String line : lines) {
            final String l = line;
            ctx.getSource().sendSuccess(() -> Component.literal(l).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }
}
