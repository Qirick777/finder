package com.evosim.core;

/**
 * 짝 성사 시 거처 귀속 판정 (재혼·분가 규칙, 순수 함수). 두 개체의 거처 상태로 어느 거처를 쓸지 결정.
 *
 * <ul>
 *   <li>방랑(거처 없음) / 성년자식(부모와 동거 = FAMILY) → 새 거처(빈 거처 재활용 or 신축).</li>
 *   <li>혼자 사는 거처주(LONE, 사별·최후 생존) 한쪽만 있으면 → 그 거처로 이주.</li>
 *   <li>둘 다 혼자 사는 거처주면 → 한쪽 랜덤 폐기·합류(KEEP_ONE).</li>
 * </ul>
 */
public final class MateHome {

    /** 개체의 거처 상태. */
    public enum Status {
        WANDERER,       // 거처 없음
        LONE_OWNER,     // 거처에 혼자 사는 성년(사별/최후)
        FAMILY_MEMBER   // 거처에 다른 성년과 동거(부모와 사는 성년 자식 등)
    }

    /** 결정된 거처 처리. */
    public enum Action {
        NEW,        // 새 거처(빈 거처 재활용 우선, 없으면 신축)
        USE_A,      // A의 거처로 둘 다 이주
        USE_B,      // B의 거처로 둘 다 이주
        KEEP_ONE    // 둘 다 단독 거처주 → 한쪽 랜덤 유지·다른쪽 폐기
    }

    private MateHome() {
    }

    public static Action resolve(Status a, Status b) {
        boolean aLone = a == Status.LONE_OWNER;
        boolean bLone = b == Status.LONE_OWNER;
        if (aLone && bLone) {
            return Action.KEEP_ONE;
        }
        if (aLone) {
            return Action.USE_A;   // 혼자 사는 쪽 거처로 (상대는 방랑/자식이므로 그 집으로 이주)
        }
        if (bLone) {
            return Action.USE_B;
        }
        return Action.NEW;         // 둘 다 방랑 or 자식 → 새 거처(재활용/신축)
    }
}
