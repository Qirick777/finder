package com.evosim.mod;

import com.evosim.mod.entity.MimicEntity;
import com.evosim.mod.log.SimEvents;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;
import java.util.List;

/**
 * 게임 내 {@code /evolog} — 자연 관찰 로그 제어 (설계서 §14). 사건이 <b>실제로 벌어지는 순간</b>에
 * 컨텍스트와 함께 파일·메모리에 기록되므로, 로그를 통째로 복사해 검증할 수 있다.
 *
 * <ul>
 *   <li>{@code /evolog on} — 기록 시작 (파일 {@code evosim-events.log} 열림).</li>
 *   <li>{@code /evolog off} — 기록 종료.</li>
 *   <li>{@code /evolog dump [n]} — 최근 n건(기본 30)을 채팅에 출력.</li>
 *   <li>{@code /evolog census} — 지금 즉시 인구 스냅샷을 로그에 남김.</li>
 *   <li>{@code /evolog clear} — 메모리 버퍼 비움.</li>
 * </ul>
 */
public final class EvoLogCommand {

    private EvoLogCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("evolog")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("on").executes(EvoLogCommand::on))
                .then(Commands.literal("off").executes(EvoLogCommand::off))
                .then(Commands.literal("census").executes(EvoLogCommand::census))
                .then(Commands.literal("clear").executes(EvoLogCommand::clear))
                .then(Commands.literal("dump")
                        .executes(ctx -> dump(ctx, 30))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 500))
                                .executes(ctx -> dump(ctx, IntegerArgumentType.getInteger(ctx, "n"))))));
    }

    private static int on(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Path dir = src.getServer().getServerDirectory().toPath();
        SimEvents.setEnabled(true, dir);
        Path log = SimEvents.logPath();
        src.sendSuccess(() -> Component.literal(
                        "관찰 로그 ON — 사건이 발생 즉시 기록됨" + (log != null ? " → " + log : ""))
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int off(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        SimEvents.setEnabled(false, src.getServer().getServerDirectory().toPath());
        src.sendSuccess(() -> Component.literal("관찰 로그 OFF").withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    private static int census(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!SimEvents.enabled()) {
            src.sendFailure(Component.literal("로그가 꺼져 있음 — /evolog on 먼저"));
            return 0;
        }
        ServerLevel level = src.getLevel();
        List<MimicEntity> all = level.getEntitiesOfClass(MimicEntity.class,
                net.minecraft.world.phys.AABB.ofSize(src.getPosition(), 512, 512, 512));
        SimEvents.census(level, all);
        src.sendSuccess(() -> Component.literal("인구 스냅샷 기록: 미믹 " + all.size() + "명")
                .withStyle(ChatFormatting.AQUA), false);
        return all.size();
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        SimEvents.clearMemory();
        ctx.getSource().sendSuccess(() -> Component.literal("메모리 버퍼 비움")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int dump(CommandContext<CommandSourceStack> ctx, int n) {
        CommandSourceStack src = ctx.getSource();
        List<String> lines = SimEvents.recent(n);
        if (lines.isEmpty()) {
            src.sendSuccess(() -> Component.literal("기록 없음 (로그가 꺼져 있었거나 아직 사건 없음)")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal("── 최근 " + lines.size() + "건 ──")
                .withStyle(ChatFormatting.GOLD), false);
        for (String line : lines) {
            src.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        return lines.size();
    }
}
