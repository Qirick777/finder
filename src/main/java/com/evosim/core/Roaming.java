package com.evosim.core;

import java.util.Set;

/**
 * 활동반경 (설계서 §13-D 정착·§14 군집). 개체가 앵커(거처, 없으면 태어난 곳)에서 <b>얼마나 멀리까지</b>
 * 돌아다닐 수 있는지를 특성으로 정한다 — 이 반경을 넘으면 표현층 리시(leash)가 앵커로 되돌린다.
 *
 * <p>{@link Multipliers}·{@link Physique}와 같은 순수-가산 패턴. 특성별 차등:
 * <ul>
 *   <li><b>이주자</b> 넓게(×2) / <b>애향심</b> 좁게(×0.5) — 정착 성향</li>
 *   <li><b>고독</b> 넓게(×1.5, 홀로 멀리) / <b>군집</b> 좁게(×0.75, 무리 곁) — 군집 성향</li>
 * </ul>
 *
 * <p>이 함수가 없으면 활동반경 제한이 전무해 개체군이 무한 표류·분산(증발)했다. 순수 함수라
 * {@code /evotest roaming}으로 조합별 반경을 손계산 대조한다.
 */
public final class Roaming {

    /** 기본 활동반경(블록) — 중립 개체. */
    public static final double BASE_RADIUS = 32.0;

    private Roaming() {
    }

    /** 개체의 활동반경(블록) — 정착·군집 성향으로 차등. */
    public static double radius(Individual ind) {
        double r = BASE_RADIUS;
        Set<Trait> t = ExpressionResolver.expressedTraits(ind);
        if (t.contains(Trait.MIGRATORY)) {
            r *= 2.0;          // 이주자: 멀리 개척
        } else if (t.contains(Trait.HOMEBOUND)) {
            r *= 0.5;          // 애향심: 고향 근처
        }
        if (t.contains(Trait.SOLITARY)) {
            r *= 1.5;          // 고독: 홀로 넓게
        } else if (t.contains(Trait.GREGARIOUS)) {
            r *= 0.75;         // 군집: 무리 곁에
        }
        return r;
    }
}
