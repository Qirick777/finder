package com.evosim.mod.item;

import com.evosim.core.Category;
import com.evosim.core.ExpressionResolver;
import com.evosim.core.Individual;
import com.evosim.core.Sex;
import com.evosim.core.Tag;
import com.evosim.core.Trait;
import com.evosim.core.TraitInstance;
import com.evosim.mod.entity.MimicEntity;
import com.evosim.mod.gui.PedigreeSnapshot;
import com.evosim.mod.net.ModNetwork;
import com.evosim.mod.net.PedigreePacket;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 미믹 검사봉 (설계서 §14). 미믹을 우클릭하면 <b>현재 모드</b>의 정보를 채팅에 표시.
 * 쉬프트+스크롤로 모드 순환: 특성 / 짝 / 거처 / 가족 인벤토리.
 * 쉬프트+우클릭 = 모드 무관 <b>가계도 GUI</b>(조상 3세대, 노드 클릭으로 위로 항해).
 */
public class TraitScannerItem extends Item {

    private static final int HOME_MARK_INTERVAL = 15;   // 파티클 갱신 주기(틱) — 렉 방지
    private static final double HOME_MARK_RANGE = 80.0;  // 이 반경 내 미믹 거처만 표시

    public TraitScannerItem(Properties properties) {
        super(properties);
    }

