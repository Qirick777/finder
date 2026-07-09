package com.evosim.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 헤드리스 다세대 시뮬 (설계서 §17 헤드리스 시뮬, Phase 2 "안정성+분포").
 *
 * <p>그래픽·물리 없이 순수 로직만으로 세대를 굴려 파이프라인이 크래시·무한루프·즉시전멸 없이
 * 도는지 확인한다. <b>선택압(식량·전투·수명)은 Phase 3</b>이므로, 지금은 세대 교체 + 수용력 상한으로
 * 개체군을 유계로 유지하는 단순 모델(안정성 확인용). 시드 고정이라 완전 재현된다.
 */
public final class Simulation {

    /** 시뮬 결과 리포트 (AI가 읽을 구조화 데이터). */
    public static final class Result {
        public final List<Integer> populationByGen = new ArrayList<>();
        public final Map<Trait, Integer> finalExpressedFreq = new EnumMap<>(Trait.class);
        public int generationsRun = 0;
        public boolean extinct = false;
        public int peakPopulation = 0;
        public long checksum = 1469598103934665603L;
        // 최종 세대의 발현 특성 중 우성 비율(우성 감쇠 검증용).
        public int finalExpressedCount = 0;
        public int finalDominantExpressed = 0;

        public double finalDominantFraction() {
            return finalExpressedCount == 0 ? 0.0 : (double) finalDominantExpressed / finalExpressedCount;
        }
    }

    private Simulation() {
    }

    /**
     * @param seed         결정론 시드
     * @param initialPairs 초기 쌍 수 (개체 = 2×쌍, 1세대 랜덤)
     * @param generations  돌릴 세대 수
     * @param capacity     수용력 상한 (초과 시 랜덤 도태 — 선택압 대용, Phase 3에서 실제 규칙으로 교체)
     */
    public static Result run(long seed, int initialPairs, int generations, int capacity) {
        DeterministicRng rng = new DeterministicRng(seed);
        Result result = new Result();
        long nextId = 1;

        List<Individual> pop = new ArrayList<>();
        for (int i = 0; i < initialPairs * 2; i++) {
            pop.add(Genetics.randomFirstGen(nextId++, rng));
        }
        record(result, pop);

        for (int g = 2; g <= generations + 1; g++) {
            List<Individual> males = new ArrayList<>();
            List<Individual> females = new ArrayList<>();
            for (Individual ind : pop) {
                (ind.sex() == Sex.MALE ? males : females).add(ind);
            }
            rng.shuffle(males);
            rng.shuffle(females);

            int pairs = Math.min(males.size(), females.size());
            List<Individual> next = new ArrayList<>();
            for (int p = 0; p < pairs; p++) {
                Individual a = males.get(p);
                Individual b = females.get(p);
                // 근친(형제) 회피는 Phase 4 — 여기선 안정성만 본다.
                // 성비 보정(교대 배정, 설계서 §2): 각 쌍이 아들·딸 하나씩 → 성비 쏠림사 방지.
                // (인지 범위 성비 기반 보정은 Phase 4에서 실제 규칙으로 교체.)
                next.add(Genetics.breed(nextId++, a, b, rng, g, null, Sex.MALE));
                next.add(Genetics.breed(nextId++, a, b, rng, g, null, Sex.FEMALE));
            }

            // 수용력 상한: 넘치면 랜덤 도태 (선택압 대용).
            if (next.size() > capacity) {
                rng.shuffle(next);
                next = new ArrayList<>(next.subList(0, capacity));
            }

            pop = next;
            record(result, pop);
            result.generationsRun = g - 1;
            if (pop.isEmpty()) {
                result.extinct = true;
                break;
            }
        }

        // 최종 세대 발현 특성 빈도 분포 + 우성 비율.
        for (Individual ind : pop) {
            for (TraitInstance ti : ExpressionResolver.expressed(ind)) {
                result.finalExpressedFreq.merge(ti.trait(), 1, Integer::sum);
                result.finalExpressedCount++;
                if (ti.isDominant()) {
                    result.finalDominantExpressed++;
                }
            }
        }
        return result;
    }

    private static void record(Result result, List<Individual> pop) {
        result.populationByGen.add(pop.size());
        result.peakPopulation = Math.max(result.peakPopulation, pop.size());
        // 인구 추이를 체크섬에 반영(결정론 확인용).
        result.checksum = result.checksum * 1_000_003L + pop.size();
    }
}
