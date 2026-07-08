package com.evosim.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * 유전 엔진 (설계서 §2 유전 알고리즘). 마크에 안 얽힌 순수 함수(§18) — 헤드리스 검증이 공짜로 따라옴.
 *
 * <p>Phase 0/1 범위: 카테고리별 "반발 안 하는 3개 뽑기 + 중립", 우성 75% 전달, 돌연변이 2%,
 * 성별발현 태그 유전. (반발 카드·흔적 보상은 이후 페이즈에서 확장.)
 */
public final class Genetics {

    /** 우성 특성이 자식에게 우성으로 유전될 확률 (설계서 §2, 확정값). */
    public static final double DOMINANT_INHERIT_RATE = 0.75;
    /** 돌연변이 확률 (설계서 §2, 확정값). */
    public static final double MUTATION_RATE = 0.02;
    /** 카테고리당 최대 발현 특성 수 (설계서 §2). */
    public static final int MAX_PER_CATEGORY = 3;

    // 1세대 랜덤 부여 시 태그가 붙을 확률 (밸런싱 대상 — 검증엔 영향 없음).
    private static final double FIRST_GEN_DOMINANT_RATE = 0.50;
    private static final double FIRST_GEN_SEX_TAG_RATE = 0.15;

    private static final Map<Category, List<Trait>> POOL = buildPool();

    private Genetics() {
    }

