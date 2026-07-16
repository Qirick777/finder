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

    // 육아 적극성 — 남성발현/여성발현 세트(예: 무심[남]·적극[여]). 성별로 발동. breed로 슬롯별 유전.
    private ParentingClass parentingCareMale = ParentingClass.MODERATE;
    private ParentingClass parentingCareFemale = ParentingClass.MODERATE;

    // 짝 선정 까다로움 — 남/여 슬롯 세트(육아와 동일 유전 방식). 성별로 발동. 중립=보통.
    private MateChoiceClass mateChoiceMale = MateChoiceClass.NEUTRAL;
    private MateChoiceClass mateChoiceFemale = MateChoiceClass.NEUTRAL;

    // 직계 조상 ID 명단(부모→조부모→… BFS 순, 상한 Kinship.ANCESTOR_CAP) — 근친 회피의 직계
    // 차단 근거(조부모-손주 등). breed 가 양가 명단을 병합해 채운다. 1세대·구 세이브는 빈 배열.
    private long[] ancestorIds = new long[0];

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

    /** 특성 제거(편집기) — 동일 인스턴스(정체성 비교)만 제거. 성공 여부 반환. */
    public boolean removeTrait(TraitInstance ti) {
        return traits.get(ti.category()).remove(ti);
    }

    /**
     * 특성 교체(편집기) — 우성 토글·등급 변경은 불변 {@link TraitInstance} 재생성으로 처리한다.
     * 같은 특성(=같은 카테고리)끼리만 허용. 성공 여부 반환.
     */
    public boolean replaceTrait(TraitInstance oldTi, TraitInstance newTi) {
        if (newTi.category() != oldTi.category()) {
            return false;
        }
        List<TraitInstance> list = traits.get(oldTi.category());
        int i = list.indexOf(oldTi);
        if (i < 0) {
            return false;
        }
        list.set(i, newTi);
        return true;
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

    public ParentingClass parentingCareMale() {
        return parentingCareMale;
    }

    public ParentingClass parentingCareFemale() {
        return parentingCareFemale;
    }

    public void setParentingCareMale(ParentingClass c) {
        this.parentingCareMale = c;
    }

    public void setParentingCareFemale(ParentingClass c) {
        this.parentingCareFemale = c;
    }

    /** 이 개체 성별에서 실제 발동하는 육아 클래스 (남녀발현 세트 중 성별에 맞는 쪽). */
    public ParentingClass parentingCare() {
        return sex == Sex.MALE ? parentingCareMale : parentingCareFemale;
    }

    public MateChoiceClass mateChoiceMale() {
        return mateChoiceMale;
    }

    public MateChoiceClass mateChoiceFemale() {
        return mateChoiceFemale;
    }

    public void setMateChoiceMale(MateChoiceClass c) {
        this.mateChoiceMale = c;
    }

    public void setMateChoiceFemale(MateChoiceClass c) {
        this.mateChoiceFemale = c;
    }

    /** 이 개체 성별에서 실제 발동하는 짝 선정 까다로움 (성별에 맞는 슬롯). */
    public MateChoiceClass mateChoice() {
        return sex == Sex.MALE ? mateChoiceMale : mateChoiceFemale;
    }

    /** 직계 조상 ID 명단(부모→조부모→… BFS 순). 비어 있으면 1세대 또는 구 세이브. */
    public long[] ancestorIds() {
        return ancestorIds;
    }

    public void setAncestorIds(long[] ids) {
        this.ancestorIds = ids != null ? ids : new long[0];
    }
}
