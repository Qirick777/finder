package com.evosim.test;

import com.evosim.core.BreedStats;
import com.evosim.core.Category;
import com.evosim.core.DeterministicRng;
import com.evosim.core.ExpressionResolver;
import com.evosim.core.Genetics;
import com.evosim.core.Individual;
import com.evosim.core.Multipliers;
import com.evosim.core.Sex;
import com.evosim.core.Tag;
import com.evosim.core.Trait;
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
 * <p>두 가지로 호출된다 — 게임 내 {@code /evotest}(EvoTestCommand)와 헤드리스 CLI(main).
 * 둘 다 {@link #runReport(String)} 하나를 공유하므로 결과가 일치한다. 이 클래스는 마크에 의존하지
 * 않는다(설계서 §18) → 순수 실행 가능.
 *
 * <p>헤드리스 사용: {@code ./gradlew evotest --args="genetics"} 또는 {@code "all"}.
 */
public final class EvoTest {

    private EvoTest() {
    }

    public static void main(String[] args) {
        // 리포트에 한글/이모지(✅❌)가 있으므로 로케일과 무관하게 UTF-8로 출력.
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));

        String cmd = args.length > 0 ? args[0].toLowerCase() : "all";
        Report report = runReport(cmd);
        for (String line : report.render()) {
            System.out.println(line);
        }
        if (report.hasFailures()) {
            System.exit(1);
        }
    }

    /**
     * 검증을 실행하고 결과 리포트를 반환한다(출력·종료 없음). 게임 명령어와 CLI가 공유.
     */
    public static Report runReport(String cmd) {
        String kind = cmd == null ? "all" : cmd.toLowerCase();
        Report report = new Report();
        switch (kind) {
            case "genetics" -> genetics(report);
            case "traits" -> traits(report);
            case "multiplier" -> multiplier(report);
            case "all" -> all(report);
            default -> report.add("evotest", false, "genetics | traits | multiplier | all",
                    "알 수 없는 검증: " + cmd);
        }
        return report;
    }

    /** 전체 회귀 (설계서 §17 `/evotest all`). 새 페이즈마다 여기에 검증을 추가한다. */
    private static void all(Report report) {
        genetics(report);
        traits(report);
        multiplier(report);
        // Phase 1↑: feeding, combat, lifespan, mating … 를 여기에 누적.
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

    // ──────────────────────────────────────────────────────────────
    // /evotest traits — 특성 발동 판정 (설계서 Phase 1 ①, §2)
    //   성별발현/흔적 활성화 + 발현 반발 없음(불변식)
    // ──────────────────────────────────────────────────────────────
    private static void traits(Report report) {
        // 1) 남성발현: 남성에서 발동, 여성에서 흔적(발동 X, 보유는 O)
        Individual male = one(Sex.MALE, TraitInstance.of(Trait.BRAVE, Tag.MALE_EXPRESSED));
        Individual female = one(Sex.FEMALE, TraitInstance.of(Trait.BRAVE, Tag.MALE_EXPRESSED));
        boolean maleOn = ExpressionResolver.isExpressed(male, Trait.BRAVE);
        boolean femaleOff = !ExpressionResolver.isExpressed(female, Trait.BRAVE);
        boolean stillHeld = female.allTraits().stream().anyMatch(t -> t.trait() == Trait.BRAVE);
        report.add("traits/남성발현", maleOn && femaleOff && stillHeld,
                "남♂발동·여♀흔적(보유 유지)",
                "남 " + onOff(maleOn) + " · 여 " + onOff(!femaleOff) + " · 여보유 " + yn(stillHeld));

        // 2) 여성발현: 여성에서 발동, 남성에서 흔적
        Individual m2 = one(Sex.MALE, TraitInstance.of(Trait.STRONG_MATERNAL, Tag.FEMALE_EXPRESSED));
        Individual f2 = one(Sex.FEMALE, TraitInstance.of(Trait.STRONG_MATERNAL, Tag.FEMALE_EXPRESSED));
        boolean femOn = ExpressionResolver.isExpressed(f2, Trait.STRONG_MATERNAL);
        boolean malOff = !ExpressionResolver.isExpressed(m2, Trait.STRONG_MATERNAL);
        report.add("traits/여성발현", femOn && malOff,
                "여♀발동·남♂흔적", "여 " + onOff(femOn) + " · 남 " + onOff(!malOff));

        // 3) 상쇄(남성발현+여성발현 동시) → 성별 무관 항상 발동
        Individual cm = one(Sex.MALE, TraitInstance.of(Trait.DILIGENT, Tag.MALE_EXPRESSED, Tag.FEMALE_EXPRESSED));
        Individual cf = one(Sex.FEMALE, TraitInstance.of(Trait.DILIGENT, Tag.MALE_EXPRESSED, Tag.FEMALE_EXPRESSED));
        boolean bothOn = ExpressionResolver.isExpressed(cm, Trait.DILIGENT)
                && ExpressionResolver.isExpressed(cf, Trait.DILIGENT);
        report.add("traits/상쇄", bothOn,
                "남녀발현 동시 → 항상 발동", "남·여 모두 " + onOff(bothOn));

        // 4) 태그 없음 → 성별 무관 항상 발동
        Individual pm = one(Sex.MALE, TraitInstance.of(Trait.HERBALIST));
        Individual pf = one(Sex.FEMALE, TraitInstance.of(Trait.HERBALIST));
        boolean plainOn = ExpressionResolver.isExpressed(pm, Trait.HERBALIST)
                && ExpressionResolver.isExpressed(pf, Trait.HERBALIST);
        report.add("traits/무태그", plainOn,
                "태그없음 → 항상 발동", "남·여 모두 " + onOff(plainOn));

        // 5) 불변식(속성 검증): 랜덤 개체 다수 → 발동 중인 특성끼리 반발쌍 0
        DeterministicRng rng = new DeterministicRng(24680L);
        int violations = 0;
        int samples = 3000;
        for (int i = 0; i < samples; i++) {
            Individual ind = Genetics.randomFirstGen(i + 1, rng);
            List<TraitInstance> exp = ExpressionResolver.expressed(ind);
            for (int a = 0; a < exp.size(); a++) {
                for (int b = a + 1; b < exp.size(); b++) {
                    if (exp.get(a).trait().conflictsWith(exp.get(b).trait())) {
                        violations++;
                    }
                }
            }
        }
        report.add("traits/발현반발", violations == 0,
                "발현 특성 반발쌍 0 (" + samples + "개체)", violations + "건");
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest multiplier — 배율/매력 손계산 대조 (설계서 Phase 1 ①, §15)
    // ──────────────────────────────────────────────────────────────
    private static void multiplier(Report report) {
        // 1) 채집: 약초학자(+0.5) + 손재주(+0.2) = 1.7
        Individual g = one(Sex.MALE,
                TraitInstance.of(Trait.HERBALIST), TraitInstance.of(Trait.DEXTEROUS));
        checkNum(report, "multiplier/채집", 1.7, Multipliers.gather(g),
                "약초학자+손재주 = 1.0+0.5+0.2");

        // 2) 채집: 식물혼동(-0.5) = 0.5
        Individual g2 = one(Sex.MALE, TraitInstance.of(Trait.PLANT_CONFUSED));
        checkNum(report, "multiplier/채집저하", 0.5, Multipliers.gather(g2), "식물혼동 = 1.0-0.5");

        // 3) 사냥: 도축업자(+0.5) + 육식(+0.2) = 1.7 / 그 개체 채집 = 육식(-0.3) = 0.7
        Individual h = one(Sex.MALE,
                TraitInstance.of(Trait.BUTCHER), TraitInstance.of(Trait.CARNIVORE));
        checkNum(report, "multiplier/사냥", 1.7, Multipliers.hunt(h), "도축업자+육식 = 1.0+0.5+0.2");
        checkNum(report, "multiplier/육식채집", 0.7, Multipliers.gather(h), "육식 채집 = 1.0-0.3");

        // 4) 저장: 요리사(+0.2)=1.2 / 요리치(-0.2)=0.8
        checkNum(report, "multiplier/저장", 1.2,
                Multipliers.storage(one(Sex.MALE, TraitInstance.of(Trait.COOK))), "요리사 = 1.2");
        checkNum(report, "multiplier/저장저하", 0.8,
                Multipliers.storage(one(Sex.MALE, TraitInstance.of(Trait.BAD_COOK))), "요리치 = 0.8");

        // 5) 발현 연동: 여성발현 약초학자를 남성이 가지면 흔적 → 배율에 안 잡힘(=1.0)
        Individual vest = one(Sex.MALE, TraitInstance.of(Trait.HERBALIST, Tag.FEMALE_EXPRESSED));
        checkNum(report, "multiplier/흔적무효", 1.0, Multipliers.gather(vest),
                "흔적(발동X)은 배율 미반영");

        // 6) 매력: 평가자 강함선호+똑똑함선호, 상대 힘센+명석 → 2점
        Individual evalr = one(Sex.FEMALE,
                TraitInstance.of(Trait.PREF_STRENGTH), TraitInstance.of(Trait.PREF_SMART));
        Individual tgt = one(Sex.MALE,
                TraitInstance.of(Trait.STRONG), TraitInstance.of(Trait.BRIGHT));
        int charm = Multipliers.charmScore(evalr, tgt);
        report.add("multiplier/매력", charm == 2,
                "강함·똑똑함 선호 → 힘센·명석 상대 = 2", charm + "점");

        // 7) 매력 발현 연동: 상대의 명석이 흔적이면 똑똑함선호 가점 안 됨 → 1점
        Individual tgt2 = one(Sex.MALE,
                TraitInstance.of(Trait.STRONG),
                TraitInstance.of(Trait.BRIGHT, Tag.FEMALE_EXPRESSED)); // 남성에겐 흔적
        int charm2 = Multipliers.charmScore(evalr, tgt2);
        report.add("multiplier/매력흔적", charm2 == 1,
                "상대 명석이 흔적 → 강함선호만 = 1", charm2 + "점");
    }

    /** 특정 성별 + 지정 특성만 가진 검증용 개체 (무대 세팅). */
    private static Individual one(Sex sex, TraitInstance... traits) {
        Individual ind = new Individual(0, sex, 0, 0, 1);
        for (TraitInstance ti : traits) {
            ind.addTrait(ti);
        }
        return ind;
    }

    private static void checkNum(Report report, String id, double expected, double actual, String note) {
        boolean ok = Math.abs(expected - actual) < 1e-9;
        report.add(id, ok, num(expected) + " (" + note + ")", num(actual));
    }

    private static String num(double v) {
        return String.format("%.2f", v);
    }

    private static String onOff(boolean on) {
        return on ? "발동" : "흔적";
    }

    private static String yn(boolean b) {
        return b ? "O" : "X";
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
    public static final class Report {
        private final List<Check> checks = new ArrayList<>();

        void add(String id, boolean pass, String expected, String actual) {
            checks.add(new Check(id, pass, expected, actual));
        }

        public boolean hasFailures() {
            return checks.stream().anyMatch(c -> !c.pass);
        }

        /** 개별 검사 목록 — 게임 표현층이 색상 Component 로 렌더할 때 사용. */
        public List<Check> checks() {
            return List.copyOf(checks);
        }

        /** 요약 헤더 + 상세를 줄 단위 리스트로 (CLI·게임 채팅 공용 출력). */
        public List<String> render() {
            List<String> out = new ArrayList<>();
            long total = checks.size();
            long passed = checks.stream().filter(c -> c.pass).count();
            long failed = total - passed;

            out.add("=== 검증 요약 ===");
            out.add("총 " + total + " · ✅ " + passed + " · ❌ " + failed);
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
                out.add(sb.toString());
            }
            out.add("=================");
            for (Check c : checks) {
                out.add("[" + c.id + "] 기대 " + c.expected
                        + " / 실제 " + c.actual + "  " + (c.pass ? "✅" : "❌"));
            }
            return out;
        }
    }

    /** 검사 하나 — 기대 vs 실제 (설계서 §17 기록 형식). */
    public record Check(String id, boolean pass, String expected, String actual) {
    }
}