    private static Map<Category, List<Trait>> buildPool() {
        Map<Category, List<Trait>> pool = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            pool.put(c, new ArrayList<>());
        }
        for (Trait t : Trait.values()) {
            pool.get(t.category()).add(t);
        }
        return pool;
    }

    /**
     * 1세대(스폰에그) 개체 — 완전 랜덤 부여, 9개 꽉 채움 (설계서 §2 첫 세대).
     */
    public static Individual randomFirstGen(long id, DeterministicRng rng) {
        Sex sex = rng.nextBoolean() ? Sex.MALE : Sex.FEMALE;
        Individual ind = new Individual(id, sex, 0, 0, 1);
        for (Category cat : Category.values()) {
            List<Trait> shuffled = new ArrayList<>(POOL.get(cat));
            rng.shuffle(shuffled);
            for (Trait t : shuffled) {
                if (ind.traitsIn(cat).size() >= MAX_PER_CATEGORY) {
                    break;
                }
                if (conflicts(ind.traitsIn(cat), t)) {
                    continue;
                }
                EnumSet<Tag> tags = EnumSet.noneOf(Tag.class);
                if (rng.chance(FIRST_GEN_DOMINANT_RATE)) {
                    tags.add(Tag.DOMINANT);
                }
                if (rng.chance(FIRST_GEN_SEX_TAG_RATE)) {
                    tags.add(Tag.MALE_EXPRESSED);
                }
                if (rng.chance(FIRST_GEN_SEX_TAG_RATE)) {
                    tags.add(Tag.FEMALE_EXPRESSED);
                }
                ind.addTrait(new TraitInstance(t, tags));
            }
        }
        return ind;
    }

    /**
     * 자식 1명 생성 (설계서 §2 유전 알고리즘). stats는 검증용, 게임 실행 시 null 허용.
     */
    public static Individual breed(long childId, Individual a, Individual b,
                                   DeterministicRng rng, int generation, BreedStats stats) {
        Sex sex = rng.nextBoolean() ? Sex.MALE : Sex.FEMALE;
        Individual child = new Individual(childId, sex, a.id(), b.id(), generation);

        for (Category cat : Category.values()) {
            List<TraitInstance> chosen = selectCategory(cat, a, b, rng, stats);
            mutate(cat, chosen, rng, stats);
            for (TraitInstance ti : chosen) {
                child.addTrait(ti);
            }
        }
        return child;
    }

    /** 한 카테고리에서 부모 후보를 뽑아 최대 3개 채움 (우성 우선 확정 + 반발 회피). */
    private static List<TraitInstance> selectCategory(Category cat, Individual a, Individual b,
                                                      DeterministicRng rng, BreedStats stats) {
        // 후보 = 아빠 + 엄마의 그 카테고리 특성. 우성 후보를 먼저(우선 확정), 각 그룹 셔플.
        List<TraitInstance> dominantCands = new ArrayList<>();
        List<TraitInstance> otherCands = new ArrayList<>();
        for (TraitInstance ti : a.traitsIn(cat)) {
            (ti.isDominant() ? dominantCands : otherCands).add(ti);
        }
        for (TraitInstance ti : b.traitsIn(cat)) {
            (ti.isDominant() ? dominantCands : otherCands).add(ti);
        }
        rng.shuffle(dominantCands);
        rng.shuffle(otherCands);

        List<TraitInstance> ordered = new ArrayList<>(dominantCands);
        ordered.addAll(otherCands);

        List<TraitInstance> chosen = new ArrayList<>();
        for (TraitInstance cand : ordered) {
            if (chosen.size() >= MAX_PER_CATEGORY) {
                break;
            }
            if (containsTrait(chosen, cand.trait())) {
                continue; // 중복 특성 금지
            }
            if (conflicts(chosen, cand.trait())) {
                continue; // 반발 특성 건너뜀
            }
            // 성별발현 태그는 흔적으로도 100% 유전 (설계서 §2).
            EnumSet<Tag> tags = cand.sexTags();
            // 우성 후보는 75%로 우성 유지, 25%는 우성을 잃음("자식 4명 중 ~1명은 다름").
            if (cand.isDominant()) {
                if (stats != null) {
                    stats.dominantInherited++;
                }
                if (rng.chance(DOMINANT_INHERIT_RATE)) {
                    tags.add(Tag.DOMINANT);
                    if (stats != null) {
                        stats.dominantRetained++;
                    }
                }
            }
            chosen.add(new TraitInstance(cand.trait(), tags));
        }
        return chosen;
    }

    /** 돌연변이: 2% 확률로 뽑힌 특성 하나를 다른 걸로 교체 + 우성여부 재주사위 (설계서 §2 step4). */
    private static void mutate(Category cat, List<TraitInstance> chosen,
                               DeterministicRng rng, BreedStats stats) {
        if (stats != null) {
            stats.mutationRolls++;
        }
        if (!rng.chance(MUTATION_RATE) || chosen.isEmpty()) {
            return;
        }
        int idx = rng.nextInt(chosen.size());
        Trait replacement = pickReplacement(cat, chosen, idx, rng);
        if (replacement == null) {
            return;
        }
        EnumSet<Tag> tags = EnumSet.noneOf(Tag.class);
        if (rng.chance(0.5)) { // 우성여부 재주사위
            tags.add(Tag.DOMINANT);
        }
        chosen.set(idx, new TraitInstance(replacement, tags));
        if (stats != null) {
            stats.mutations++;
        }
    }

    /** idx 자리를 대체할, 나머지 선택과 반발/중복하지 않는 같은 카테고리 특성. */
    private static Trait pickReplacement(Category cat, List<TraitInstance> chosen, int idx,
                                         DeterministicRng rng) {
        List<TraitInstance> others = new ArrayList<>(chosen);
        others.remove(idx);
        List<Trait> options = new ArrayList<>();
        for (Trait t : POOL.get(cat)) {
            if (containsTrait(others, t)) {
                continue;
            }
            if (conflicts(others, t)) {
                continue;
            }
            if (chosen.get(idx).trait() == t) {
                continue; // 같은 특성으로 "교체"는 의미 없음
            }
            options.add(t);
        }
        return rng.pick(options);
    }

    private static boolean containsTrait(List<TraitInstance> list, Trait trait) {
        for (TraitInstance ti : list) {
            if (ti.trait() == trait) {
                return true;
            }
        }
        return false;
    }

    private static boolean conflicts(List<TraitInstance> list, Trait trait) {
        for (TraitInstance ti : list) {
            if (ti.trait().conflictsWith(trait)) {
                return true;
            }
        }
        return false;
    }
}
