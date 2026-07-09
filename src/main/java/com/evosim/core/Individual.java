package com.evosim.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 개체 (설계서 §1, §2). Phase 0 데이터 구조.
 *
 * <p>순수 데이터 — 마크(렌더링/좌표/AI)에 얽히지 않는다(설계서 §18). homePos는 정수 3좌표
 * 포인터(§3)로, Phase 0에서는 미사용이라 null.
 */
public final class Individual {
    private final long id;
    private final Sex sex;
    private final long parentAId; // 부모 링크만 저장 → 가계도는 계산(설계서 §14). 1세대는 0.
    private final long parentBId;
    private final int generation; // 인위 생성 첫 세대=1, 자식=2 … (설계서 §2 세대 카운트)

    // 카테고리별 발현 특성 목록(최대 3개씩). 흔적/반발 확장은 이후 페이즈.
    private final Map<Category, List<TraitInstance>> traits;

    // 생존 상태(이후 페이즈에서 사용). Phase 0에선 구조만 마련.
    private int hungerCount = 0;
    private int[] homePos = null; // {x,y,z} 포인터 또는 null

    // 육아 적극성 — 개체마다 정확히 하나(설계서 육아 클래스). breed로 유전.
    private ParentingClass parentingCare = ParentingClass.MODERATE;

    public Individual(long id, Sex sex, long parentAId, long parentBId, int generation) {
        this.id = id;
        this.sex = sex;
        this.parentAId = parentAId;
        this.parentBId = parentBId;
        this.generation = generation;
        this.traits = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            this.traits.put(c, new ArrayList<>());
        }
    }

    public long id() {
        return id;
    }

    public Sex sex() {
        return sex;
    }

    public long parentAId() {
        return parentAId;
    }

    public long parentBId() {
        return parentBId;
    }

    public int generation() {
        return generation;
    }

    public List<TraitInstance> traitsIn(Category category) {
        return traits.get(category);
    }

    /** 모든 카테고리의 특성을 평평하게. */
    public List<TraitInstance> allTraits() {
        List<TraitInstance> out = new ArrayList<>();
        for (Category c : Category.values()) {
            out.addAll(traits.get(c));
        }
        return out;
    }

    public void addTrait(TraitInstance ti) {
        traits.get(ti.category()).add(ti);
    }

    public int hungerCount() {
        return hungerCount;
    }

    public void setHungerCount(int hungerCount) {
        this.hungerCount = hungerCount;
    }

    public int[] homePos() {
        return homePos;
    }

    public void setHomePos(int[] homePos) {
        this.homePos = homePos;
    }

    public ParentingClass parentingCare() {
        return parentingCare;
    }

    public void setParentingCare(ParentingClass parentingCare) {
        this.parentingCare = parentingCare;
    }
}
