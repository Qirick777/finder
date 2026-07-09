package com.evosim.mod;

import com.evosim.mod.gui.EvoMatchMenu;
import com.evosim.mod.gui.MateBoardSnapshot;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraftforge.network.NetworkHooks;

/**
 * {@code /evomatch} — 짝매칭 현황판 GUI를 연다(상자처럼 열리는 컨테이너 화면). 주변 미믹들의 까다로움·
 * 상태·후보 순위·구애 기록을 목록으로 보여주고, 열려 있는 동안 서버가 실시간으로 갱신한다.
 */
public final class EvoMatchCommand {

    private EvoMatchCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("evomatch")
                .requires(src -> src.hasPermission(2))
                .executes(EvoMatchCommand::open));
    }

    private static int open(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MateBoardSnapshot snap = MateBoardSnapshot.build(player);
        NetworkHooks.openScreen(player,
                new SimpleMenuProvider((id, inv, p) -> new EvoMatchMenu(id, snap),
                        Component.literal("짝매칭 현황판")),
                snap::encode);
        return 1;
    }
}
