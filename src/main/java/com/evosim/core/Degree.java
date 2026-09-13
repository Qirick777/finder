package com.evosim.core;

/**
 * <b>학위 효과</b>(지식인 체계, 계획서 1.5 — 볼록: 학사 작고 석사 크다) — 순수 함수만.
 *
 * <p>학위(0 없음 · 1 학사 · 2 석사)는 대학이 올린다(도면 뒤). 값은 먼저 파 두고 자리마다 조건만
 * 건다 — 마름 선발·관리 효율·수당, 지휘관 보너스, 교사·교수·의사 급여, 혼인 매력. 성인이 대학에
 * 가며 잃는 노동 기회를 되갚아야 하므로 석사에서 가파르다.
 */
public final class Degree {

    private Degree() {
    }

    public static final int NONE = 0;
    public static final int BACHELOR = 1;
    public static final int MASTER = 2;

    public static int clamp(int degree) {
        return Math.max(NONE, Math.min(MASTER, degree));
    }

    public static String name(int degree) {
        return switch (clamp(degree)) {
            case BACHELOR -> "학사";
            case MASTER -> "석사";
            default -> "없음";
        };
    }

    /** 마름 선발 가중 — ×1 / ×1.15 / ×1.6. */
    public static double stewardWeight(int degree) {
        return switch (clamp(degree)) {
            case BACHELOR -> 1.15;
            case MASTER -> 1.6;
            default -> 1.0;
        };
    }

    /** 마름·감독관 관리 효율 가산 — +0 / +5% / +15% (상한 1.0 은 호출부). */
    public static double efficiencyBonus(int degree) {
        return switch (clamp(degree)) {
            case BACHELOR -> 0.05;
            case MASTER -> 0.15;
            default -> 0.0;
        };
    }

    /** 마름 수당 계수 가산 — +0 / +0.05 / +0.15. */
    public static double wageBonus(int degree) {
        return efficiencyBonus(degree);
    }

    /** 지휘관 병사 보너스(공격·감지) — +0 / +5% / +15%. */
    public static double commanderBonus(int degree) {
        return efficiencyBonus(degree);
    }

    /** 지휘관 아래 병사의 봉급 미납 이탈 유예 — +0 / +1일 / +2일. */
    public static int desertGraceDays(int degree) {
        return clamp(degree);
    }

    /** 교사 급여 — 임시교사 1.0 / 학사 2.0 / 석사 3.0. */
    public static double teacherWage(int degree) {
        return switch (clamp(degree)) {
            case BACHELOR -> 2.0;
            case MASTER -> 3.0;
            default -> 1.0;
        };
    }

    /** 교수 급여 — 석사만 3.5, 그 외 0(자격 없음). */
    public static double professorWage(int degree) {
        return clamp(degree) == MASTER ? 3.5 : 0.0;
    }

    /** 의사 급여 — 학사 2.5 / 석사 4.0, 무학위 0(자격 없음). */
    public static double doctorWage(int degree) {
        return switch (clamp(degree)) {
            case BACHELOR -> 2.5;
            case MASTER -> 4.0;
            default -> 0.0;
        };
    }

    /** 의사의 유아 회복률 — 학사 60% / 석사 80%, 무학위 0. */
    public static double doctorRecovery(int degree) {
        return switch (clamp(degree)) {
            case BACHELOR -> 0.6;
            case MASTER -> 0.8;
            default -> 0.0;
        };
    }

    /** 혼인 매력 가산 — +0 / +1 / +3. */
    public static int marriageCharm(int degree) {
        return switch (clamp(degree)) {
            case BACHELOR -> 1;
            case MASTER -> 3;
            default -> 0;
        };
    }
}
