package com.evosim.mod.item;

import com.evosim.core.Category;
import com.evosim.core.ExpressionResolver;
import com.evosim.core.Individual;
import com.evosim.core.Sex;
import com.evosim.core.Tag;
import com.evosim.core.Trait;
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
import java.util.Set;

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
        Set<Trait> active = ExpressionResolver.expressedTraits(ind); // 최종 발현(성별+반발 반영)
        player.displayClientMessage(Component.literal(
                        "발현 " + active.size() + "개 (초록=발현·노랑=우성·회색=흔적·하늘=반발)")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        for (Category cat : Category.values()) {
            player.displayClientMessage(categoryLine(cat, ind, sex, active), false);
        }
        return InteractionResult.SUCCESS;
    }

    private static MutableComponent categoryLine(Category cat, Individual ind, Sex sex, Set<Trait> active) {
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
            line.append(traitComponent(traits.get(i), ind, sex, active));
        }
        return line;
    }

    private static MutableComponent traitComponent(TraitInstance ti, Individual ind, Sex sex,
                                                   Set<Trait> active) {
        StringBuilder s = new StringBuilder(ti.trait().koreanName());
        // 성별발현 태그 표기.
        boolean male = ti.hasTag(Tag.MALE_EXPRESSED);
        boolean female = ti.hasTag(Tag.FEMALE_EXPRESSED);
        if (male && female) {
            s.append("(양성발현)");
        } else if (male) {
            s.append("(남성발현)");
        } else if (female) {
            s.append("(여성발현)");
        }

        ChatFormatting color;
        if (ti.isAnti()) {
            s.append("(반발)");
            if (!ti.expressedFor(sex)) {
                s.append("[흔적]"); // 카드 자신이 성별로 발동 안 함
                color = ChatFormatting.DARK_AQUA;
            } else if (suppressesActive(ind, ti.trait(), sex)) {
                s.append("→무력화중"); // 대상 특성을 실제로 끄는 중
                color = ChatFormatting.AQUA;
            } else {
                s.append("[대상없음]"); // 켜졌지만 끌 대상이 발현 안 됨
                color = ChatFormatting.DARK_AQUA;
            }
        } else {
            boolean sexExpressed = ti.expressedFor(sex);
            boolean fullyActive = sexExpressed && active.contains(ti.trait());
            if (!sexExpressed) {
                s.append("[흔적·성별]"); // 성별발현 불일치로 발동 안 함
                color = ChatFormatting.GRAY;
            } else if (!fullyActive) {
                s.append("[흔적·반발]"); // 반발 카드에 무력화됨
                color = ChatFormatting.GRAY;
            } else {
                color = ti.isDominant() ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
            }
        }
        if (ti.isDominant()) {
            s.append("(우성)");
        }
        return Component.literal(s.toString()).withStyle(color);
    }

    /** 이 반발 카드가 실제로 무력화 중인가 — 같은 대상의 일반 특성이 성별상 발동하려는 상태인지. */
    private static boolean suppressesActive(Individual ind, Trait target, Sex sex) {
        for (TraitInstance ti : ind.allTraits()) {
            if (!ti.isAnti() && ti.trait() == target && ti.expressedFor(sex)) {
                return true;
            }
        }
        return false;
    }

    private static String label(Category cat) {
        return switch (cat) {
            case DISPOSITION -> "성향";
            case PHYSICAL -> "신체";
            case PREFERENCE -> "선호";
        };
    }
}
