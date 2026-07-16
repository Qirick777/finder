package com.evosim.mod.item;

import com.evosim.core.Individual;
import com.evosim.core.Tag;
import com.evosim.core.TraitInstance;
import com.evosim.mod.entity.MimicEntity;
import com.evosim.mod.net.ModNetwork;
import com.evosim.mod.net.OpenTraitEditorPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 미믹 특성 편집봉 — 미믹 우클릭 → 편집 화면(추가/삭제/우성/등급). 크리에이티브 관찰 도구
 * (/give evosim:trait_editor). 모든 적용은 서버 {@link com.evosim.mod.entity.TraitEditor} 검증 경유.
 */
public class TraitEditorItem extends Item {

    public TraitEditorItem(Properties properties) {
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
        if (player instanceof ServerPlayer sp) {
            sendEditor(sp, mimic.getId(), "");
        }
        return InteractionResult.CONSUME;
    }

    /** 현재 서버 상태로 편집 화면 패킷 전송(열기와 연산 후 갱신이 같은 경로). */
    public static void sendEditor(ServerPlayer sp, int entityId, String status) {
        Entity e = sp.serverLevel().getEntity(entityId);
        if (!(e instanceof MimicEntity m) || m.getIndividual() == null) {
            return;
        }
        Individual ind = m.getIndividual();
        List<OpenTraitEditorPacket.Entry> entries = new ArrayList<>();
        for (TraitInstance ti : ind.allTraits()) {
            int bits = (ti.isDominant() ? OpenTraitEditorPacket.BIT_DOMINANT : 0)
                    | (ti.hasTag(Tag.MALE_EXPRESSED) ? OpenTraitEditorPacket.BIT_MALE : 0)
                    | (ti.hasTag(Tag.FEMALE_EXPRESSED) ? OpenTraitEditorPacket.BIT_FEMALE : 0);
            entries.add(new OpenTraitEditorPacket.Entry(
                    ti.trait().ordinal(), bits, ti.grade(), ti.isAnti()));
        }
        String stage = switch (m.getStage()) {
            case INFANT -> "유아";
            case BOY -> "소년";
            case ADULT -> "성년";
            case ELDER -> "노년";
        };
        String headline = "N" + ind.id() + " " + (m.isFemale() ? "♀" : "♂") + stage
                + " · 세대" + ind.generation();
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp),
                new OpenTraitEditorPacket(m.getId(), headline, entries, status));
    }
}
