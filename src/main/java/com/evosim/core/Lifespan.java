package com.evosim.core;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 세대 기반 수명 + 상속 (설계서 §9). 나이 카운터 없이 계보(부모 링크)로 판정한다.
 *
 * <p>순수 함수 — 인지 범위 내 개체 집합을 넘겨 "죽을 때인가?"만 답한다. 실제 게임은 로딩된 청크의
 * 미믹만 모아 이 함수를 호출.
 */
public final class Lifespan {

    /** 상속 상한 = 아이 1명 부양비(번식 임계치와 동일, 설계서 §9 자원 왜곡 방지). */
    public static final double CHILD_SUPPORT_COST = 2.5;

    private Lifespan() {
    }

    /** 계보 판정용 경량 개체 (묘비/살아있는 개체 공용). */
    public record Being(long id, long parentA, long parentB, LifeStage stage, boolean everReproduced) {
    }

    /**
     * 자연사 판정 (설계서 §9):
     * <ul>
     *   <li>살아있는 자식이 있고 → 모두 성년 + 손자 존재 → 즉시 사망(증조부 나이).</li>
     *   <li>자식이 아직 미성년이거나 손자 없음 → 생존(부양 지속).</li>
     *   <li>번식했었는데 살아있는 자식이 전부 사라짐 → 부모도 퇴장(영생 구멍 차단).</li>
     *   <li>번식한 적 없는 개체(방랑자) → 이 판정으로는 안 죽음(다른 규칙).</li>
     * </ul>
     */
    public static boolean shouldDie(Being self, Collection<Being> living) {
        Set<Long> childIds = new HashSet<>();
        boolean allAdult = true;
        for (Being b : living) {
            if (b.parentA() == self.id() || b.parentB() == self.id()) {
                childIds.add(b.id());
                if (b.stage() != LifeStage.ADULT) {
                    allAdult = false;
                }
            }
        }
        if (childIds.isEmpty()) {
            return self.everReproduced(); // 번식 후 자식 전멸 → 퇴장 / 방랑자 → 생존
        }
        if (!allAdult) {
            return false; // 자식 미성년 → 부양 지속(생존)
        }
        for (Being b : living) {
            if (childIds.contains(b.parentA()) || childIds.contains(b.parentB())) {
                return true; // 손자 존재 + 자식 모두 성년 → 즉시 사망
            }
        }
        return false; // 자식 성년이지만 아직 손자 없음 → 생존
    }

    /**
     * 상속액 (설계서 §9): 부모 좌표에 남은 자가 있으면 "아이 1명 부양비"만 상속, 나머지 소멸.
     * 아무도 없으면 전부 소멸(무한 이월로 식량이 안 마르는 왜곡 방지).
     */
    public static double inheritAmount(double storage, boolean heirPresent) {
        if (!heirPresent) {
            return 0.0;
        }
        return Math.min(storage, CHILD_SUPPORT_COST);
    }
}
