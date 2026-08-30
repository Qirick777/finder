package com.evosim.core;

/**
 * <b>교육수준</b>(P5b) — 등교 일수가 만드는 <b>획득</b> 능력. 유전되지 않는다.
 *
 * <p>계획서 1.5 의 "학교 → 획득 능력치(유전 안 됨)" 한 줄을 수로 옮긴 것이다. 값은
 * {@link com.evosim.mod.entity.MimicEntity#getSchoolDays()} 하나뿐이고, 그 수는 아이가 실제로
 * 학교 자리에 도착한 날에만 오른다 — 등록만 하고 못 닿은 아이는 오르지 않는다.
 *
 * <p><b>왜 {@link Individual} 에 두지 않는가</b>: Individual 은 유전되는 것의 그릇이다. 여기에
 * 넣으면 언젠가 상속 코드가 이 값을 함께 옮기게 되고, 그 순간 "획득"이 "세습"이 된다.
 * 그래서 값은 엔티티에 두고, 배율 계산에는 <b>인자로 흘려보낸다</b>({@code gather(ind, level)}).
 */
public final class Schooling {

    private Schooling() {
    }

    /**
     * 도달 가능한 최고 교육수준 — <b>소년기 길이가 상한이다</b>.
     *
     * <p>등교는 소년기에만 하고 하루 한 번 적립된다. 소년기가 3일
     * ({@code MimicEntity.growthTick} 의 BOY 임계 72000틱)이므로 아무리 성실해도 3 을 넘을 수
     * 없다. 단계를 3·6·10 처럼 넉넉히 잡으면 <b>아무도 1단계에 도달하지 못한 채</b> 기능이
     * 죽는다 — 실측 최다는 2 였다. 그래서 눈금을 소년기에 맞춰 압축한다.
     *
     * <p>무학 0 · 초급 1 · 중급 2 · 상급 3.
     */
    public static final int MAX_LEVEL = 3;

    /**
     * 교육수준 1단계당 채집·사냥 가산 — <b>0.02</b>.
     *
     * <p>상급(3단계)에서 +6% 다. 상한을 이 값으로 잡은 근거는 <b>이미 있는 유전 특성</b>이다:
     * 기본교육({@link Trait#BASIC_EDUCATION})이 채집·사냥 각각 +10% 인데, 3일 다닌 <b>획득</b>
     * 교육이 그보다 세면 핏줄로 받은 소양보다 사흘 학교가 낫다는 뜻이 되어 앞뒤가 맞지 않는다.
     * 그 아래에서, 눈에 보일 만큼은 되는 눈금이 0.02 다.
     *
     * <p>명석/멍청 연계는 <b>새 규칙을 만들지 않는다</b> — {@code Multipliers.abilityAmp}
     * (명석 ×1.25 · 멍청 ×0.8)에 그대로 태운다. 이 코드베이스가 약초학자·손재주·채집꾼에
     * 이미 쓰고 있는 "같은 재능도 명석한 자가 더 크게 쓴다"는 규약이고, 교육도 능력 축의
     * 양(+) 항이므로 같은 대우를 받는 것이 일관된다. 결과: 상급에서 멍청 +4.8% · 보통 +6% ·
     * 명석 +7.5%.
     */
    public static final double PER_LEVEL = 0.02;

    /** 등교 일수 → 교육수준(0~{@link #MAX_LEVEL}). 음수·초과는 잘라낸다. */
    public static int level(int schoolDays) {
        return Math.max(0, Math.min(MAX_LEVEL, schoolDays));
    }

    /** 교육수준의 이름 — 보고·사건 로그용. */
    public static String name(int level) {
        return switch (level(level)) {
            case 1 -> "초급";
            case 2 -> "중급";
            case 3 -> "상급";
            default -> "무학";
        };
    }
}
