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
    /** 육아 클래스 돌연변이 확률. */
    public static final double PARENTING_MUTATION_RATE = 0.10;
    /** 카테고리당 최대 <b>발현</b> 특성 수 = 일반 특성 1차 선택 상한 (설계서 §2). */
    public static final int MAX_PER_CATEGORY = 3;
    /** 카테고리당 반발 카드 상한 (9 일반 + 6 반발 = 15, 설계서 §2). */
    public static final int MAX_ANTI_PER_CATEGORY = 2;

    // 1세대 랜덤 부여 시 태그가 붙을 확률 (밸런싱 대상 — 검증엔 영향 없음).
    // 우성은 75% 유지라 매세대 25%씩 감쇠 → 낮은 균형으로 수렴. 시드를 균형 근처(20%)로 잡아
    // 1세대가 우성 도배로 보이지 않게 한다(설계서 §2: 우성은 전달 우위일 뿐 생존 우위 아님).
    private static final double FIRST_GEN_DOMINANT_RATE = 0.20;
    private static final double FIRST_GEN_SEX_TAG_RATE = 0.15;
    private static final double FIRST_GEN_ANTI_RATE = 0.20; // 카테고리마다 반발 카드 1장 심을 확률

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
        return randomFirstGen(id, rng, rng.nextBoolean() ? Sex.MALE : Sex.FEMALE);
    }

    /** 성별을 지정한 1세대 개체 — 게임 내 무대 세팅(성별 지정 소환) 등에서 사용. */
    public static Individual randomFirstGen(long id, DeterministicRng rng, Sex sex) {
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
            // 반발 카드 종자 심기 — 풀에 억제유전자가 돌게(유전·발현 검증 재료).
            if (rng.chance(FIRST_GEN_ANTI_RATE)) {
                Trait target = rng.pick(POOL.get(cat));
                EnumSet<Tag> antiTags = EnumSet.noneOf(Tag.class);
                if (rng.chance(FIRST_GEN_DOMINANT_RATE)) {
                    antiTags.add(Tag.DOMINANT);
                }
                if (rng.chance(FIRST_GEN_SEX_TAG_RATE)) {
                    antiTags.add(Tag.MALE_EXPRESSED);
                }
                if (rng.chance(FIRST_GEN_SEX_TAG_RATE)) {
                    antiTags.add(Tag.FEMALE_EXPRESSED);
                }
                ind.addTrait(TraitInstance.antiCard(target, antiTags));
            }
        }
        int pcN = ParentingClass.values().length;
        ind.setParentingCareMale(ParentingClass.byOrdinal(rng.nextInt(pcN)));
        ind.setParentingCareFemale(ParentingClass.byOrdinal(rng.nextInt(pcN)));
        return ind;
    }

    /**
     * 자식 1명 생성 (설계서 §2 유전 알고리즘). stats는 검증용, 게임 실행 시 null 허용.
     */
    public static Individual breed(long childId, Individual a, Individual b,
                                   DeterministicRng rng, int generation, BreedStats stats) {
        Sex sex = rng.nextBoolean() ? Sex.MALE : Sex.FEMALE;
        return breed(childId, a, b, rng, generation, stats, sex);
    }

    /**
     * 성별을 지정해 자식 생성 — 성비 보정(설계서 §2, 헤드리스 시뮬 교대 배정)에서 사용.
     * 형질 유전은 성별과 독립(성별은 발현·흔적 보상에만 영향).
     */
    public static Individual breed(long childId, Individual a, Individual b,
                                   DeterministicRng rng, int generation, BreedStats stats, Sex sex) {
        Individual child = new Individual(childId, sex, a.id(), b.id(), generation);
        for (Category cat : Category.values()) {
            for (TraitInstance ti : selectCategory(cat, a, b, sex, rng, stats)) {
                child.addTrait(ti);
            }
        }
        // 육아 클래스 유전: 남·여발현 슬롯을 각각 독립 유전(→ 한쪽 전부 or 섞이기 자연 발생) + 각 10% 돌연변이.
        int pcN = ParentingClass.values().length;
        ParentingClass cm = rng.nextBoolean() ? a.parentingCareMale() : b.parentingCareMale();
        if (rng.chance(PARENTING_MUTATION_RATE)) {
            cm = ParentingClass.byOrdinal(rng.nextInt(pcN));
        }
        ParentingClass cf = rng.nextBoolean() ? a.parentingCareFemale() : b.parentingCareFemale();
        if (rng.chance(PARENTING_MUTATION_RATE)) {
            cf = ParentingClass.byOrdinal(rng.nextInt(pcN));
        }
        child.setParentingCareMale(cm);
        child.setParentingCareFemale(cf);
        return child;
    }

    /**
     * 한 카테고리의 자식 특성 구성 (설계서 §2 유전 알고리즘):
     * <ol>
     *   <li>우성 우선 + 반발 회피로 <b>일반 특성 최대 3개</b>.</li>
     *   <li>돌연변이(일반 특성 대상).</li>
     *   <li><b>흔적 보상</b>: 뽑힌 게 성별발현으로 흔적이 되면 같은 축의 발현값을 부모에서 1회 더 봄
     *       → 흔적+발현 공존. 반발 카드는 보상 대상 아님.</li>
     *   <li><b>반발 카드</b> 유전(최대 2개). 무력화는 발현 판정에서 처리되므로 일반 특성과 공존.</li>
     * </ol>
     */
    private static List<TraitInstance> selectCategory(Category cat, Individual a, Individual b,
                                                      Sex childSex, DeterministicRng rng, BreedStats stats) {
        List<TraitInstance> normalCands = new ArrayList<>();
        List<TraitInstance> antiCands = new ArrayList<>();
        for (TraitInstance ti : a.traitsIn(cat)) {
            (ti.isAnti() ? antiCands : normalCands).add(ti);
        }
        for (TraitInstance ti : b.traitsIn(cat)) {
            (ti.isAnti() ? antiCands : normalCands).add(ti);
        }

        // 1) 일반 특성 최대 3개 — 우성 우선 선택(전달 우위) + 반발/중복 회피.
        //    우성 태그 자체는 inherit()에서 75%만 유전(25%는 열성화) → 도배 상한이 유지율(~75%)에 걸림.
        //    선택압이 붙으면 나쁜데 우성인 특성이 도태돼 이 비율이 더 내려간다(설계서 §2).
        List<TraitInstance> chosen = new ArrayList<>();
        for (TraitInstance cand : dominantFirst(normalCands, rng)) {
            if (chosen.size() >= MAX_PER_CATEGORY) {
                break;
            }
            if (containsTrait(chosen, cand.trait()) || conflicts(chosen, cand.trait())) {
                continue;
            }
            chosen.add(inherit(cand, rng, stats));
        }

        // 2) 돌연변이 (일반 특성 대상).
        mutate(cat, chosen, rng, stats);

        // 3) 흔적 보상 — 뽑힌 일반 특성이 자식 성별에서 흔적이면 같은 축의 발현값을 1회 더.
        List<TraitInstance> rewards = new ArrayList<>();
        for (TraitInstance ti : new ArrayList<>(chosen)) {
            if (ti.expressedFor(childSex)) {
                continue; // 발현되면 손해 아님 → 보상 없음
            }
            TraitInstance sibling = findExpressingSibling(
                    ti.trait(), childSex, normalCands, chosen, rewards, rng, stats);
            if (sibling != null) {
                rewards.add(sibling);
                if (stats != null) {
                    stats.vestigialRewards++;
                }
            }
        }
        chosen.addAll(rewards);

        // 4) 반발 카드 유전 (최대 2개). 대상 중복 방지, 일반 특성과 반발 검사 없이 공존.
        int antiCount = 0;
        for (TraitInstance cand : dominantFirst(antiCands, rng)) {
            if (antiCount >= MAX_ANTI_PER_CATEGORY) {
                break;
            }
            if (containsAntiTarget(chosen, cand.trait())) {
                continue;
            }
            chosen.add(inherit(cand, rng, stats));
            antiCount++;
        }
        return chosen;
    }

    /** 우성 후보를 앞에 두고 각 그룹을 셔플한 후보 순서(우선 선택용). */
    private static List<TraitInstance> dominantFirst(List<TraitInstance> cands, DeterministicRng rng) {
        List<TraitInstance> dominant = new ArrayList<>();
        List<TraitInstance> other = new ArrayList<>();
        for (TraitInstance ti : cands) {
            (ti.isDominant() ? dominant : other).add(ti);
        }
        rng.shuffle(dominant);
        rng.shuffle(other);
        List<TraitInstance> ordered = new ArrayList<>(dominant);
        ordered.addAll(other);
        return ordered;
    }

    /**
     * 부모 후보 하나를 자식에게 복제 — 성별발현 태그는 흔적 포함 100% 유전, 우성 후보는 75%로 우성 유지
     * (설계서 §2). 반발 카드 여부도 보존.
     */
    private static TraitInstance inherit(TraitInstance cand, DeterministicRng rng, BreedStats stats) {
        EnumSet<Tag> tags = cand.sexTags();
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
        return cand.isAnti()
                ? TraitInstance.antiCard(cand.trait(), tags)
                : new TraitInstance(cand.trait(), tags);
    }

    /**
     * 흔적이 된 특성의 같은 축에서, 자식 성별에 발현되는 다른 값을 부모 후보에서 찾아 상속 복제(설계서 §2).
     * 없으면 null.
     */
    private static TraitInstance findExpressingSibling(Trait vestigial, Sex childSex,
                                                       List<TraitInstance> normalCands,
                                                       List<TraitInstance> chosen,
                                                       List<TraitInstance> rewards,
                                                       DeterministicRng rng, BreedStats stats) {
        Axis axis = vestigial.axis();
        List<TraitInstance> options = new ArrayList<>();
        for (TraitInstance cand : normalCands) {
            if (cand.trait().axis() != axis || cand.trait() == vestigial) {
                continue; // 같은 축의 다른 값이어야
            }
            if (!cand.expressedFor(childSex)) {
                continue; // 이 성별에 발현되는 값만
            }
            if (containsTrait(chosen, cand.trait()) || containsTrait(rewards, cand.trait())) {
                continue;
            }
            options.add(cand);
        }
        TraitInstance picked = rng.pick(options);
        return picked == null ? null : inherit(picked, rng, stats);
    }

    private static boolean containsAntiTarget(List<TraitInstance> list, Trait target) {
        for (TraitInstance ti : list) {
            if (ti.isAnti() && ti.trait() == target) {
                return true;
            }
        }
        return false;
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
