package com.evosim.core;

/**
 * <b>대학</b>(지식인 체계 P2, 계획서 1.3) — 순수 함수만.
 *
 * <p>"등록금이 대학을, 대학은 일자리를": 가구가 등록금을 내고, 그 돈으로 교수 급여와 기숙 급식이
 * 돌며, 학위가 마름·감독관·지휘관·교사·목사·의사 자리에 우대를 준다. 적자는 군주가 메우고
 * 흑자는 교수와 군주가 반분한다.
 */
public final class University {

    private University() {
    }

    /** 등록금 하루치. */
    public static final double TUITION = 1.5;
    /** 기숙비 하루치(급식 원가). */
    public static final double LODGING_FEE = 1.5;
    /** 학사 과정 일수·석사 과정 일수(출석한 날만 센다). */
    public static final int BACHELOR_DAYS = 2;
    public static final int MASTER_DAYS = 2;
    /** 통학 한계 — 이보다 멀면 기숙. */
    public static final double COMMUTE = 64.0;
    /** 기숙 한계 — 이보다 멀면 입학 불가. */
    /** 기숙 입학 상한(블록). 길찾기 사거리(FOLLOW_RANGE 160)보다 짧아야 한다 — 실측(런 34 무대 10): 165블록 집의 학생이
     *  기숙사로 가는 경로를 못 만들어(부분경로가 제자리에서 끝남) 4800틱 제자리에 서 있었다. */
    public static final double LODGE_RANGE = 150.0;
    /** 임시 교수(석사 없을 때, 상급 학력) 급여. 석사 교수는 Degree.professorWage(3.5). */
    public static final double TEMP_PROFESSOR_WAGE = 2.5;
    /** 설립 자격 — 군주급 추종 가구·상급 학력 성년·초등학교. */
    public static final int MIN_FOLLOWERS = 20;
    public static final int MIN_SCHOLARS = 3;
    public static final int MIN_SCHOOLS = 1;
    /** 미납 이틀 연속이면 중퇴. */
    public static final int DROPOUT_UNPAID_DAYS = 2;
    /** 같은 갈래 간격(마을에 하나). */
    public static final double MIN_GAP = 96.0;
    /**
     * 부지 탐색 고리의 바깥 한계(블록). 학교 등은 64 다.
     * 대학 도면(41×43)은 집 간격(15~30)보다 훨씬 넓어 마을 안에는 못 들어가고 가장자리 밖에 선다 —
     * 실측(런 32 사본, 집 26채·밭 569칸): 회전에 따라 첫 자리가 중심에서 64~80 에서 나왔다.
     * 통학 64 를 넘는 학생은 기숙(192 안)으로 받으므로 멀어도 운영에는 지장이 없다.
     */
    public static final int SITE_RADIUS = 128;

    /**
     * <b>연구실 하나가 받는 석사 수</b> — 교수 밑 조교 둘. 도면의 연구실 3석이면 동시 6명이다.
     */
    public static final int MASTERS_PER_LAB = 2;
    /**
     * 석사 진학의 <b>능력 문턱</b> — 명석이 발현했거나 관리등급이 이 값 이상. 자리가 아니라 자격이므로
     * 못 넘은 자는 연구실이 비어 있어도 학사에서 멈추고, 넘은 자는 자리가 있는 한 모두 들어간다.
     */
    public static final int MASTER_ABILITY_GRADE = 2;

    /** 학생 정원 — 교수 1명당 11석(사용자 지시), 강의실 좌석이 상한. */
    public static int studentCap(int professors, int seats, int perProfessor) {
        return Math.max(0, Math.min(seats, professors * perProfessor));
    }

    /** 교수 급여 — 석사 3.5, 그 외(임시 교수) 2.5. */
    public static double professorWage(int degree) {
        return Degree.clamp(degree) >= Degree.MASTER ? Degree.professorWage(degree) : TEMP_PROFESSOR_WAGE;
    }

    /**
     * 교수 후보 순위 점수 — 석사가 먼저(+100), 다음 관리등급 × (1 + 명석 등급). 학사·상급 학력은
     * 임시 교수 자격. 무학위·상급 미만은 0(자격 없음).
     */
    public static double professorScore(int degree, int schoolLevel, int manageGrade, int brightGrade) {
        boolean master = Degree.clamp(degree) >= Degree.MASTER;
        if (!master && schoolLevel < Schooling.MAX_LEVEL) {
            return 0.0;
        }
        return (master ? 100.0 : 0.0) + Math.max(0, manageGrade) * (1.0 + Math.max(0, brightGrade)) + 1.0;
    }

    /** 입학 자격 — 상급 학력, 학위 미완(석사 미만), 거처 곳간 ≥ 등록금 2일치 + 하루 소모 2일치. */
    public static boolean canEnroll(int schoolLevel, int degree, double larder, double dailyNeed) {
        return schoolLevel >= Schooling.MAX_LEVEL && Degree.clamp(degree) < Degree.MASTER
                && larder >= TUITION * 2.0 + Math.max(0.0, dailyNeed) * 2.0;
    }

    /** 통학 가능(64 이내). */
    public static boolean commutes(double distance) {
        return distance <= COMMUTE;
    }

    /** 기숙 대상(64 초과 192 이내). */
    public static boolean needsLodging(double distance) {
        return distance > COMMUTE && distance <= LODGE_RANGE;
    }

    /** 하루 납부액 — 등록금(+기숙비). */
    public static double dailyFee(boolean lodging) {
        return TUITION + (lodging ? LODGING_FEE : 0.0);
    }

    /** 중퇴 판정 — 미납 연속 일수. */
    public static boolean dropout(int unpaidDays) {
        return unpaidDays >= DROPOUT_UNPAID_DAYS;
    }

    /** 과정 일수 — 학사 2, 석사 2. */
    public static int courseDays(int target) {
        return target >= Degree.MASTER ? MASTER_DAYS : BACHELOR_DAYS;
    }

    /** 졸업 판정 — 출석 적립이 과정 일수에 닿았는가. */
    public static boolean graduated(double credit, int target) {
        return credit + 1.0E-9 >= courseDays(target);
    }

    /**
     * 밤 정산 — [0] 군주 보전(적자), [1] 교수 몫(흑자 절반), [2] 군주 몫(흑자 절반).
     * 수입 = 등록금 + 기숙비, 지출 = 교수 급여 + 급식.
     */
    public static double[] settle(double income, double wages, double meals) {
        double net = Math.max(0.0, income) - Math.max(0.0, wages) - Math.max(0.0, meals);
        if (net < 0.0) {
            return new double[] {-net, 0.0, 0.0};
        }
        return new double[] {0.0, net * 0.5, net * 0.5};
    }

    /** 설립 자격 — 추종 20 가구 이상 · 상급 학력 성년 3 이상 · 초등학교 1 이상 · 곳간 ≥ 착공비 + 예비. */
    public static boolean canFound(int followers, int scholars, int schools, double larder, double cost, double reserve) {
        return followers >= MIN_FOLLOWERS && scholars >= MIN_SCHOLARS && schools >= MIN_SCHOOLS
                && larder >= cost + Math.max(0.0, reserve);
    }
}
