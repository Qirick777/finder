package com.evosim.mod.item;

import com.evosim.core.Category;
import com.evosim.core.Individual;
import com.evosim.core.Sex;
import com.evosim.core.TraitInstance;
import com.evosim.mod.entity.MimicEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 미믹 검사봉 — 미믹을 우클릭하면 보유 특성 전체를 채팅에 표시(설계서 §14 "개체 클릭 시 특성 목록").
 *
 * <p>발현/흔적/반발 카드/우성을 색·표기로 구분해 특성 부여가 제대로 됐는지 눈으로 확인.
 */
public class TraitScannerItem extends Item {

    public TraitScannerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                  LivingEntity target, InteractionHand hand) {
        if (!(target instanceof MimicEntity mimic)) {
            return InteractionResult.PASS;
        }
        if (player.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }

        Individual ind = mimic.getIndividual();
        String sexLabel = mimic.isFemale() ? "여" : "남";
        String stageLabel = switch (mimic.getStage()) {
            case INFANT -> "유아";
            case BOY -> "소년";
            case ADULT -> "성년";
        };

        player.displayClientMessage(Component.literal(
                        "=== 미믹 #" + mimic.getId() + " [" + sexLabel + " · " + stageLabel + "] ===")
                .withStyle(ChatFormatting.GOLD), false);

        if (ind == null) {
            player.displayClientMessage(Component.literal(
                            "개체 데이터 없음 (리로드된 개체 — 저장은 Phase 6). 새로 소환해 확인하세요.")
                    .withStyle(ChatFormatting.RED), false);
            return InteractionResult.SUCCESS;
        }

        Sex sex = ind.sex();
        for (Category cat : Category.values()) {
            player.displayClientMessage(categoryLine(cat, ind, sex), false);
        }
        return InteractionResult.SUCCESS;
    }

    private static MutableComponent categoryLine(Category cat, Individual ind, Sex sex) {
        MutableComponent line = Component.literal("[" + label(cat) + "] ")
                .withStyle(ChatFormatting.GRAY);
        List<TraitInstance> traits = ind.traitsIn(cat);
        if (traits.isEmpty()) {
            return line.append(Component.literal("(없음)").withStyle(ChatFormatting.DARK_GRAY));
        }
        for (int i = 0; i < traits.size(); i++) {
            if (i > 0) {
                line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
            }
            line.append(traitComponent(traits.get(i), sex));
        }
        return line;
    }

    private static MutableComponent traitComponent(TraitInstance ti, Sex sex) {
        StringBuilder text = new StringBuilder(ti.trait().koreanName());
        if (ti.isAnti()) {
            text.append("(반발)");
        }
        boolean expressed = !ti.isAnti() && ti.expressedFor(sex);
        boolean vestigial = !ti.isAnti() && !expressed;
        if (vestigial) {
            text.append("[흔적]");
        }
        if (ti.isDominant()) {
            text.append("(우성)");
        }
        // 색: 반발=하늘 / 흔적=회색 / 발현=초록(우성이면 노랑).
        ChatFormatting color;
        if (ti.isAnti()) {
            color = ChatFormatting.AQUA;
        } else if (vestigial) {
            color = ChatFormatting.GRAY;
        } else if (ti.isDominant()) {
            color = ChatFormatting.YELLOW;
        } else {
            color = ChatFormatting.GREEN;
        }
        return Component.literal(text.toString()).withStyle(color);
    }

    private static String label(Category cat) {
        return switch (cat) {
            case DISPOSITION -> "성향";
            case PHYSICAL -> "신체";
            case PREFERENCE -> "선호";
        };
    }
}
