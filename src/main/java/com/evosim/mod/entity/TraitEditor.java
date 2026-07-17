package com.evosim.mod.entity;

import com.evosim.core.Genetics;
import com.evosim.core.Tag;
import com.evosim.core.Trait;
import com.evosim.core.TraitInstance;
import com.evosim.core.Individual;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.EnumSet;
import java.util.List;

/**
 * 특성 편집 서버 코어 (편집봉). 패킷 핸들러와 검증 무대(editx)가 <b>같은 진입점</b>을 쓴다 —
 * 판정-코드 대칭. 편집 개체가 합법 개체로 남도록 유전 규칙(카테고리 슬롯 3·등급 축 클램프)을
 * 그대로 강제하고, 적용 후 속성 재적용({@link MimicEntity#refreshTraitAttributes})까지 책임진다.
 */
public final class TraitEditor {

    public static final int OP_ADD = 0;
    public static final int OP_REMOVE = 1;
    public static final int OP_TOGGLE_DOMINANT = 2;
    public static final int OP_GRADE_DELTA = 3;
    /** 성명 직접 입력 — text = "first|middle|last" (middle 공백 허용). */
    public static final int OP_SET_NAME = 4;
    private static final int NAME_MAX = 24;

    private TraitEditor() {
    }

    /**
     * 편집 연산 적용. editor 가 null 이면 사거리 검사 생략(검증 무대).
     *
     * @param traitOrdinal ADD 전용 — {@link Trait} ordinal
     * @param index        REMOVE/TOGGLE/GRADE 전용 — {@code allTraits()} 순서 인덱스
     * @param value        ADD=지정 등급(0이면 Ⅲ) / GRADE_DELTA=±1
     * @return 상태 메시지(성공·거부 사유 — 화면 하단 표시)
     */
    public static String apply(ServerLevel level, ServerPlayer editor, int entityId,
                               int op, int traitOrdinal, int index, int value, boolean dominant) {
        return apply(level, editor, entityId, op, traitOrdinal, index, value, dominant, "");
    }

    public static String apply(ServerLevel level, ServerPlayer editor, int entityId,
                               int op, int traitOrdinal, int index, int value, boolean dominant,
                               String text) {
        Entity e = level.getEntity(entityId);
        if (!(e instanceof MimicEntity m) || !m.isAlive() || m.getIndividual() == null) {
            return "대상 없음";
        }
        // 사거리 제한 없음 — 편집 도중 미믹이 걸어가 버려 "너무 멀다"로 조작이 끊기던 문제.
        // 화면을 연 시점의 대상에 계속 조작 가능(크리에이티브 도구 — 악용 경로 아님).
        Individual ind = m.getIndividual();
        List<TraitInstance> all = ind.allTraits();
        String status;
        switch (op) {
            case OP_ADD -> {
                if (traitOrdinal < 0 || traitOrdinal >= Trait.values().length) {
                    return "잘못된 특성";
                }
                Trait t = Trait.values()[traitOrdinal];
                for (TraitInstance ti : all) {
                    if (!ti.isAnti() && ti.trait() == t) {
                        return "이미 보유: " + t.koreanName();
                    }
                }
                if (ind.traitsIn(t.category()).size() >= Genetics.MAX_PER_CATEGORY) {
                    return "슬롯 초과 — 카테고리당 " + Genetics.MAX_PER_CATEGORY + "개";
                }
                int grade = t.isGraded() ? TraitInstance.clampGrade(value <= 0 ? 3 : value) : 0;
                EnumSet<Tag> tags = dominant ? EnumSet.of(Tag.DOMINANT) : EnumSet.noneOf(Tag.class);
                ind.addTrait(new TraitInstance(t, tags, false, grade));
                status = "추가: " + t.koreanName()
                        + (grade > 0 ? TraitInstance.roman(grade) : "") + (dominant ? "(우성)" : "");
            }
            case OP_REMOVE -> {
                if (index < 0 || index >= all.size()) {
                    return "잘못된 인덱스";
                }
                TraitInstance ti = all.get(index);
                ind.removeTrait(ti);
                status = "삭제: " + ti.trait().koreanName();
            }
            case OP_TOGGLE_DOMINANT -> {
                if (index < 0 || index >= all.size()) {
                    return "잘못된 인덱스";
                }
                TraitInstance ti = all.get(index);
                EnumSet<Tag> tags = EnumSet.noneOf(Tag.class);
                tags.addAll(ti.tags());
                boolean on = !tags.remove(Tag.DOMINANT);
                if (on) {
                    tags.add(Tag.DOMINANT);
                }
                ind.replaceTrait(ti, new TraitInstance(ti.trait(), tags, ti.isAnti(), ti.grade()));
                status = ti.trait().koreanName() + " 우성 " + (on ? "지정" : "해제");
            }
            case OP_GRADE_DELTA -> {
                if (index < 0 || index >= all.size()) {
                    return "잘못된 인덱스";
                }
                TraitInstance ti = all.get(index);
                if (!ti.trait().isGraded()) {
                    return "무등급 축: " + ti.trait().koreanName();
                }
                int g = TraitInstance.clampGrade((ti.grade() <= 0 ? 3 : ti.grade()) + value);
                ind.replaceTrait(ti, new TraitInstance(ti.trait(), ti.tags(), ti.isAnti(), g));
                status = ti.trait().koreanName() + " 등급 " + TraitInstance.roman(g);
            }
            case OP_SET_NAME -> {
                // text = "first|middle|last" — middle 은 공백 허용, first·last 필수, 각 24자 제한.
                String[] parts = (text == null ? "" : text).split("\\|", -1);
                if (parts.length != 3) {
                    return "이름 형식 오류";
                }
                String f = parts[0].trim();
                String mid = parts[1].trim();
                String l = parts[2].trim();
                if (f.isEmpty() || l.isEmpty()) {
                    return "이름·성은 비울 수 없음";
                }
                if (f.length() > NAME_MAX || mid.length() > NAME_MAX || l.length() > NAME_MAX) {
                    return "이름이 너무 김(최대 " + NAME_MAX + "자)";
                }
                ind.setName(f, mid, l);
                status = "개명: " + ind.fullName();
            }
            default -> {
                return "알 수 없는 연산";
            }
        }
        // 신체 특성(튼튼·재빠름·힘…) 편집이 즉시 체력·속도·공격에 반영되도록 재적용.
        m.refreshTraitAttributes();
        return status;
    }
}
