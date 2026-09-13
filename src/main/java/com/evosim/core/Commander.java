package com.evosim.core;

/**
 * <b>지휘관</b>(지식인 체계 P3, 계획서 1.6) — 순수 함수만.
 *
 * <p>막사 정원이 다 찬 밤에 막사 주인이 병사 중 한 명을 지휘관으로 세운다. 자격은 학위 1 이상
 * (없으면 임명하지 않는다 — 대학이 있어야 지휘관이 선다), 순위는 학위 × 능력(힘·경계·지능).
 * 급여 5(병사 최고 봉급과 같다). 효과는 그 막사 병사 전원의 공격·감지 +5%/+15%(학사/석사)와
 * 봉급 미납 이탈 유예 +1/+2일. 낮에는 막사에서 훈련, 밤은 막사 취침.
 */
public final class Commander {

    private Commander() {
    }

    /** 지휘관 하루 급여 — 병사 최고 봉급(5.0)과 같다. */
    public static final double WAGE = 5.0;

    /** 자격 — 학위 1 이상. */
    public static boolean eligible(int degree) {
        return Degree.clamp(degree) >= Degree.BACHELOR;
    }

    /** 막사가 정원까지 찼는가(정원 0 은 미충족). */
    public static boolean fullHouse(int seated, int cap) {
        return cap > 0 && seated >= cap;
    }

    /**
     * 순위 점수 — 학위 × (힘 + 경계 + 지능 등급). 학위가 0 이면 0(자격 없음). 능력 항이 전부
     * 중립(1+1+0)이어도 학위만으로 양수라 후보가 비지 않는다.
     */
    public static double score(int degree, double strength, double vision, int brightGrade) {
        if (!eligible(degree)) {
            return 0.0;
        }
        return Degree.clamp(degree) * (Math.max(0.0, strength) + Math.max(0.0, vision) + Math.max(0, brightGrade));
    }

    /** 병사 공격·감지 배수 — 1 / 1.05 / 1.15. */
    public static double bonusMult(int degree) {
        return 1.0 + Degree.commanderBonus(degree);
    }

    /** 봉급 미납 이탈 유예 일수 — 0 / 1 / 2. */
    public static int graceDays(int degree) {
        return Degree.desertGraceDays(degree);
    }
}
