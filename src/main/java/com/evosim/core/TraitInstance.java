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

    public TraitInstance(Trait trait, Set<Tag> tags) {
        this.trait = trait;
        this.tags = tags.isEmpty() ? EnumSet.noneOf(Tag.class) : EnumSet.copyOf(tags);
    }

    public static TraitInstance of(Trait trait, Tag... tags) {
        EnumSet<Tag> set = EnumSet.noneOf(Tag.class);
        for (Tag t : tags) {
            set.add(t);
        }
        return new TraitInstance(trait, set);
    }

    public Trait trait() {
        return trait;
    }

    public Category category() {
        return trait.category();
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
