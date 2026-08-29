package com.evosim.mod.entity;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongToDoubleFunction;
import java.util.function.LongUnaryOperator;

/**
 * <b>신분 4계층</b> — 지배 / 상위 / 평민 / 천민. 전부 <b>파생값</b>이고 저장하지 않는다.
 *
 * <p>이 파일이 계층 판정의 <b>단일 출처</b>다. 종전에는 판정식이 보고 명령
 * ({@code /evosim allegiance}) 안에만 있어서 게임 로직이 읽을 수 없었다. 세금·군인·첩·
 * 사용료(P4~P7)가 모두 이 계층을 입력으로 쓰므로, 판정식이 두 벌로 갈라지면 밭 격자
 * 재구성 때와 같은 종류의 어긋남이 생긴다 — 그래서 한 곳에 모았다.
 *
 * <p><b>신분으로 분기하지 않는다</b>(규칙5). 아래 조건에 신분을 묻는 항목은 하나도 없고,
 * 전부 셀 수 있는 수 — 추종자 수·주인 유무·소유 밭 타일·상환분·연속 궁핍 일수 — 뿐이다.
 * 계층은 그 수들이 만든 <b>결과</b>이지 입력이 아니다.
 */
public enum SocialRank {

    /**
     * <b>지배</b> — 아무도 따르지 않으면서 추종자를 거느리고, 그 추종자 중에 <b>또 주인이
     * 있는</b> 자. 즉 사람을 통해 사람을 부린다(간접 지배). 세력 크기가 아니라 <b>구조</b>로
     * 가른다 — 열 명을 직접 거느리는 것과, 세 명을 거느린 자를 셋 거느리는 것은 다른 물건이다.
     */
    RULER("지배"),

    /** <b>상위</b> — 추종자를 거느리되 그 위에 또 누군가가 있거나, 지배의 구조 조건을 못 채운 자. */
    UPPER("상위"),

    /** <b>평민</b> — 거느리지도 매이지도 않았거나, 매였어도 제 땅·제 살림이 있는 자. */
    COMMON("평민"),

    /**
     * <b>천민</b> — 거느리는 자 없고, 주인에게 매였고, 제 땅이 없고, 그 상태를 <b>스스로
     * 벗어나지 못하는</b> 자.
     *
     * <p>"벗어나지 못함"의 척도가 둘이다. 상환분이 상환능력을 넘거나(빚), 가구가 하루를 못
     * 넘기는 날이 {@link #DESTITUTE_DAYS} 일 연달았거나(궁핍). 앞의 것은 대출·세금이 붙는
     * P4 부터 0 이 아니게 되고, 뒤의 것은 <b>지금 이미 존재하는 수</b>다. 둘 중 하나면 된다.
     */
    LOW("천민");

    private final String label;

    SocialRank(String label) {
        this.label = label;
    }

    /** 보고·로그 표기. */
    public String label() {
        return label;
    }

    /**
     * 천민 판정의 궁핍 쪽 문턱 — 가구 저장고가 가구 하루소모에 못 미치는 날이 이만큼 연달으면
     * 자활 불능으로 본다.
     *
     * <p>3 인 이유: 하루는 사고이고 이틀은 불운이지만 사흘이면 추세다. 저장고는 날마다
     * 출렁이므로({@link AllegianceStore#TILE_WORTH} 주석의 그 이유) 하루치 스냅숏으로
     * 신분을 매기면 계층이 매일 뒤집힌다. <b>측정 뒤에 확정할 값</b>이다.
     */
    public static final int DESTITUTE_DAYS = 3;

    /**
     * 모두의 계층을 한 번에 판정한다.
     *
     * @param people        살아 있는 개체 id 전부
     * @param patron        추종자 id → 주인 id ({@link AllegianceStore#patronMap} 의 결과)
     * @param ownedTiles    id → 소유 밭 타일 수
     * @param owed          id → 상환분 합
     * @param destituteDays id → 연속 궁핍 일수
     */
    public static Map<Long, SocialRank> derive(
            Collection<Long> people,
            Map<Long, Long> patron,
            LongUnaryOperator ownedTiles,
            LongToDoubleFunction owed,
            LongUnaryOperator destituteDays) {

        // 직속 추종자 수 — 주인 쪽에서 센다.
        Map<Long, Integer> direct = new HashMap<>();
        for (long p : patron.values()) {
            direct.merge(p, 1, Integer::sum);
        }

        Map<Long, SocialRank> out = new HashMap<>();
        for (long id : people) {
            int followers = direct.getOrDefault(id, 0);
            long myPatron = patron.getOrDefault(id, 0L);

            if (followers >= 1) {
                // 거느리는 자는 아무리 궁해도 천민이 아니다 — 천민 검사보다 먼저 갈린다.
                out.put(id, myPatron == 0L && rulesThroughOthers(id, patron, direct)
                        ? RULER : UPPER);
                continue;
            }
            int tiles = (int) ownedTiles.applyAsLong(id);
            boolean bound = myPatron != 0L && tiles == 0;
            boolean insolvent = owed.applyAsDouble(id)
                    > Math.max(AllegianceStore.MIN_BOND, tiles * AllegianceStore.TILE_WORTH);
            boolean destitute = destituteDays.applyAsLong(id) >= DESTITUTE_DAYS;
            out.put(id, bound && (insolvent || destitute) ? LOW : COMMON);
        }
        return out;
    }

    /** 내 추종자 중에 <b>스스로 주인인</b> 자가 하나라도 있는가 — 간접 지배의 판정. */
    private static boolean rulesThroughOthers(long id, Map<Long, Long> patron,
                                              Map<Long, Integer> direct) {
        for (var e : patron.entrySet()) {
            if (e.getValue() == id && direct.getOrDefault(e.getKey(), 0) >= 1) {
                return true;
            }
        }
        return false;
    }
}
