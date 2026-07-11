package com.evosim.mod;

import com.evosim.mod.entity.LarderStore;
import com.evosim.mod.entity.MimicEntity;
import com.evosim.mod.log.SimEvents;
import net.minecraft.core.BlockPos;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 게임 내 {@code /evolog} — 자연 관찰 로그 제어 (설계서 §14). 사건이 <b>실제로 벌어지는 순간</b>에
 * 컨텍스트(개체·보유 H·좌표·시각)와 함께 파일·메모리에 기록된다 — 통째로 복사해 밸런싱 근거로 쓴다.
 *
 * <p>기록되는 사건(전부 컴팩트 한 줄): 등장(개체 변수 1회 소개), 채집/사냥/수확(+량), 입금/인출(저장고
 * 증감), 위급/회복/굶주림/아사, 나눔(B), 육아 급식(D), 구애(성사/거절+판정 수치), 짝성립,
 * 출산(신생아 성별·세대·특성·부모), 성장(유→소→성), 사망(원인), 건축/건축완료/합류/입주/폐가, 베리,
 * 전투, 동원(R4 전이), 가계(1분/가구: 저장고·소지합·입출금), 인구(하루 1회: 인구+식량 통계).
 *
 * <ul>
 *   <li>{@code /evolog} — 현재 상태(ON/OFF·버퍼·파일 경로).</li>
 *   <li>{@code /evolog on|off} — 기록 시작/종료 (파일 {@code evosim-events.log}).</li>
 *   <li>{@code /evolog dump [n]} — 최근 n건(기본 30)을 채팅에 출력.</li>
 *   <li>{@code /evolog food} — 즉석 식량 스냅샷: 가구별 저장고 + 구성원 보유 H (로그에도 남음).</li>
 *   <li>{@code /evolog census} — 즉시 인구+식량 스냅샷을 로그에 남김.</li>
 *   <li>{@code /evolog clear} — 메모리 버퍼 비움.</li>
 * </ul>
 */
public final class EvoLogCommand {

    private EvoLogCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("evolog")
                .requires(src -> src.hasPermission(2))
                .executes(EvoLogCommand::status)
                .then(Commands.literal("on").executes(EvoLogCommand::on))
                .then(Commands.literal("off").executes(EvoLogCommand::off))
                .then(Commands.literal("food").executes(EvoLogCommand::food))
                .then(Commands.literal("census").executes(EvoLogCommand::census))
                .then(Commands.literal("clear").executes(EvoLogCommand::clear))
                .then(Commands.literal("dump")
                        .executes(ctx -> dump(ctx, 30))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 500))
                                .executes(ctx -> dump(ctx, IntegerArgumentType.getInteger(ctx, "n"))))));
    }

    /** 인수 없이 — 로그 상태 한 줄. */
    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        boolean on = SimEvents.enabled();
        Path log = SimEvents.logPath();
        src.sendSuccess(() -> Component.literal(String.format(
                        "관찰 로그 %s · 버퍼 %d건%s",
                        on ? "ON" : "OFF", SimEvents.memorySize(),
                        on && log != null ? " · 파일 " + log : ""))
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        return on ? 1 : 0;
    }

    /** 즉석 식량 스냅샷 — 가구별 저장고 L + 구성원 보유 H, 방랑자 별도. 로그가 켜져 있으면 기록도. */
    private static int food(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        List<MimicEntity> all = level.getEntitiesOfClass(MimicEntity.class,
                net.minecraft.world.phys.AABB.ofSize(src.getPosition(), 256, 256, 256));
        if (all.isEmpty()) {
            src.sendSuccess(() -> Component.literal("근처(256) 미믹 없음")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
            return 0;
        }
        Map<Long, List<MimicEntity>> byHome = new LinkedHashMap<>();
        List<MimicEntity> wanderers = new ArrayList<>();
        for (MimicEntity m : all) {
            BlockPos h = m.getHomePos();
            if (h == null) {
                wanderers.add(m);
            } else {
                byHome.computeIfAbsent(h.asLong(), k -> new ArrayList<>()).add(m);
            }
        }
        LarderStore store = LarderStore.get(level);
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Long, List<MimicEntity>> e : byHome.entrySet()) {
            BlockPos hp = BlockPos.of(e.getKey());
            StringBuilder sb = new StringBuilder(String.format("@%d,%d 저장고 %.0f │",
                    hp.getX(), hp.getZ(), store.get(hp)));
            for (MimicEntity m : e.getValue()) {
                sb.append(String.format(" #%d(%s%s H%.1f)", m.getId(),
                        m.isFemale() ? "여" : "남", stage1(m), m.getHolding()));
            }
            lines.add(sb.toString());
        }
        if (!wanderers.isEmpty()) {
            StringBuilder sb = new StringBuilder("방랑 │");
            for (MimicEntity m : wanderers) {
                sb.append(String.format(" #%d(%s%s H%.1f)", m.getId(),
                        m.isFemale() ? "여" : "남", stage1(m), m.getHolding()));
            }
            lines.add(sb.toString());
        }
        src.sendSuccess(() -> Component.literal("── 식량 스냅샷 (가구 " + byHome.size()
                + " · 방랑 " + wanderers.size() + ") ──").withStyle(ChatFormatting.GOLD), false);
        for (String line : lines) {
            src.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
            if (SimEvents.enabled()) {
                SimEvents.note(level, "식량", line);
            }
        }
        return lines.size();
    }

    private static String stage1(MimicEntity m) {
        return switch (m.getStage()) {
            case INFANT -> "유";
            case BOY -> "소";
            case ADULT -> "성";
        };
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
