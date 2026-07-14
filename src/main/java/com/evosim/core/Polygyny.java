package com.evosim.core;

import java.util.List;
import java.util.Set;

/**
 * 일부다처(一夫多妻) 게이트 (혼인 확장). 남성은 그대로 두고(능동 구애 없음), <b>여성이 기혼 남성도
 * 잠재 짝으로 고려</b>한다 — 단 세 겹의 필터로 대다수는 일부일처, 드물게만 다처가 드러난다:
 *
 * <ol>
 *   <li>여성 측: 기혼 후보는 매력 <b>−{@link #MARRIED_CHARM_PENALTY}</b> 감점 — 평시엔 독신남이
 *       순위에서 이긴다(성비 붕괴·특출난 기혼남일 때만 구애가 향함).</li>
 *   <li>아내 질투 게이트: 현재 아내 중 한 명이라도 <b>인색·경쟁</b> 보유 시 거절(용납 안 함) —
 *       단 <b>욕심·야망가</b> 남편은 이 게이트를 누른다({@link #eliteSuitor}).</li>
 *   <li>부양 증명: 저장고 ≥ 가족 하루소모 × {@link #SUPPORT_DAYS}일 — 부유한 가장만.
 *       처 수 명목 상한은 없고 이 게이트가 유일한 상한(아내·자식이 늘수록 소모가 커진다).</li>
 * </ol>
 *
 * <p>게이트 전부 통과 시 남성의 기본값은 <b>수락</b>(확률 판정 없음). 순수 함수 — evotest로 전 조합 검증.
 */
public final class Polygyny {

    /** 부양 증명 — 저장고가 가족 하루소모의 이 일수 이상이어야 추가 부인 수락.
     *  처 수의 명목 상한은 없음(사용자 확정) — 아내가 늘수록 가족소모가 커져 부가 곧 상한. */
    public static final double SUPPORT_DAYS = 3.0;
    /** 여성이 기혼 후보를 등록할 때의 매력 감점(독신 우선 — 일부일처가 기본이 되게).
     *  부유 기혼남은 부유선호 가점(잉여→매력)이 이를 자연 상쇄한다 — 인위적 감점 해제 없음. */
    public static final int MARRIED_CHARM_PENALTY = 2;

    private Polygyny() {
    }

    /** 이 아내가 추가 부인을 용납하지 않는가 — 인색·경쟁 보유 시 질투 게이트 발동. */
    public static boolean wifeObjects(Individual wife) {
        Set<Trait> t = ExpressionResolver.expressedTraits(wife);
        return t.contains(Trait.STINGY) || t.contains(Trait.COMPETITIVE);
    }

    /** 부유층 구혼 대상 — 욕심·야망가 발현 남편은 아내의 질투를 누른다(부가 질투를 이긴다). */
    public static boolean eliteSuitor(Individual husband) {
        Set<Trait> t = ExpressionResolver.expressedTraits(husband);
        return t.contains(Trait.GREEDY) || t.contains(Trait.AMBITIOUS);
    }

    /**
     * 기혼 남성의 추가 부인 수락 판정 — 질투(부유층 면제)·부양 게이트 통과 시에만 참(그 경우
     * 기본 수락 — 확률 없음: 둘째 이상은 게이트가 곧 100% 수락). 상한 게이트는 없다.
     *
     * @param wives           현재 아내들
     * @param larder          거처 저장고
     * @param familyDailyNeed 가족 하루 소모
     * @param husband         구혼받는 남편(욕심·야망가면 질투 무시)
     */
    public static boolean canAccept(List<Individual> wives, double larder, double familyDailyNeed,
                                    Individual husband) {
        if (!eliteSuitor(husband)) {
            for (Individual w : wives) {
                if (wifeObjects(w)) {
                    return false;
                }
            }
        }
        return larder >= familyDailyNeed * SUPPORT_DAYS;
    }
}
