package com.evosim.core;

import java.util.EnumSet;
import java.util.Set;

/**
 * 개체가 실제로 보유한 특성 한 개 = 특성 값 + 태그(설계서 §2).
 *
 * <p>같은 특성이라도 개체마다 태그(우성/성별발현)가 다를 수 있으므로 특성 enum과 분리한다.
 * 태그는 유전 시 특성 값에 붙어 함께 전달된다.
 */
public final class TraitInstance {
    private final Trait trait;
    private final Set<Tag> tags;
    // 반발 카드(설계서 §2): true면 이 인스턴스는 대상 특성 `trait`를 무력화하는 억제유전자 카드다.
    // 카드 자신은 효과를 내지 않고, 같은 개체에서 발현 중인 `trait`를 흔적으로 끈다(일반 특성만 대상).
    private final boolean anti;
    // 강도 등급 (설계서 §14: 힘 V~I 등). 무등급 특성은 0, 등급 특성은 1~5(I~V). 선호도 목표 등급을 담는다.
    private final int grade;

    /** 최소·최대 등급 (I~V). */
    public static final int MIN_GRADE = 1;
    public static final int MAX_GRADE = 5;

    public TraitInstance(Trait trait, Set<Tag> tags) {
        this(trait, tags, false, 0);
    }

    public TraitInstance(Trait trait, Set<Tag> tags, boolean anti) {
        this(trait, tags, anti, 0);
    }

    public TraitInstance(Trait trait, Set<Tag> tags, boolean anti, int grade) {
        this.trait = trait;
        this.tags = tags.isEmpty() ? EnumSet.noneOf(Tag.class) : EnumSet.copyOf(tags);
        this.anti = anti;
        this.grade = grade;
    }

    public static TraitInstance of(Trait trait, Tag... tags) {
        return new TraitInstance(trait, toSet(tags), false);
    }

    /** 등급을 가진 특성 인스턴스 (신체 스칼라·등급 선호). 등급은 1~5로 고정(클램프). */
    public static TraitInstance graded(Trait trait, int grade, Tag... tags) {
        return new TraitInstance(trait, toSet(tags), false, clampGrade(grade));
    }

    /** 등급을 1~5로 제한. */
    public static int clampGrade(int g) {
        return g < MIN_GRADE ? MIN_GRADE : Math.min(g, MAX_GRADE);
    }

    /** 등급을 로마숫자 표기(0=빈 문자열). */
    public static String roman(int g) {
        return switch (g) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> "";
        };
    }

    /** 반발 카드 인스턴스 — {@code target}을 무력화한다. */
    public static TraitInstance antiCard(Trait target, Tag... tags) {
        return new TraitInstance(target, toSet(tags), true);
    }

    public static TraitInstance antiCard(Trait target, Set<Tag> tags) {
        return new TraitInstance(target, tags, true);
    }

    private static EnumSet<Tag> toSet(Tag... tags) {
        EnumSet<Tag> set = EnumSet.noneOf(Tag.class);
        for (Tag t : tags) {
            set.add(t);
        }
        return set;
    }

    public Trait trait() {
        return trait;
    }

    public Category category() {
        return trait.category();
    }

    /** 반발(억제유전자) 카드인가. */
    public boolean isAnti() {
        return anti;
    }

    /** 강도 등급(1~5). 무등급이면 0. */
    public int grade() {
        return grade;
    }

    public Set<Tag> tags() {
        return EnumSet.copyOf(tags.isEmpty() ? EnumSet.noneOf(Tag.class) : tags);
    }

    public boolean isDominant() {
        return tags.contains(Tag.DOMINANT);
    }

    /**
     * 이 특성이 주어진 성별에서 발동(발현)하는가 — 성별발현 판정 (설계서 §2 발현 판정 순서 1).
     *
     * <p>남성발현+여성발현 동시 = 상쇄 → 항상 발동. 한쪽만 있으면 그 성별만. 없으면 항상.
     * 발동 안 하면 <b>흔적</b>(유전은 되지만 효과 없음). 발현은 저장하지 않고 참조할 때마다 재판정.
     */
    public boolean expressedFor(Sex sex) {
        boolean male = tags.contains(Tag.MALE_EXPRESSED);
        boolean female = tags.contains(Tag.FEMALE_EXPRESSED);
        if (male && female) {
            return true; // 상쇄 → 항상 발동
        }
        if (male) {
            return sex == Sex.MALE;
        }
        if (female) {
            return sex == Sex.FEMALE;
        }
        return true; // 태그 없음 → 성별 무관 항상 발동
    }

    public boolean hasTag(Tag tag) {
        return tags.contains(tag);
    }

    /** 성별발현 태그(남/여)만 추린 새 EnumSet — 유전 시 흔적으로도 100% 전달됨. */
    public EnumSet<Tag> sexTags() {
        EnumSet<Tag> out = EnumSet.noneOf(Tag.class);
        if (tags.contains(Tag.MALE_EXPRESSED)) {
            out.add(Tag.MALE_EXPRESSED);
        }
        if (tags.contains(Tag.FEMALE_EXPRESSED)) {
            out.add(Tag.FEMALE_EXPRESSED);
        }
        return out;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(trait.koreanName());
        if (grade > 0) {
            sb.append(roman(grade)); // 예: 튼튼III
        }
        if (anti) {
            sb.append("(반발)");
        }
        if (!tags.isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (Tag t : tags) {
                if (!first) {
                    sb.append(',');
                }
                switch (t) {
                    case DOMINANT -> sb.append("우성");
                    case MALE_EXPRESSED -> sb.append("남발");
                    case FEMALE_EXPRESSED -> sb.append("여발");
                }
                first = false;
            }
            sb.append(']');
        }
        return sb.toString();
    }
}