    /**
     * 거처 모드로 <b>들고 있으면</b> 주변 미믹들의 거처를 파티클 기둥으로 표시(설계서 §14 관찰).
     * 서버측·주기적(15틱)·거처 좌표 중복 제거 → 다수 미믹이어도 렉 없이 몇 개만 뿌린다.
     */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (!selected || level.isClientSide || ScannerMode.of(stack) != ScannerMode.HOME) {
            return;
        }
        if (level.getGameTime() % HOME_MARK_INTERVAL != 0
                || !(level instanceof ServerLevel sl) || !(entity instanceof Player player)) {
            return;
        }
        Set<Long> shown = new HashSet<>();
        for (MimicEntity m : sl.getEntitiesOfClass(
                MimicEntity.class, player.getBoundingBox().inflate(HOME_MARK_RANGE))) {
            BlockPos h = m.getHomePos();
            if (h == null || !shown.add(h.asLong())) {
                continue; // 같은 거처는 한 번만
            }
            sl.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                    h.getX() + 0.5, h.getY() + 1.2, h.getZ() + 0.5, 5, 0.2, 0.6, 0.2, 0.0);
        }
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

        // 웅크려 우클릭 = 모드 무관 가계도 열기 (§14). 스냅샷을 만들어 클라 화면 패킷으로 회신.
        if (player.isShiftKeyDown()) {
            if (mimic.isStageActor()) {
                // 무대 개체는 원장 미등록(통계 오염 방지) — 전부 미상으로 그려지느니 이유를 말한다.
                player.displayClientMessage(Component.literal(
                        "검증 무대 개체 — 혈통 원장에 없어 가계도를 열 수 없습니다.")
                        .withStyle(ChatFormatting.GRAY), true);
            } else if (mimic.getIndividual() != null
                    && player instanceof ServerPlayer sp && player.level() instanceof ServerLevel sl) {
                ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp),
                        new PedigreePacket(PedigreeSnapshot.build(sl, mimic.getIndividual().id())));
            }
            return InteractionResult.CONSUME;
        }

        ScannerMode mode = ScannerMode.of(stack);
        String sexLabel = mimic.isFemale() ? "여" : "남";
        String stageLabel = switch (mimic.getStage()) {
            case INFANT -> "유아";
            case BOY -> "소년";
            case ADULT -> "성년";
            case ELDER -> "노년";
        };
        player.displayClientMessage(Component.literal(
                        "=== 미믹 #" + mimic.getId() + " [" + sexLabel + " · " + stageLabel
                                + "] · " + mode.label() + " ===")
                .withStyle(ChatFormatting.GOLD), false);

        Individual ind = mimic.getIndividual();
        if (ind == null) {
            player.displayClientMessage(Component.literal(
                            "개체 데이터 없음 (구버전 개체 — 새로 소환해 확인하세요).")
                    .withStyle(ChatFormatting.RED), false);
            return InteractionResult.SUCCESS;
        }

        switch (mode) {
            case TRAIT -> showTraits(player, ind);
            case MATE -> showMate(player, mimic, ind);
            case HOME -> showHome(player, mimic);
            case INVENTORY -> showInventory(player, mimic);
        }
        return InteractionResult.SUCCESS;
    }

    // ── 특성 모드 ──
    private static void showTraits(Player player, Individual ind) {
        Sex sex = ind.sex();
        Set<Trait> active = ExpressionResolver.expressedTraits(ind);
        player.displayClientMessage(Component.literal(
                        "발현 " + active.size() + "개 (초록=발현·노랑=우성·회색=흔적·하늘=반발)")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        player.displayClientMessage(Component.literal("육아: "
                        + ind.parentingCareMale().label() + "[남]·"
                        + ind.parentingCareFemale().label() + "[여] → 활성 " + ind.parentingCare().label())
                .withStyle(ChatFormatting.LIGHT_PURPLE), false);
        for (Category cat : Category.values()) {
            if (cat == Category.DISPOSITION) {
                // 성향 슬롯을 공유하는 능력특성은 따로 묶어 보여준다(성향 / 능력).
                player.displayClientMessage(dispositionLine(ind, sex, active, false), false);
                player.displayClientMessage(dispositionLine(ind, sex, active, true), false);
            } else {
                player.displayClientMessage(categoryLine(cat, ind, sex, active), false);
            }
        }
    }

    /** 성향 카테고리 표시 — abilityOnly=false 면 순수 성향, true 면 능력특성만. */
    private static MutableComponent dispositionLine(Individual ind, Sex sex, Set<Trait> active,
                                                    boolean abilityOnly) {
        MutableComponent line = Component.literal("[" + (abilityOnly ? "능력" : "성향") + "] ")
                .withStyle(ChatFormatting.GRAY);
        List<TraitInstance> picked = new java.util.ArrayList<>();
        for (TraitInstance ti : ind.traitsIn(Category.DISPOSITION)) {
            if (ti.trait().isAbility() == abilityOnly) {
                picked.add(ti);
            }
        }
        if (picked.isEmpty()) {
            return line.append(Component.literal("(없음)").withStyle(ChatFormatting.DARK_GRAY));
        }
        for (int i = 0; i < picked.size(); i++) {
            if (i > 0) {
                line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
            }
            line.append(traitComponent(picked.get(i), ind, sex, active));
        }
        return line;
    }

    // ── 짝 모드 ──
    private static void showMate(Player player, MimicEntity mimic, Individual ind) {
        var mc = ind.mateChoice();
        player.displayClientMessage(Component.literal(
                        "까다로움: " + mc.label() + "(k" + mc.k() + "·탐색" + (int) mc.searchSeconds() + "s)"
                                + " · 상태: " + mimic.getMateState()
                                + " · 세트 " + ind.mateChoiceMale().label() + "[남]·"
                                + ind.mateChoiceFemale().label() + "[여]")
                .withStyle(ChatFormatting.GRAY), false);

        List<String> prefs = new ArrayList<>();
        for (Trait t : ExpressionResolver.expressedTraits(ind)) {
            if (t.category() == Category.PREFERENCE) {
                prefs.add(t.koreanName());
            }
        }
        player.displayClientMessage(Component.literal(
                        "선호(발현): " + (prefs.isEmpty() ? "없음" : String.join("·", prefs)))
                .withStyle(ChatFormatting.GREEN), false);

        int family = familyAt(mimic).size();
        String famText = family <= 1 ? "독신/방랑" : family + "명 동거";
        player.displayClientMessage(Component.literal("가족: " + famText)
                .withStyle(ChatFormatting.AQUA), false);

        player.displayClientMessage(Component.literal(String.format(
                        "식량: 보유 %.2f · 거처 저장고 %.1f · %s (저장고 여유 시 번식)",
                        mimic.getHolding(), mimic.getLastSurplus(),
                        mimic.wasLastFed() ? "양호" : "위급(굶는 중)"))
                .withStyle(mimic.wasLastFed() ? ChatFormatting.GREEN : ChatFormatting.RED), false);
    }

    // ── 거처 모드 ──
    private static void showHome(Player player, MimicEntity mimic) {
        BlockPos home = mimic.getHomePos();
        if (home == null) {
            player.displayClientMessage(Component.literal("거처: 없음 (방랑자)")
                    .withStyle(ChatFormatting.GRAY), false);
            return;
        }
        player.displayClientMessage(Component.literal(
                        "거처 좌표: (" + home.getX() + ", " + home.getY() + ", " + home.getZ() + ")")
                .withStyle(ChatFormatting.GREEN), false);
        List<MimicEntity> fam = familyAt(mimic);
        StringBuilder ids = new StringBuilder();
        for (MimicEntity m : fam) {
            ids.append('#').append(m.getId()).append(m.isFemale() ? "여 " : "남 ");
        }
        player.displayClientMessage(Component.literal(
                        "같은 거처 " + fam.size() + "명: " + ids).withStyle(ChatFormatting.AQUA), false);
    }

    // ── 가족 인벤토리 모드 ──
    private static void showInventory(Player player, MimicEntity mimic) {
        BlockPos home = mimic.getHomePos();
        if (home == null) {
            player.displayClientMessage(Component.literal("거처 없음 → 가족 창고 없음 (방랑자)")
                    .withStyle(ChatFormatting.GRAY), false);
            return;
        }
        List<MimicEntity> fam = familyAt(mimic);
        player.displayClientMessage(Component.literal(
                        "거처 (" + home.getX() + "," + home.getY() + "," + home.getZ() + ") · 가족 " + fam.size() + "명")
                .withStyle(ChatFormatting.GREEN), false);
        double carried = 0.0;
        for (MimicEntity m : fam) {
            carried += m.getHolding();
        }
        player.displayClientMessage(Component.literal(String.format(
                        "가족 소지 합 %.2f · 거처 저장고 %.1f (수시 정산 — 정수 입출금)",
                        carried, mimic.getLastSurplus()))
                .withStyle(ChatFormatting.GOLD), false);
    }

    /** 같은 거처(homePos)를 가리키는 미믹들 = 한 가족(§3). */
    private static List<MimicEntity> familyAt(MimicEntity mimic) {
        BlockPos home = mimic.getHomePos();
        List<MimicEntity> fam = new ArrayList<>();
        if (home == null) {
            return fam;
        }
        for (MimicEntity m : mimic.level().getEntitiesOfClass(
                MimicEntity.class, mimic.getBoundingBox().inflate(96.0))) {
            if (home.equals(m.getHomePos())) {
                fam.add(m);
            }
        }
        return fam;
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
        if (ti.grade() > 0) {
            s.append(TraitInstance.roman(ti.grade())); // 강도 등급(예: 튼튼III)
        }
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
                s.append("[흔적]");
                color = ChatFormatting.DARK_AQUA;
            } else if (suppressesActive(ind, ti.trait(), sex)) {
                s.append("→무력화중");
                color = ChatFormatting.AQUA;
            } else {
                s.append("[대상없음]");
                color = ChatFormatting.DARK_AQUA;
            }
        } else {
            boolean sexExpressed = ti.expressedFor(sex);
            boolean fullyActive = sexExpressed && active.contains(ti.trait());
            if (!sexExpressed) {
                s.append("[흔적·성별]");
                color = ChatFormatting.GRAY;
            } else if (!fullyActive) {
                s.append("[흔적·반발]");
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
