package com.evosim.core;

/**
 * 짝 성사 시 새 가정의 거처를 <b>어떻게</b> 마련할지 결정하는 순수 함수 (설계서 §13-D 이주/정착 확장).
 *
 * <p>정착 축 특성(이주자 {@link Trait#MIGRATORY} / 애향심 {@link Trait#HOMEBOUND} / 기본 중립)의 조합으로
 * ① 빈 거처(꺼진 모닥불) 재사용 확률, ② 새로 지을 때의 거리, ③ 지을 위치의 기준점(앵커)을 정한다.
 *
 * <ul>
 *   <li><b>이주자</b>는 반드시 새로 짓는다(재사용 0%). 이주자+기본=멀리, 이주자+이주자=더 멀리.</li>
 *   <li><b>이주자+애향심</b>: 기본처럼 가까이 이주하되, 지을지(신축)·들어갈지(재사용)는 랜덤(50%).</li>
 *   <li><b>기본+애향심</b>: 75%로 빈 거처 사용, 없으면 <b>애향심 보유자가 태어난 위치</b> 근처에 신축.</li>
 *   <li><b>애향심+애향심</b>: 100%로 빈 거처 사용, 없으면 <b>둘의 거처 중간</b>에 신축.</li>
 *   <li><b>기본+기본</b>: 중도(50%), 없으면 짝이 성사된 자리에서 기본 거리.</li>
 * </ul>
 *
 * <p>겹치지 않게 배치·거리 확장은 {@link Settlement#placeHome}이 담당한다. 이 클래스는 앵커·거리·재사용
 * 확률만 순수하게 결정하므로 {@code /evotest}로 조합표 전체를 헤드리스 검증할 수 있다.
 */
public final class HomeResolution {

    /** 정착 성향 — 개체의 정착 축 특성 발현 결과. */
    public enum Disposition {
        MIGRANT,  // 이주자: 항상 새로, 멀리
        HOMER,    // 애향심: 있던 곳으로, 가까이
        NEUTRAL   // 기본: 중립
    }

    /** 신축 시 위치 기준점. */
    public enum Anchor {
        MATING_SPOT,    // 짝이 성사된 자리(기본 독립)
        HOMER_BIRTH,    // 애향심 보유자가 태어난 위치
        MIDPOINT_HOMES  // 두 거처의 중간
    }

    /**
     * 거처 마련 계획.
     *
     * @param emptyPercent 빈 거처를 먼저 시도할 확률(0~100). 0이면 무조건 신축(이주자).
     * @param distance     신축 시 앵커에서 떨어질 거리(블록).
     * @param anchor       신축 위치 기준점.
     */
    public record Plan(int emptyPercent, int distance, Anchor anchor) {
    }

    // 거리 등급(기본 거리 배수) — 더 멀리 > 멀리 > 기본 > 가까이.
    private static final int FARTHER = Settlement.BASE_DISTANCE * 3; // 이주자+이주자
    private static final int FAR = Settlement.BASE_DISTANCE * 2;     // 이주자+기본
    private static final int DEFAULT = Settlement.BASE_DISTANCE;     // 기본 독립
    private static final int CLOSE = Settlement.BASE_DISTANCE / 2;   // 애향 밀집

    private HomeResolution() {
    }

    /** 개체의 정착 축 특성으로 성향 판정. */
    public static Disposition dispositionOf(Individual ind) {
        if (ExpressionResolver.isExpressed(ind, Trait.MIGRATORY)) {
            return Disposition.MIGRANT;
        }
        if (ExpressionResolver.isExpressed(ind, Trait.HOMEBOUND)) {
            return Disposition.HOMER;
        }
        return Disposition.NEUTRAL;
    }

    /** 두 성향 조합의 거처 마련 계획(대칭 — 순서 무관). */
    public static Plan plan(Disposition a, Disposition b) {
        int migrants = (a == Disposition.MIGRANT ? 1 : 0) + (b == Disposition.MIGRANT ? 1 : 0);
        int homers = (a == Disposition.HOMER ? 1 : 0) + (b == Disposition.HOMER ? 1 : 0);

        if (migrants == 2) {
            return new Plan(0, FARTHER, Anchor.MATING_SPOT);        // 이주자+이주자: 더 멀리
        }
        if (migrants == 1 && homers == 1) {
            return new Plan(50, DEFAULT, Anchor.MATING_SPOT);        // 이주자+애향심: 가까이·랜덤
        }
        if (migrants == 1) {
            return new Plan(0, FAR, Anchor.MATING_SPOT);             // 이주자+기본: 멀리
        }
        if (homers == 2) {
            return new Plan(100, CLOSE, Anchor.MIDPOINT_HOMES);      // 애향심+애향심: 100% 재사용·중간
        }
        if (homers == 1) {
            return new Plan(75, CLOSE, Anchor.HOMER_BIRTH);          // 기본+애향심: 75% 재사용·고향
        }
        return new Plan(50, DEFAULT, Anchor.MATING_SPOT);            // 기본+기본: 중도
    }

    /** 두 개체 조합의 계획을 바로 계산(편의). */
    public static Plan plan(Individual a, Individual b) {
        return plan(dispositionOf(a), dispositionOf(b));
    }
}
