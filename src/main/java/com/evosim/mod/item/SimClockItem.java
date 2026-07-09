package com.evosim.mod.item;

import com.evosim.core.LifeStage;
import com.evosim.core.Schedule;
import com.evosim.mod.entity.MimicEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 생태 시계 (설계서 §16 하루 리듬). 들고 우클릭하면 <b>지금이 무슨 시간이고, 미믹들이 지금 무엇을 할
 * 시간인지</b>를 채팅에 보여준다 — 채집/구애/귀가·정산·번식/취침. 주변 미믹의 현재 상태 요약도 함께.
 *
 * <p>표현층 관찰 도구. 판정 자체는 순수 {@link Schedule#globalPhase(long)}(§18)에 위임.
 */
public class SimClockItem extends Item {

    private static final double SCAN_RADIUS = 48.0;

    public SimClockItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }

        long dayTime = level.getDayTime();
        long day = level.getGameTime() / 24000L;
        Schedule.Phase phase = Schedule.globalPhase(dayTime);
        int tod = (int) (((dayTime % 24000L) + 24000L) % 24000L);

        player.displayClientMessage(Component.literal(
                        "=== 생태 시계 · day" + day + " · " + tod + "틱 ===")
                .withStyle(ChatFormatting.GOLD), false);
        player.displayClientMessage(Component.literal(
                        "지금은 [" + phaseLabel(phase) + "] — " + phaseAction(phase))
                .withStyle(phaseColor(phase)), false);

        // 주변 미믹 현황 (지금 이 시간대에 실제로 그러고 있는지 눈으로 대조).
        List<MimicEntity> near = level.getEntitiesOfClass(MimicEntity.class,
                player.getBoundingBox().inflate(SCAN_RADIUS));
        if (near.isEmpty()) {
            player.displayClientMessage(Component.literal("주변 " + (int) SCAN_RADIUS + "블록 내 미믹 없음")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
            return InteractionResultHolder.success(stack);
        }
        int adult = 0;
        int boy = 0;
        int infant = 0;
        int wanderer = 0;
        int settled = 0;
        for (MimicEntity m : near) {
            switch (m.getStage()) {
                case ADULT -> adult++;
                case BOY -> boy++;
                case INFANT -> infant++;
            }
            if (m.isWanderer()) {
                wanderer++;
            } else if (m.getStage() == LifeStage.ADULT && m.getHomePos() != null) {
                settled++;
            }
        }
        player.displayClientMessage(Component.literal(
                        "주변 미믹 " + near.size() + "명 → 성년 " + adult + "(정착 " + settled
                                + "·방랑 " + wanderer + ") · 소년 " + boy + " · 유아 " + infant)
                .withStyle(ChatFormatting.AQUA), false);
        return InteractionResultHolder.success(stack);
    }

    private static String phaseLabel(Schedule.Phase p) {
        return switch (p) {
            case SLEEP -> "취침";
            case WORK -> "노동";
            case WANDER -> "배회";
            case NIGHT -> "밤";
        };
    }

    private static String phaseAction(Schedule.Phase p) {
        return switch (p) {
            case SLEEP -> "허기 소모 없이 휴식 (거처에서 잠)";
            case WORK -> "채집·사냥으로 식량 확보";
            case WANDER -> "산책하며 짝 찾기·구애 (방랑자)";
            case NIGHT -> "귀가 → 밤 정산 → 식량 확보 시 번식 · 유아 급식";
        };
    }

    private static ChatFormatting phaseColor(Schedule.Phase p) {
        return switch (p) {
            case SLEEP -> ChatFormatting.DARK_GRAY;
            case WORK -> ChatFormatting.GREEN;
            case WANDER -> ChatFormatting.YELLOW;
            case NIGHT -> ChatFormatting.LIGHT_PURPLE;
        };
    }
}
