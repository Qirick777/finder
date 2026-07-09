package com.evosim.test;

import com.evosim.core.BehaviorDecision;
import com.evosim.core.DeterministicRng;
import com.evosim.core.Genetics;
import com.evosim.core.Individual;
import com.evosim.core.ExpressionResolver;
import com.evosim.core.Schedule;
import com.evosim.core.Sex;
import com.evosim.core.Trait;

import java.util.ArrayList;
import java.util.List;

/**
 * 디버그 진단 출력 (설계서 §17 {@code /evodebug}). ✅/❌ 판정이 아니라 "무슨 함수를 언제 부르나"를
 * 사람이 읽게 뿌린다. 게임 명령어와 헤드리스가 공유하는 순수 로직.
 *
 * <p>{@code trace}: 랜덤 개체의 하루 행동 타임라인 → 행동 우선순위 버그 확인(설계서 §16 §18).
 * (Phase 2a: 실제 소환 대신 표본 개체를 뽑아 스케줄을 추적. 살아있는 엔티티 추적은 Phase 2b.)
 */
public final class EvoDebug {

    private EvoDebug() {
    }

    public static List<String> trace(int count, long seed) {
        List<String> out = new ArrayList<>();
        out.add("=== /evodebug trace " + count + " (하루 행동 타임라인) ===");
        DeterministicRng rng = new DeterministicRng(seed);

        for (int i = 0; i < count; i++) {
            Individual ind = Genetics.randomFirstGen(i + 1, rng);
            out.add("개체#" + (i + 1) + " [" + (ind.sex() == Sex.MALE ? "남" : "여") + "] "
                    + expressedSummary(ind));

            int step = 500;
            BehaviorDecision.Action cur = null;
            int rangeStart = 0;
            for (int t = 0; t <= Schedule.DAY; t += step) {
                BehaviorDecision.Action a = t < Schedule.DAY ? BehaviorDecision.decide(ind, t) : null;
                if (a != cur) {
                    if (cur != null) {
                        out.add("  " + tick(rangeStart) + "~" + tick(t) + "  " + ko(cur));
                    }
                    cur = a;
                    rangeStart = t;
                }
            }
        }
        return out;
    }

    private static String expressedSummary(Individual ind) {
        List<String> names = new ArrayList<>();
        for (Trait t : ExpressionResolver.expressedTraits(ind)) {
            names.add(t.koreanName());
            if (names.size() >= 6) {
                break;
            }
        }
        return String.join("·", names);
    }

    private static String tick(int t) {
        return String.format("%5d", t);
    }

    private static String ko(BehaviorDecision.Action a) {
        return switch (a) {
            case SLEEP -> "잠";
            case GATHER -> "채집";
            case HUNT -> "사냥";
            case WANDER_COURT -> "배회·구애";
            case RETURN_HOME -> "귀가(밤)";
        };
    }
}
