package com.evosim.core;

/**
 * <b>병원</b>(지식인 체계 P6, 계획서 1.8) — 순수 함수만.
 *
 * <p>유아 병듦을 "즉사"에서 "앓다 낫는다"로 바꾼다: 반경 {@link #REACH} 안에 병상이 빈 병원이
 * 있으면 발병한 유아가 입원해 최대 {@link #MAX_SICK_DAYS}일 동안 매일 회복 판정을 받는다. 의사가
 * 있으면 학위별로(학사 60% · 석사 80%), 없으면 {@link #NO_DOCTOR_RECOVERY}(30%). 사흘 안에 못 나으면
 * 죽는다. 체력 30% 이하 성년은 하루 입원으로 60%까지 회복한다. 진료비는 가구가 내고 적자는 군주가
 * 보전, 흑자는 의사와 군주가 반분한다.
 */
public final class Hospital {

    private Hospital() {
    }

    /** 입원 가능 반경(집~병원). */
    public static final double REACH = 64.0;
    /** 유아 앓는 기간 상한 — 이 날수 안에 못 나으면 사망. */
    public static final int MAX_SICK_DAYS = 3;
    /** 의사 없을 때의 하루 회복률. */
    public static final double NO_DOCTOR_RECOVERY = 0.3;
    /** 유아 입원 하루 진료비 · 성년 방문 진료비. */
    public static final double INFANT_FEE = 1.5;
    public static final double VISIT_FEE = 1.0;
    /** 저체력 입원 문턱(체력 비율)과 퇴원 체력. */
    public static final double LOW_HEALTH = 0.30;
    public static final double DISCHARGE_HEALTH = 0.60;
    /** 설립 — 추종 가구 문턱, 같은 갈래 간격. */
    public static final int MIN_FOLLOWERS = 10;
    public static final double MIN_GAP = 96.0;

    /** 하루 회복 확률 — 의사 학위(0 무학위 의사 없음 취급, 1 학사 60%, 2 석사 80%). 의사 없음(-1) 30%. */
    public static double recoveryChance(int doctorDegree) {
        if (doctorDegree < Degree.BACHELOR) {
            return NO_DOCTOR_RECOVERY;
        }
        return Degree.doctorRecovery(doctorDegree);
    }

    /** 앓은 날수가 상한을 넘었는가(입원 뒤 {@link #MAX_SICK_DAYS}번 판정에 다 실패). */
    public static boolean fatal(int sickDays) {
        return sickDays > MAX_SICK_DAYS;
    }

    /** 입원 반경 안인가. */
    public static boolean inReach(double distance) {
        return distance <= REACH;
    }

    /** 저체력 입원 대상인가. */
    public static boolean lowHealth(double health, double maxHealth) {
        return maxHealth > 0.0 && health <= maxHealth * LOW_HEALTH;
    }

    /** 밤 정산 — [0] 군주 보전, [1] 의사 몫, [2] 군주 몫. */
    public static double[] settle(double income, double wages) {
        double net = Math.max(0.0, income) - Math.max(0.0, wages);
        return net < 0.0 ? new double[] {-net, 0.0, 0.0} : new double[] {0.0, net * 0.5, net * 0.5};
    }

    /** 의사 후보 점수 — 학위 필수(학사 이상), 같은 학위면 명석 등급. 무학위 0. */
    public static double doctorScore(int degree, int brightGrade) {
        if (Degree.clamp(degree) < Degree.BACHELOR) {
            return 0.0;
        }
        return Degree.clamp(degree) * 10.0 + Math.max(0, brightGrade) + 1.0;
    }
}
