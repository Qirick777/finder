package com.evosim.core;

import java.util.List;
import java.util.Random;

/**
 * 시드 고정 결정론 난수 (설계서 §17 헤드리스 시뮬 필수 3요소 ①).
 *
 * <p>시뮬/게임의 모든 랜덤은 이 생성기 하나를 거친다 → 같은 시드면 완전히 재현 가능.
 * 검증·밸런싱·헤드리스 시뮬이 이 결정론에 의존한다.
 */
public final class DeterministicRng {
    private final Random random;

    public DeterministicRng(long seed) {
        this.random = new Random(seed);
    }

    /** 확률 p(0..1)로 참. */
    public boolean chance(double p) {
        return random.nextDouble() < p;
    }

    public boolean nextBoolean() {
        return random.nextBoolean();
    }

    /** [0, bound) 정수. */
    public int nextInt(int bound) {
        return random.nextInt(bound);
    }

    /** 리스트에서 균등 랜덤 하나 (빈 리스트면 null). */
    public <T> T pick(List<T> items) {
        if (items.isEmpty()) {
            return null;
        }
        return items.get(random.nextInt(items.size()));
    }

    /** Fisher–Yates 셔플 (제자리). */
    public <T> void shuffle(List<T> items) {
        for (int i = items.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            T tmp = items.get(i);
            items.set(i, items.get(j));
            items.set(j, tmp);
        }
    }
}
