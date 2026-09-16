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

    /**
     * 감독관을 앉히려면 그 구획에 마름 말고도 이만큼의 상시 소작이 있어야 한다(후보 본인 포함).
     * 감독관은 일꾼을 감독하는 자리라 감독할 소작이 없으면 자리 자체가 없다. 종전엔 72칸만 넘으면
     * 소작 둘뿐인 밭에서도 하나를 감독관으로 빼내(마름까지 빼면 일꾼 0), 익은 밭이 매일 그대로 남았다
     * (사용자 실측: 대학도 없는 마을에서 소작 없이 감독관만 늘어 익은 채 방치).
     */
    public static final int MIN_TENANTS = 3;
    /** 감독관을 유지하려면 남은 상시 소작이 이만큼은 돼야 한다. 밑돌면 해임되어 다시 일꾼으로 돌아간다. */
    public static final int KEEP_TENANTS = 2;

    /** 임명 가능 — 구획 크기와 상시 소작 수(마름·감독관 제외, 후보 포함) 둘 다 문턱을 넘어야 한다. */
    public static boolean canAppoint(int tiles, int permanentTenants) {
        return needed(tiles) && permanentTenants >= MIN_TENANTS;
    }

    /** 유지 가능 — 구획이 여전히 크고 감독할 상시 소작이 남아 있어야 한다. */
    public static boolean canKeep(int tiles, int permanentTenants) {
        return needed(tiles) && permanentTenants >= KEEP_TENANTS;
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
