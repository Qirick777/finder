package com.evosim.mod.entity;

import com.evosim.core.Individual;
import com.evosim.core.ParentingClass;
import com.evosim.core.Sex;
import com.evosim.core.Tag;
import com.evosim.core.Trait;
import com.evosim.core.TraitInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.EnumSet;

/**
 * {@link Individual} ↔ NBT 저장/복원 (설계서 §1, Phase 6 지속성). 미믹이 월드 저장·리로드·청크 언로드를
 * 넘어 <b>특성·육아 클래스·가계·굶주림</b>을 유지하도록 개체 데이터를 통째로 직렬화한다.
 *
 * <p>enum은 이름(name)으로 저장 → 순서 바뀌어도 안전. 알 수 없는 특성/태그는 조용히 건너뛴다.
 */
public final class IndividualNbt {

    private static final int TAG_COMPOUND = 10; // net.minecraft.nbt.Tag.TAG_COMPOUND

    private IndividualNbt() {
    }

    public static CompoundTag save(Individual ind) {
        CompoundTag t = new CompoundTag();
        t.putLong("Id", ind.id());
        t.putString("Sex", ind.sex().name());
        t.putLong("PA", ind.parentAId());
        t.putLong("PB", ind.parentBId());
        t.putInt("Gen", ind.generation());
        t.putInt("Hunger", ind.hungerCount());
        t.putString("PCareM", ind.parentingCareMale().name());
        t.putString("PCareF", ind.parentingCareFemale().name());
        t.putString("MChM", ind.mateChoiceMale().name());   // 짝 까다로움 슬롯 — 누락돼 있던 영속
        t.putString("MChF", ind.mateChoiceFemale().name());
        t.putLongArray("Anc", ind.ancestorIds());           // 직계 조상 명단(근친 회피 §13-E)

        ListTag list = new ListTag();
        for (TraitInstance ti : ind.allTraits()) {
            CompoundTag c = new CompoundTag();
            c.putString("T", ti.trait().name());
            c.putBoolean("A", ti.isAnti());
            if (ti.grade() > 0) {
                c.putInt("R", ti.grade()); // 강도 등급(I~V)
            }
            StringBuilder tags = new StringBuilder();
            for (Tag tag : ti.tags()) {
                if (tags.length() > 0) {
                    tags.append(',');
                }
                tags.append(tag.name());
            }
            c.putString("G", tags.toString());
            list.add(c);
        }
        t.put("Traits", list);
        return t;
    }

    public static Individual load(CompoundTag t) {
        Sex sex = "FEMALE".equals(t.getString("Sex")) ? Sex.FEMALE : Sex.MALE;
        Individual ind = new Individual(t.getLong("Id"), sex,
                t.getLong("PA"), t.getLong("PB"), Math.max(1, t.getInt("Gen")));
        ind.setHungerCount(t.getInt("Hunger"));
        ind.setParentingCareMale(parentingOr(t.getString("PCareM")));
        ind.setParentingCareFemale(parentingOr(t.getString("PCareF")));
        ind.setMateChoiceMale(mateChoiceOr(t.getString("MChM")));
        ind.setMateChoiceFemale(mateChoiceOr(t.getString("MChF")));
        ind.setAncestorIds(t.getLongArray("Anc")); // 없으면 빈 배열(구 세이브 호환)

        ListTag list = t.getList("Traits", TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            Trait trait = traitOrNull(c.getString("T"));
            if (trait == null) {
                continue; // 알 수 없는 특성(버전 차이)은 건너뜀
            }
            EnumSet<Tag> tags = EnumSet.noneOf(Tag.class);
            String g = c.getString("G");
            if (!g.isEmpty()) {
                for (String s : g.split(",")) {
                    Tag tag = tagOrNull(s);
                    if (tag != null) {
                        tags.add(tag);
                    }
                }
            }
            ind.addTrait(new TraitInstance(trait, tags, c.getBoolean("A"), c.getInt("R")));
        }
        return ind;
    }

    private static ParentingClass parentingOr(String s) {
        try {
            return ParentingClass.valueOf(s);
        } catch (IllegalArgumentException e) {
            return ParentingClass.MODERATE;
        }
    }

    private static com.evosim.core.MateChoiceClass mateChoiceOr(String s) {
        try {
            return com.evosim.core.MateChoiceClass.valueOf(s);
        } catch (IllegalArgumentException e) {
            return com.evosim.core.MateChoiceClass.NEUTRAL; // 구 세이브(빈 문자열) 포함
        }
    }

    private static Trait traitOrNull(String s) {
        if ("BAD_COOK".equals(s)) {
            return Trait.RAW_EATER; // 영구 레거시 별칭 — 요리치→날로먹기 개명 전 세이브 보존
        }
        try {
            return Trait.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Tag tagOrNull(String s) {
        try {
            return Tag.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
