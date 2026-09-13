package com.evosim.core;

/**
 * <b>감독관</b>(지식인 체계 P3, 계획서 1.6) — 순수 함수만.
 *
 * <p>단일 구획이 {@link #MIN_TILES}(72)칸 이상이면 마름 위로 감독관 한 명이 붙는다. 마름 하나가
 * 볼 수 있는 규모(관리 용량 8+g³, Ⅴ등급 133)를 넘어서는 큰 밭에 <b>관리 층을 하나 더</b> 두는 것
 * — "조직을 키우면 무한히 커진다"의 다음 단. 급여는 그 구획 지대의 5%, 효과는 그 구획 관리 효율
 * 바닥을 감독관 등급(학위 가산)으로. 72칸 미만은 마름 하나로 충분하다.
 */
public final class Overseer {

    private Overseer() {
    }

    /** 감독관이 붙는 구획 크기 하한 — 중견지주 봉토(36)의 두 배, 마름 Ⅴ 용량(133)의 절반쯤. */
    public static final int MIN_TILES = 72;
    /** 급여 — 그 구획 오늘 지대(주인 몫 정수 유닛)의 5%. */
    public static final double WAGE_SHARE = 0.05;

    /** 이 크기의 구획에 감독관이 필요한가. */
    public static boolean needed(int tiles) {
        return tiles >= MIN_TILES;
    }

    /** 후보 순위 점수 — 학위 우선(10점씩), 다음 관리등급. 같으면 호출부가 수율·근속·id 로 가른다. */
    public static int score(int degree, int manageGrade) {
        return Degree.clamp(degree) * 10 + Math.max(0, manageGrade);
    }

    /**
     * 오늘 급여 — [0] 지급 정수 유닛, [1] 다음 날로 넘길 소수. 지대 정수 유닛 × 5% + 이월.
     * 정수 유닛만 움직이는 곳간 규칙(마름 수당과 같다).
     */
    public static double[] wage(int rentUnits, double carry) {
        double due = Math.max(0, rentUnits) * WAGE_SHARE + Math.max(0.0, carry);
        int units = (int) Math.floor(due);
        return new double[] {units, due - units};
    }

    /** 감독관이 있는 구획의 관리 효율 바닥 — 감독관 자신의 관리 효율 × (1 + 학위 가산), 상한 1. */
    public static double floor(double overseerEfficiency, int degree) {
        return Math.min(1.0, Math.max(0.0, overseerEfficiency) * (1.0 + Degree.efficiencyBonus(degree)));
    }
}
