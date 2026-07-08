package com.evosim.test;

import com.evosim.core.BreedStats;
import com.evosim.core.Category;
import com.evosim.core.DeterministicRng;
import com.evosim.core.Genetics;
import com.evosim.core.Individual;
import com.evosim.core.TraitInstance;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 원터치 검증 하니스 (설계서 §17). 게임 내 {@code /evotest <종류>} 명령어의 헤드리스 대응물.
 *
 * <p>실제 기능이 쓰는 <b>같은 함수</b>(Genetics.breed 등)를 호출해 상황을 만들고 → 실행하고 →
 * 예상값과 대조해 ✅/❌로 판정한다. {@code all}은 전 검증을 한 번에 돌리는 회귀 테스트.
 *
 * <p>사용: {@code ./gradlew run --args="genetics"} 또는 {@code "all"}.
 */
public final class EvoTest {

    public static void main(String[] args) {
        // 리포트에 한글/이모지(✅❌)가 있으므로 로케일과 무관하게 UTF-8로 출력.
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));

        String cmd = args.length > 0 ? args[0].toLowerCase() : "all";
        Report report = new Report();
        switch (cmd) {
            case "genetics" -> genetics(report);
            case "all" -> all(report);
            default -> {
                System.out.println("알 수 없는 검증: " + cmd);
                System.out.println("사용 가능: genetics · all");
                return;
            }
        }
        report.print();
        if (report.hasFailures()) {
            System.exit(1);
        }
    }

    /** 전체 회귀 (설계서 §17 `/evotest all`). 새 페이즈마다 여기에 검증을 추가한다. */
    private static void all(Report report) {
        genetics(report);
        // Phase 1↑: traits, multiplier, feeding, combat, lifespan, mating … 를 여기에 누적.
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest genetics — breed() 1만 회 (설계서 Phase 0)
    //   반발 위반 0 / 우성 75±2% / 돌연변이 2±0.5% / 결정론 재현
    // ──────────────────────────────────────────────────────────────
    private static void genetics(Report report) {
        final int breeds = 10_000;
        final long seed = 987654321L;

        GeneticsRun run = runGenetics(seed, breeds);

        // 1) 반발 위반 0 (정합성 — 불변식)
        report.add("genetics/반발", run.conflictViolations == 0,
                "반발쌍 동시보유 0",
                run.conflictViolations + "건");

        // 2) 특성 개수 범위 (불변식: 카테고리≤3, 총≤9)
        boolean countOk = run.maxPerCategory <= Genetics.MAX_PER_CATEGORY && run.maxTotal <= 9;
        report.add("genetics/개수", countOk,
                "카테고리≤3 · 총≤9",
                "최대 카테고리 " + run.maxPerCategory + " · 최대 총 " + run.maxTotal);

        // 3) 우성 유전 75±2% (설계서 §2 확정)
        double domRate = run.stats.dominantRetentionRate();
        report.add("genetics/우성", withinPct(domRate, 0.75, 0.02),
                "75±2%",
                pct(domRate) + " (표본 " + run.stats.dominantInherited + ")");

        // 4) 돌연변이 2±0.5% (설계서 §2 확정)
        double mutRate = run.stats.mutationRate();
        report.add("genetics/돌연변이", withinPct(mutRate, 0.02, 0.005),
                "2±0.5%",
                pct(mutRate) + " (표본 " + run.stats.mutationRolls + ")");

        // 5) 시드 고정 결정론 (설계서 §17 필수요소 ①): 같은 시드 → 완전 동일
        GeneticsRun again = runGenetics(seed, breeds);
        report.add("genetics/결정론", again.checksum == run.checksum,
                "동일 시드 → 동일 결과",
                run.checksum == again.checksum ? "재현 일치" : "불일치!");
    }

    /** 랜덤 부모 풀을 만들고 breed()를 N회 호출, 통계·불변식·체크섬을 모아 반환. */
    private static GeneticsRun runGenetics(long seed, int breeds) {
        DeterministicRng rng = new DeterministicRng(seed);
        BreedStats stats = new BreedStats();

        List<Individual> parents = new ArrayList<>();
        long nextId = 1;
        for (int i = 0; i < 200; i++) {
            parents.add(Genetics.randomFirstGen(nextId++, rng));
        }

        GeneticsRun run = new GeneticsRun();
        run.stats = stats;

        for (int i = 0; i < breeds; i++) {
            Individual a = parents.get(rng.nextInt(parents.size()));
            Individual b = parents.get(rng.nextInt(parents.size()));
            Individual child = Genetics.breed(nextId++, a, b, rng, 2, stats);

            inspect(child, run);
        }
        return run;
    }

    /** 자식 하나에서 불변식 위반을 세고 체크섬에 반영. */
    private static void inspect(Individual child, GeneticsRun run) {
        int total = 0;
        for (Category cat : Category.values()) {
            List<TraitInstance> list = child.traitsIn(cat);
            run.maxPerCategory = Math.max(run.maxPerCategory, list.size());
            total += list.size();
            // 같은 카테고리 내 반발쌍 동시보유 검사
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    if (list.get(i).trait().conflictsWith(list.get(j).trait())) {
                        run.conflictViolations++;
                    }
                }
                // 체크섬: 특성 ordinal + 태그 수 (결정론 재현 확인용)
                run.checksum = run.checksum * 1_000_003L
                        + list.get(i).trait().ordinal() * 7L
                        + list.get(i).tags().size();
            }
        }
        run.maxTotal = Math.max(run.maxTotal, total);
    }

    private static boolean withinPct(double actual, double target, double tol) {
        return Math.abs(actual - target) <= tol;
    }

    private static String pct(double v) {
        return String.format("%.2f%%", v * 100.0);
    }

    /** 한 번의 genetics 실행 결과 묶음. */
    private static final class GeneticsRun {
        BreedStats stats;
        long conflictViolations = 0;
        int maxPerCategory = 0;
        int maxTotal = 0;
        long checksum = 1469598103934665603L; // FNV-ish 시드
    }

    // ──────────────────────────────────────────────────────────────
    // 검증 리포트 — 기대 vs 실제를 나란히 (설계서 §17 기록 형식)
    // ──────────────────────────────────────────────────────────────
    private static final class Report {
        private final List<Check> checks = new ArrayList<>();

        void add(String id, boolean pass, String expected, String actual) {
            checks.add(new Check(id, pass, expected, actual));
        }

        boolean hasFailures() {
            return checks.stream().anyMatch(c -> !c.pass);
        }

        void print() {
            long total = checks.size();
            long passed = checks.stream().filter(c -> c.pass).count();
            long failed = total - passed;

            System.out.println("=== 검증 요약 ===");
            System.out.println("총 " + total + " · ✅ " + passed + " · ❌ " + failed);
            if (failed > 0) {
                StringBuilder sb = new StringBuilder("❌: ");
                boolean first = true;
                for (Check c : checks) {
                    if (!c.pass) {
                        if (!first) {
                            sb.append(", ");
                        }
                        sb.append(c.id);
                        first = false;
                    }
                }
                System.out.println(sb);
            }
            System.out.println("=================");
            for (Check c : checks) {
                System.out.println("[" + c.id + "] 기대 " + c.expected
                        + " / 실제 " + c.actual + "  " + (c.pass ? "✅" : "❌"));
            }
        }
    }

    private record Check(String id, boolean pass, String expected, String actual) {
    }
}
