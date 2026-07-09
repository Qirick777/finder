package com.evosim.test;

import com.evosim.core.BehaviorDecision;
import com.evosim.core.BreedStats;
import com.evosim.core.Category;
import com.evosim.core.Combat;
import com.evosim.core.Courtship;
import com.evosim.core.DailyCycle;
import com.evosim.core.DeterministicRng;
import com.evosim.core.ExpressionResolver;
import com.evosim.core.Feeding;
import com.evosim.core.Genetics;
import com.evosim.core.Individual;
import com.evosim.core.Kinship;
import com.evosim.core.LifeStage;
import com.evosim.core.Lifespan;
import com.evosim.core.Mating;
import com.evosim.core.MateChoiceClass;
import com.evosim.core.Multipliers;
import com.evosim.core.ParentingClass;
import com.evosim.core.Reproduction;
import com.evosim.core.Schedule;
import com.evosim.core.Settlement;
import com.evosim.core.Sex;
import com.evosim.core.Simulation;
import com.evosim.core.SurvivalRules;
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

        // /evodebug trace — 진단 출력(판정 없음).
        if (cmd.equals("trace")) {
            int count = args.length > 1 ? Integer.parseInt(args[1]) : 3;
            for (String line : EvoDebug.trace(count, 42L)) {
                System.out.println(line);
            }
            return;
        }

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
            case "simulate" -> simulate(report);
            case "combat" -> combat(report);
            case "feeding" -> feeding(report);
            case "lifecycle" -> lifecycle(report);
            case "lifespan" -> lifespan(report);
            case "mating" -> mating(report);
            case "settlement" -> settlement(report);
            case "reproduction" -> reproduction(report);
            case "parenting" -> parenting(report);
            case "cycle" -> cycle(report);
            case "courtship" -> courtship(report);
            case "matechoice" -> matechoice(report);
            case "all" -> all(report);
            default -> report.add("evotest", false,
                    "genetics | traits | multiplier | simulate | combat | feeding | lifecycle | lifespan | mating | settlement | reproduction | parenting | cycle | courtship | matechoice | all",
                    "알 수 없는 검증: " + cmd);
        }
        return report;
    }

    /** 전체 회귀 (설계서 §17 `/evotest all`). 새 페이즈마다 여기에 검증을 추가한다. */
    private static void all(Report report) {
        genetics(report);
        traits(report);
        multiplier(report);
        simulate(report);
        combat(report);
        feeding(report);
        lifecycle(report);
        lifespan(report);
        mating(report);
        settlement(report);
        reproduction(report);
        parenting(report);
        cycle(report);
        courtship(report);
        matechoice(report);
        // Phase 4↑: family_lifecycle, 경쟁 … 를 여기에 누적.
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest genetics — breed() 1만 회 (설계서 Phase 0)
    //   반발 위반 0 / 우성 75±2% / 돌연변이 2±0.5% / 결정론 재현
    // ──────────────────────────────────────────────────────────────
    private static void genetics(Report report) {
        final int breeds = 10_000;
        final long seed = 987654321L;

        GeneticsRun run = runGenetics(seed, breeds);

        // 1) 발현 반발 0 (불변식 — 보유는 흔적/반발 카드로 반발 공존 허용, 발현은 반발 없음)
        report.add("genetics/발현반발", run.conflictViolations == 0,
                "발현 반발쌍 0",
                run.conflictViolations + "건");

        // 2) 발현 개수 범위 (불변식: 발현 카테고리≤3, 발현 총≤9)
        boolean countOk = run.maxPerCategory <= Genetics.MAX_PER_CATEGORY && run.maxTotal <= 9;
        report.add("genetics/발현개수", countOk,
                "발현 카테고리≤3 · 총≤9 (보유 최대 " + run.maxHeldTotal + ")",
                "최대 발현 카테고리 " + run.maxPerCategory + " · 최대 발현 총 " + run.maxTotal);

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

        // 6) 우성 전달우위 (설계서 §2): 우성은 우선 선택돼 전달우위로 세대↑(도배 허용).
        //    단 태그는 75%만 유전(25% 열성화)이라 상한이 유지율 근처(≤~82%)에 걸림 — 100% 포화 X.
        //    선택압(굶주림·전투사)이 붙으면 나쁜데 우성인 특성이 도태돼 이 비율이 내려간다.
        double gen1Dom = gen1DominantFraction(seed, 400);
        Simulation.Result evolved = Simulation.run(seed, 30, 30, 80);
        double evolvedDom = evolved.finalDominantFraction();
        boolean domOk = evolvedDom > gen1Dom && evolvedDom <= 0.82;
        report.add("genetics/우성전달우위", domOk,
                "우성 전달우위로 세대↑·상한≤82% (선택압으로 상쇄 예정)",
                "1세대 " + pct(gen1Dom) + " → 30세대 " + pct(evolvedDom));
    }

    /** 갓 태어난 1세대 표본의 발현 특성 중 우성 비율(씨앗 우성률 근사). */
    private static double gen1DominantFraction(long seed, int sample) {
        DeterministicRng rng = new DeterministicRng(seed ^ 0x5DEECE66DL);
        int dom = 0;
        int total = 0;
        for (int i = 0; i < sample; i++) {
            Individual ind = Genetics.randomFirstGen(i + 1, rng);
            for (TraitInstance ti : ExpressionResolver.expressed(ind)) {
                total++;
                if (ti.isDominant()) {
                    dom++;
                }
            }
        }
        return total == 0 ? 0.0 : (double) dom / total;
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

    /**
     * 자식 하나에서 불변식 위반을 센다. Phase 1 ② 이후 흔적 보상·반발 카드로 <b>보유</b> 반발은 허용되므로,
     * 불변식은 <b>발현</b> 수준으로 검사한다(발현 반발 0, 발현 카테고리≤3, 발현 총≤9). 체크섬은 보유 전체.
     */
    private static void inspect(Individual child, GeneticsRun run) {
        // 발현 수준 불변식
        List<TraitInstance> expressed = ExpressionResolver.expressed(child);
        int[] perCat = new int[Category.values().length];
        for (int i = 0; i < expressed.size(); i++) {
            perCat[expressed.get(i).category().ordinal()]++;
            for (int j = i + 1; j < expressed.size(); j++) {
                if (expressed.get(i).trait().conflictsWith(expressed.get(j).trait())) {
                    run.conflictViolations++;
                }
            }
        }
        for (int c : perCat) {
            run.maxPerCategory = Math.max(run.maxPerCategory, c);
        }
        run.maxTotal = Math.max(run.maxTotal, expressed.size());

        // 체크섬은 보유 특성 전체(결정론 재현 확인) + 보유 총량 추적
        int held = 0;
        for (Category cat : Category.values()) {
            List<TraitInstance> list = child.traitsIn(cat);
            held += list.size();
            for (TraitInstance ti : list) {
                run.checksum = run.checksum * 1_000_003L
                        + ti.trait().ordinal() * 7L
                        + ti.tags().size()
                        + (ti.isAnti() ? 3L : 0L);
            }
        }
        run.maxHeldTotal = Math.max(run.maxHeldTotal, held);
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

        // 6) 반발 카드: 용감함 + 용감함(반발) → 용감함 무력화(흔적). 카드가 유전자는 남김(억제유전자).
        Individual sup = one(Sex.MALE,
                TraitInstance.of(Trait.BRAVE), TraitInstance.antiCard(Trait.BRAVE));
        boolean suppressed = !ExpressionResolver.isExpressed(sup, Trait.BRAVE);
        boolean geneKept = sup.allTraits().stream().anyMatch(t -> t.trait() == Trait.BRAVE && !t.isAnti());
        report.add("traits/반발무력화", suppressed && geneKept,
                "발현 대상 무력화 + 유전자 잔존",
                "용감함 " + onOff(!suppressed) + " · 유전자잔존 " + yn(geneKept));

        // 7) 반발 카드 무대상: 겁쟁이 + 용감함(반발) → 끌 대상 없음 → 겁쟁이 유지, 카드가 흔적
        Individual noTarget = one(Sex.MALE,
                TraitInstance.of(Trait.COWARD), TraitInstance.antiCard(Trait.BRAVE));
        boolean cowardKept = ExpressionResolver.isExpressed(noTarget, Trait.COWARD);
        report.add("traits/반발무대상", cowardKept,
                "무력화 대상 없으면 카드가 흔적", "겁쟁이 " + onOff(cowardKept));

        // 8) 반발 카드 성별발현: 여성발현 반발 카드를 남성이 가지면 카드가 흔적 → 무력화 안 됨
        Individual antiVest = one(Sex.MALE,
                TraitInstance.of(Trait.BRAVE),
                TraitInstance.antiCard(Trait.BRAVE, Tag.FEMALE_EXPRESSED)); // 남성에겐 카드 흔적
        boolean stillBrave = ExpressionResolver.isExpressed(antiVest, Trait.BRAVE);
        report.add("traits/반발흔적", stillBrave,
                "흔적 반발카드는 무력화 못함", "용감함 " + onOff(stillBrave));

        // 9) 흔적 보상: 성별발현 특성을 가진 부모에서 breed → 흔적 보상이 발생하고, 보유엔 반발 공존이
        //    생기되(흔적+발현) 발현엔 반발 0. 보상이 실제로 일어나는지 통계로 확인.
        DeterministicRng r2 = new DeterministicRng(13579L);
        BreedStats st = new BreedStats();
        List<Individual> pool = new ArrayList<>();
        long id = 1;
        for (int i = 0; i < 200; i++) {
            pool.add(Genetics.randomFirstGen(id++, r2));
        }
        boolean heldConflictSeen = false;
        int expressedConflicts = 0;
        int breeds = 5000;
        for (int i = 0; i < breeds; i++) {
            Individual a = pool.get(r2.nextInt(pool.size()));
            Individual b = pool.get(r2.nextInt(pool.size()));
            Individual c = Genetics.breed(id++, a, b, r2, 2, st);
            // 보유 반발(흔적+발현 공존) 관측
            List<TraitInstance> all = c.allTraits();
            for (int x = 0; x < all.size() && !heldConflictSeen; x++) {
                for (int y = x + 1; y < all.size(); y++) {
                    if (!all.get(x).isAnti() && !all.get(y).isAnti()
                            && all.get(x).trait().conflictsWith(all.get(y).trait())) {
                        heldConflictSeen = true;
                        break;
                    }
                }
            }
            // 발현 반발은 0이어야
            List<TraitInstance> exp = ExpressionResolver.expressed(c);
            for (int x = 0; x < exp.size(); x++) {
                for (int y = x + 1; y < exp.size(); y++) {
                    if (exp.get(x).trait().conflictsWith(exp.get(y).trait())) {
                        expressedConflicts++;
                    }
                }
            }
        }
        report.add("traits/흔적보상", st.vestigialRewards > 0 && expressedConflicts == 0,
                "보상 발생 & 발현반발 0",
                "보상 " + st.vestigialRewards + "회 · 발현반발 " + expressedConflicts + "건");
        report.add("traits/공존", heldConflictSeen,
                "흔적+발현 공존(보유 반발) 관측됨", heldConflictSeen ? "관측" : "미관측");
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

        // 6) 매력: 특정선호(강함·똑똑함)는 각 +2 → 힘센+명석 상대 = 4점
        Individual evalr = one(Sex.FEMALE,
                TraitInstance.of(Trait.PREF_STRENGTH), TraitInstance.of(Trait.PREF_SMART));
        Individual tgt = one(Sex.MALE,
                TraitInstance.of(Trait.STRONG), TraitInstance.of(Trait.BRIGHT));
        int charm = Multipliers.charmScore(evalr, tgt);
        report.add("multiplier/매력", charm == 4,
                "특정선호 강함·똑똑함(각 +2) → 힘센·명석 상대 = 4", charm + "점");

        // 7) 매력 발현 연동: 상대의 명석이 흔적이면 똑똑함선호 가점 안 됨 → 강함선호만 = 2점
        Individual tgt2 = one(Sex.MALE,
                TraitInstance.of(Trait.STRONG),
                TraitInstance.of(Trait.BRIGHT, Tag.FEMALE_EXPRESSED)); // 남성에겐 흔적
        int charm2 = Multipliers.charmScore(evalr, tgt2);
        report.add("multiplier/매력흔적", charm2 == 2,
                "상대 명석이 흔적 → 강함선호만 = 2", charm2 + "점");

        // 8) 포괄선호(능력선호)는 개념군 특성 개수마다 +1 → 채집자+사냥꾼+명석 상대 = 3점
        Individual abilityEval = one(Sex.FEMALE, TraitInstance.of(Trait.PREF_ABILITY));
        Individual multiTgt = one(Sex.MALE, TraitInstance.of(Trait.GATHERER),
                TraitInstance.of(Trait.HUNTER), TraitInstance.of(Trait.BRIGHT));
        int charm3 = Multipliers.charmScore(abilityEval, multiTgt);
        report.add("multiplier/매력포괄", charm3 == 3,
                "능력선호 → 개념군 특성 개수마다 +1 (채집·사냥·명석 = 3)", charm3 + "점");
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest simulate — 헤드리스 다세대 안정성 + 시간대/행동 로직 (설계서 Phase 2, §16 §17)
    // ──────────────────────────────────────────────────────────────
    private static void simulate(Report report) {
        final long seed = 555L;
        final int pairs = 30, generations = 30, capacity = 80;

        // 1) 안정성: N세대 완주 · 전멸 없음 · 수용력 유계 (크래시/무한루프/즉시전멸 없음)
        Simulation.Result r = Simulation.run(seed, pairs, generations, capacity);
        boolean stable = !r.extinct
                && r.generationsRun == generations
                && r.peakPopulation <= capacity
                && r.populationByGen.get(r.populationByGen.size() - 1) > 0;
        report.add("simulate/안정성", stable,
                generations + "세대 완주·전멸X·≤" + capacity,
                "완주 " + r.generationsRun + " · 전멸 " + yn(r.extinct)
                        + " · 피크 " + r.peakPopulation + " · 최종 "
                        + r.populationByGen.get(r.populationByGen.size() - 1));

        // 2) 결정론: 같은 시드 → 같은 인구 추이
        Simulation.Result r2 = Simulation.run(seed, pairs, generations, capacity);
        report.add("simulate/결정론", r.checksum == r2.checksum,
                "동일 시드 → 동일 추이", r.checksum == r2.checksum ? "재현 일치" : "불일치!");

        // 3) 분포 산출: 최종 세대에 발현 특성 종류가 다수 존재 (분포 리포트 가능)
        report.add("simulate/분포", r.finalExpressedFreq.size() > 5,
                "최종 세대 발현 특성 다양", r.finalExpressedFreq.size() + "종");

        // 4) 시간대 경계 (설계서 §16) — 중립 개체: 기상1000·노동8000·황혼12000·취침14000
        Individual n = one(Sex.MALE);
        boolean sched = Schedule.phaseAt(n, 500) == Schedule.Phase.SLEEP
                && Schedule.phaseAt(n, 4000) == Schedule.Phase.WORK
                && Schedule.phaseAt(n, 10000) == Schedule.Phase.WANDER
                && Schedule.phaseAt(n, 13000) == Schedule.Phase.NIGHT
                && Schedule.phaseAt(n, 20000) == Schedule.Phase.SLEEP;
        report.add("simulate/시간대", sched,
                "기상→일→배회→밤→취침 경계", sched ? "정상" : "경계 어긋남");

        // 4-b) 전역 하루 구간 (시계·로그 표시용, 오프셋 없음) — 경계 + 음수/큰 틱 정규화
        boolean global = Schedule.globalPhase(500) == Schedule.Phase.SLEEP
                && Schedule.globalPhase(4000) == Schedule.Phase.WORK
                && Schedule.globalPhase(10000) == Schedule.Phase.WANDER
                && Schedule.globalPhase(13000) == Schedule.Phase.NIGHT
                && Schedule.globalPhase(20000) == Schedule.Phase.SLEEP
                && Schedule.globalPhase(24000 + 4000) == Schedule.Phase.WORK  // 다음날 정규화
                && Schedule.globalPhase(-20000) == Schedule.Phase.WORK;       // 음수 정규화(-20000→4000)
        report.add("simulate/전역시간", global,
                "globalPhase 경계 + 틱 정규화(음수·초과)", global ? "정상" : "어긋남");

        // 5) 기상 오프셋 (설계서 §16): 부지런은 일찍 기상(500틱에 이미 노동), 게으름은 늦잠(1500틱 취침)
        Individual dili = one(Sex.MALE, TraitInstance.of(Trait.DILIGENT));
        Individual lazy = one(Sex.MALE, TraitInstance.of(Trait.LAZY));
        boolean diliEarly = Schedule.phaseAt(dili, 500) == Schedule.Phase.WORK
                && Schedule.phaseAt(n, 500) == Schedule.Phase.SLEEP;
        boolean lazyLate = Schedule.phaseAt(lazy, 1500) == Schedule.Phase.SLEEP
                && Schedule.phaseAt(n, 1500) == Schedule.Phase.WORK;
        report.add("simulate/기상오프셋", diliEarly && lazyLate,
                "부지런 일찍·게으름 늦잠",
                "부지런 " + yn(diliEarly) + " · 게으름 " + yn(lazyLate));

        // 6) 행동 결정 (설계서 §18): 노동 구간에 사냥꾼→사냥, 채집꾼→채집, 중립→채집(기본)
        Individual hunter = one(Sex.MALE, TraitInstance.of(Trait.HUNTER));
        Individual gath = one(Sex.MALE, TraitInstance.of(Trait.GATHERER));
        boolean act = BehaviorDecision.decide(hunter, 4000) == BehaviorDecision.Action.HUNT
                && BehaviorDecision.decide(gath, 4000) == BehaviorDecision.Action.GATHER
                && BehaviorDecision.decide(n, 4000) == BehaviorDecision.Action.GATHER
                && BehaviorDecision.decide(n, 13000) == BehaviorDecision.Action.RETURN_HOME
                && BehaviorDecision.decide(n, 20000) == BehaviorDecision.Action.SLEEP;
        report.add("simulate/행동", act,
                "사냥꾼→사냥·채집꾼→채집·밤→귀가", act ? "정상" : "결정 어긋남");
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest combat — 전투 3층위 판정 (설계서 Phase 3, §13-B)
    // ──────────────────────────────────────────────────────────────
    private static void combat(Report report) {
        Individual brave = one(Sex.MALE, TraitInstance.of(Trait.BRAVE));
        Individual coward = one(Sex.MALE, TraitInstance.of(Trait.COWARD));
        Individual neutral = one(Sex.MALE);
        Individual reckless = one(Sex.MALE, TraitInstance.of(Trait.RECKLESS));
        Individual prudent = one(Sex.MALE, TraitInstance.of(Trait.PRUDENT));

        // ① 진입: 용감(감지 내)→ENGAGE, 용감(밖)→IGNORE, 겁쟁이→FLEE, 중립(인접)→ENGAGE, 중립(원거리)→IGNORE
        boolean entry = Combat.entry(brave, false, true) == Combat.Entry.ENGAGE
                && Combat.entry(brave, false, false) == Combat.Entry.IGNORE
                && Combat.entry(coward, true, true) == Combat.Entry.FLEE
                && Combat.entry(neutral, true, true) == Combat.Entry.ENGAGE
                && Combat.entry(neutral, false, true) == Combat.Entry.IGNORE;
        report.add("combat/진입", entry,
                "용감 적극·겁쟁이 회피·중립 온것만", entry ? "정상" : "어긋남");

        // ② 퇴각: 무모 HOLD, 중립 하한→RETREAT, 신중 하한+가족→HOLD, 신중 하한+비가족→RETREAT
        boolean retreat = Combat.retreat(reckless, 0.1, false) == Combat.Retreat.HOLD
                && Combat.retreat(neutral, 0.1, false) == Combat.Retreat.RETREAT
                && Combat.retreat(neutral, 0.5, false) == Combat.Retreat.HOLD
                && Combat.retreat(prudent, 0.1, true) == Combat.Retreat.HOLD
                && Combat.retreat(prudent, 0.1, false) == Combat.Retreat.RETREAT;
        report.add("combat/퇴각", retreat,
                "무모 안물러남·신중 가족앞 버팀", retreat ? "정상" : "어긋남");

        // ③ 복귀: 신중 상한 회복→복귀, 중립→X, 신중 미회복→X
        boolean ret = Combat.returnsToCombat(prudent, 0.8)
                && !Combat.returnsToCombat(neutral, 0.8)
                && !Combat.returnsToCombat(prudent, 0.5);
        report.add("combat/복귀", ret, "신중만 회복 후 복귀", ret ? "정상" : "어긋남");

        // 조합: 겁쟁이+무모 → 겁쟁이가 진입을 막아 FLEE (무모 발동 안 함 — 모순 없음)
        Individual cowReck = one(Sex.MALE, TraitInstance.of(Trait.COWARD), TraitInstance.of(Trait.RECKLESS));
        report.add("combat/조합", Combat.entry(cowReck, true, true) == Combat.Entry.FLEE,
                "겁쟁이+무모 → 진입 막힘(FLEE)",
                Combat.entry(cowReck, true, true).toString());

        // 성별발현 연동: 용감[여성발현]을 남성이 가지면 흔적 → 중립처럼 행동(감지 밖 IGNORE)
        Individual braveVest = one(Sex.MALE, TraitInstance.of(Trait.BRAVE, Tag.FEMALE_EXPRESSED));
        report.add("combat/발현연동", Combat.entry(braveVest, false, true) == Combat.Entry.IGNORE,
                "흔적 용감 → 중립 행동", Combat.entry(braveVest, false, true).toString());

        // 감지 범위: 용감 > 중립 > 겁쟁이
        boolean range = Combat.detectionRange(brave) > Combat.detectionRange(neutral)
                && Combat.detectionRange(neutral) > Combat.detectionRange(coward);
        report.add("combat/감지범위", range, "용감>중립>겁쟁이",
                String.format("%.0f/%.0f/%.0f", Combat.detectionRange(brave),
                        Combat.detectionRange(neutral), Combat.detectionRange(coward)));
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest feeding — 밤 배치 정산 (설계서 Phase 3, §4)
    // ──────────────────────────────────────────────────────────────
    private static void feeding(Report report) {
        // 1) 분배 순서: 창고가 남편+자식만 감당 → 아내가 먼저 굶음 (남편→자식→아내)
        Feeding.Household h = new Feeding.Household();
        h.father = member(Sex.MALE, LifeStage.ADULT, 1.5, 0.0);   // 소모 1.0
        h.children.add(member(Sex.MALE, LifeStage.BOY, 0.0, 0.0)); // 소모 0.5
        h.wives.add(member(Sex.FEMALE, LifeStage.ADULT, 0.0, 0.0)); // 소모 1.0
        // 창고 = 1.5 → 남편1.0 먹고 0.5 남음 → 자식0.5 먹고 0 → 아내 굶음
        Feeding.Result r = Feeding.settle(h);
        boolean order = r.fed.contains(h.father) && r.fed.contains(h.children.get(0))
                && r.starved.contains(h.wives.get(0))
                && h.wives.get(0).ind.hungerCount() == 1
                && h.father.ind.hungerCount() == 0;
        report.add("feeding/분배순서", order,
                "남편·자식 먹고 아내 굶음",
                "남편 " + fedStr(r, h.father) + " · 자식 " + fedStr(r, h.children.get(0))
                        + " · 아내 " + fedStr(r, h.wives.get(0)));

        // 2) 요리 배율: 요리사 남편이면 창고 유입 ×1.2 → 아내까지 먹임
        Feeding.Household h2 = new Feeding.Household();
        h2.father = member(Sex.MALE, LifeStage.ADULT, 2.1, 0.0,
                TraitInstance.of(Trait.COOK)); // 2.1×1.2=2.52 ≥ 1.0+1.0
        h2.wives.add(member(Sex.FEMALE, LifeStage.ADULT, 0.0, 0.0));
        Feeding.Result r2 = Feeding.settle(h2);
        report.add("feeding/요리배율", r2.fed.contains(h2.wives.get(0)),
                "요리사 유입 ×1.2 → 아내 먹임(2.1→2.52)",
                "아내 " + fedStr(r2, h2.wives.get(0)) + " · 잔량 " + String.format("%.2f", r2.storageLeft));

        // 3) 굶주림 누적 → 사망: 아내 굶주림 2에서 또 굶으면 3 → 사망
        Feeding.Household h3 = new Feeding.Household();
        h3.father = member(Sex.MALE, LifeStage.ADULT, 1.0, 0.0);
        Feeding.Member wife = member(Sex.FEMALE, LifeStage.ADULT, 0.0, 0.0);
        wife.ind.setHungerCount(2); // 이미 이틀 굶음
        h3.wives.add(wife);
        Feeding.Result r3 = Feeding.settle(h3); // 남편만 먹고 아내 굶음 → 3
        boolean death = r3.died.contains(wife) && wife.dead && wife.ind.hungerCount() == 3;
        report.add("feeding/굶주림사망", death,
                "굶주림 3일 연속 → 사망",
                "아내 굶주림 " + wife.ind.hungerCount() + " · 사망 " + yn(wife.dead));

        // 4) 굶주림 리셋: 먹으면 카운트 0으로
        Feeding.Household h4 = new Feeding.Household();
        Feeding.Member m = member(Sex.MALE, LifeStage.ADULT, 2.0, 0.0);
        m.ind.setHungerCount(2);
        h4.father = m;
        Feeding.settle(h4);
        report.add("feeding/리셋", m.ind.hungerCount() == 0,
                "먹으면 굶주림 0 리셋", "굶주림 " + m.ind.hungerCount());
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest lifecycle — 생애단계 능력 + 여성 페널티 (설계서 Phase 3, §7 §1)
    // ──────────────────────────────────────────────────────────────
    private static void lifecycle(Report report) {
        Individual m = one(Sex.MALE);

        // 1) 전투 가능: 성년만
        boolean fight = SurvivalRules.canFight(LifeStage.ADULT)
                && !SurvivalRules.canFight(LifeStage.BOY)
                && !SurvivalRules.canFight(LifeStage.INFANT);
        report.add("lifecycle/전투가능", fight, "성년만 전투",
                "성년 " + yn(SurvivalRules.canFight(LifeStage.ADULT))
                        + " · 소년 " + yn(SurvivalRules.canFight(LifeStage.BOY))
                        + " · 유아 " + yn(SurvivalRules.canFight(LifeStage.INFANT)));

        // 2) 채집 가능: 성년, 또는 만혼 소년
        Individual lateBoy = one(Sex.MALE, TraitInstance.of(Trait.LATE_MARRIAGE));
        boolean gather = SurvivalRules.canGather(LifeStage.ADULT, m)
                && SurvivalRules.canGather(LifeStage.BOY, lateBoy)
                && !SurvivalRules.canGather(LifeStage.BOY, m)
                && !SurvivalRules.canGather(LifeStage.INFANT, m);
        report.add("lifecycle/채집가능", gather, "성년·만혼소년만 채집",
                "성년 O · 만혼소년 " + yn(SurvivalRules.canGather(LifeStage.BOY, lateBoy))
                        + " · 일반소년 " + yn(SurvivalRules.canGather(LifeStage.BOY, m)));

        // 3) 이동속도: 유아 < 소년 < 성년 (유아 거의 정지)
        boolean speed = SurvivalRules.moveSpeedFactor(LifeStage.INFANT)
                < SurvivalRules.moveSpeedFactor(LifeStage.BOY)
                && SurvivalRules.moveSpeedFactor(LifeStage.BOY)
                < SurvivalRules.moveSpeedFactor(LifeStage.ADULT)
                && SurvivalRules.moveSpeedFactor(LifeStage.INFANT) <= 0.1;
        report.add("lifecycle/이동속도", speed, "유아<소년<성년 (유아≤0.1)",
                String.format("%.2f/%.2f/%.2f", SurvivalRules.moveSpeedFactor(LifeStage.INFANT),
                        SurvivalRules.moveSpeedFactor(LifeStage.BOY),
                        SurvivalRules.moveSpeedFactor(LifeStage.ADULT)));

        // 4) 유아: 자가 섭취·자가 이동 불가
        boolean infantHelpless = !SurvivalRules.canSelfFeed(LifeStage.INFANT)
                && !SurvivalRules.canMoveSelf(LifeStage.INFANT)
                && SurvivalRules.canSelfFeed(LifeStage.BOY)
                && SurvivalRules.canMoveSelf(LifeStage.ADULT);
        report.add("lifecycle/유아무력", infantHelpless, "유아 자가섭취·이동 불가",
                infantHelpless ? "정상" : "어긋남");

        // 5) 여성 페널티: 신체 40% 약함 (0.6배)
        boolean female = Math.abs(SurvivalRules.physicalFactor(Sex.FEMALE) - 0.6) < 1e-9
                && Math.abs(SurvivalRules.physicalFactor(Sex.MALE) - 1.0) < 1e-9;
        report.add("lifecycle/여성페널티", female, "여성 신체 0.6배 (40%↓)",
                String.format("여 %.2f · 남 %.2f", SurvivalRules.physicalFactor(Sex.FEMALE),
                        SurvivalRules.physicalFactor(Sex.MALE)));
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest lifespan — 세대 기반 수명 + 상속 (설계서 Phase 3, §9)
    // ──────────────────────────────────────────────────────────────
    private static void lifespan(Report report) {
        // 부모(1), 자식(2,3), 손자(4) 계보 구성.
        Lifespan.Being parent = new Lifespan.Being(1, 0, 0, LifeStage.ADULT, true);
        Lifespan.Being childAdult1 = new Lifespan.Being(2, 1, 0, LifeStage.ADULT, true);
        Lifespan.Being childAdult2 = new Lifespan.Being(3, 1, 0, LifeStage.ADULT, false);
        Lifespan.Being childBoy = new Lifespan.Being(3, 1, 0, LifeStage.BOY, false);
        Lifespan.Being grandchild = new Lifespan.Being(4, 2, 0, LifeStage.INFANT, false);

        // 1) 손자 존재 + 자식 모두 성년 → 사망
        boolean die = Lifespan.shouldDie(parent,
                java.util.List.of(parent, childAdult1, childAdult2, grandchild));
        report.add("lifespan/자연사", die, "손자 존재+자식 성년 → 사망", yn(die));

        // 2) 자식 미성년 → 생존
        boolean surviveBoy = !Lifespan.shouldDie(parent,
                java.util.List.of(parent, childAdult1, childBoy, grandchild));
        report.add("lifespan/미성년생존", surviveBoy, "자식 미성년 → 생존", yn(surviveBoy));

        // 3) 자식 성년이나 손자 없음 → 생존
        boolean surviveNoGc = !Lifespan.shouldDie(parent,
                java.util.List.of(parent, childAdult1, childAdult2));
        report.add("lifespan/손자없음생존", surviveNoGc, "손자 없음 → 생존", yn(surviveNoGc));

        // 4) 번식했으나 자식 전멸 → 부모도 사망 (영생 구멍 차단)
        boolean dieNoHeir = Lifespan.shouldDie(parent, java.util.List.of(parent));
        report.add("lifespan/자식전멸", dieNoHeir, "번식 후 자식 전멸 → 부모 사망", yn(dieNoHeir));

        // 5) 번식한 적 없는 방랑자 → 이 판정으로는 생존
        Lifespan.Being wanderer = new Lifespan.Being(9, 0, 0, LifeStage.ADULT, false);
        boolean wandererLives = !Lifespan.shouldDie(wanderer, java.util.List.of(wanderer));
        report.add("lifespan/방랑자생존", wandererLives, "미번식 방랑자 → 생존", yn(wandererLives));

        // 6) 상속: 남은 자 있으면 1명분(2.5)만, 없으면 소멸
        boolean inherit = Math.abs(Lifespan.inheritAmount(10.0, true) - 2.5) < 1e-9
                && Math.abs(Lifespan.inheritAmount(1.0, true) - 1.0) < 1e-9
                && Lifespan.inheritAmount(10.0, false) == 0.0;
        report.add("lifespan/상속", inherit, "남은 자 1명분만·없으면 소멸",
                "10→" + Lifespan.inheritAmount(10.0, true) + " · 없음→"
                        + Lifespan.inheritAmount(10.0, false));
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest mating — 조우 판정 + 기준선 (설계서 Phase 4, §10)
    // ──────────────────────────────────────────────────────────────
    private static void mating(Report report) {
        // 1) 시작 기준선: 여=신중(3), 남=널널(1), 엄격=4, 개방=0
        boolean base = Mating.startingBaseline(one(Sex.FEMALE)) == Mating.PRUDENT
                && Mating.startingBaseline(one(Sex.MALE)) == Mating.LOOSE
                && Mating.startingBaseline(one(Sex.MALE, TraitInstance.of(Trait.STRICT_MATE))) == Mating.STRICT
                && Mating.startingBaseline(one(Sex.FEMALE, TraitInstance.of(Trait.OPEN_MATE))) == Mating.OPEN;
        report.add("mating/기준선", base, "여신중·남널널·엄격4·개방0",
                base ? "정상" : "어긋남");

        // 서로 상대의 선호를 만족(강함선호 ↔ 힘센) → 매력 상호 1점.
        Individual m = one(Sex.MALE, TraitInstance.of(Trait.PREF_STRENGTH), TraitInstance.of(Trait.STRONG));
        Individual f = one(Sex.FEMALE, TraitInstance.of(Trait.PREF_STRENGTH), TraitInstance.of(Trait.STRONG));

        // 2) 상호 매력 충분 + 기준선 낮음 → 짝 성립
        report.add("mating/성립", Mating.encounter(m, Mating.LOOSE, f, Mating.LOOSE) == Mating.Outcome.PAIR,
                "상호 매력≥기준선 → PAIR",
                Mating.encounter(m, Mating.LOOSE, f, Mating.LOOSE).toString());

        // 3) 내 매력이 상대 기준선 미달 → 거절당함(REJECTED)
        Individual plain = one(Sex.MALE, TraitInstance.of(Trait.PREF_STRENGTH)); // 힘센 없음 → 매력 0
        report.add("mating/거절", Mating.encounter(plain, Mating.LOOSE, f, Mating.STRICT) == Mating.Outcome.REJECTED,
                "내매력<상대기준선 → REJECTED",
                Mating.encounter(plain, Mating.LOOSE, f, Mating.STRICT).toString());

        // 4) 상대는 받아주나 내가 까다로워 컷(CUT)
        Individual picky = one(Sex.MALE, TraitInstance.of(Trait.PREF_STRENGTH)); // 상대(개방) 매력 0
        Individual openF = one(Sex.FEMALE, TraitInstance.of(Trait.OPEN_MATE));   // 힘센 없음
        report.add("mating/컷", Mating.encounter(picky, Mating.STRICT, openF, Mating.OPEN) == Mating.Outcome.CUT,
                "상대 수락·내가 컷 → CUT",
                Mating.encounter(picky, Mating.STRICT, openF, Mating.OPEN).toString());

        // 5) 거절당할 때만 눈 낮춤 → 기준선 0으로 하락 → 첫 상대와 성립 (멸종 방지 메커니즘)
        Individual self = one(Sex.MALE, TraitInstance.of(Trait.PREF_STRENGTH)); // 매력 0(까다로운 상대에겐)
        Individual pickyF = one(Sex.FEMALE, TraitInstance.of(Trait.PREF_STRENGTH)); // self 매력 안 봄
        int sB = Mating.STRICT;
        int r = 0;
        for (; sB > Mating.OPEN && r < 10; r++) {
            if (Mating.encounter(self, sB, pickyF, Mating.STRICT) == Mating.Outcome.REJECTED) {
                sB = Mating.lowerBaseline(sB);
            }
        }
        Individual anyF = one(Sex.FEMALE, TraitInstance.of(Trait.OPEN_MATE));
        boolean paired = Mating.encounter(self, sB, anyF, Mating.OPEN) == Mating.Outcome.PAIR;
        report.add("mating/눈낮춤", sB == Mating.OPEN && paired,
                "거절 누적 → 기준선 0 → 첫 상대와 성립",
                "기준선 " + sB + " · " + (paired ? "성립" : "실패"));

        // 5-b) 반복 수렴(in-world 알고리즘): 양쪽이 각자 눈을 낮추며 후보를 다시 시도.
        //   ① 호환쌍(상호 매력 3)은 시작 기준선에서 즉시 성립 → "너무 어렵지 않다".
        Individual highM = one(Sex.MALE,
                TraitInstance.of(Trait.PREF_STRENGTH), TraitInstance.of(Trait.PREF_ABILITY),
                TraitInstance.of(Trait.PREF_VITALITY), TraitInstance.of(Trait.STRONG),
                TraitInstance.of(Trait.BRIGHT), TraitInstance.of(Trait.NIMBLE));
        Individual highF = one(Sex.FEMALE,
                TraitInstance.of(Trait.PREF_STRENGTH), TraitInstance.of(Trait.PREF_ABILITY),
                TraitInstance.of(Trait.PREF_VITALITY), TraitInstance.of(Trait.STRONG),
                TraitInstance.of(Trait.BRIGHT), TraitInstance.of(Trait.NIMBLE));
        boolean easyCompatible = Mating.encounter(highM, Mating.startingBaseline(highM),
                highF, Mating.startingBaseline(highF)) == Mating.Outcome.PAIR;

        //   ② 비호환쌍(상호 매력 0)은 시작 기준선엔 성립 X(너무 쉽지 않음) → 반복 눈낮춤으로 결국 성립.
        Individual zm = one(Sex.MALE);
        Individual zf = one(Sex.FEMALE);
        int zmB = Mating.startingBaseline(zm);   // 남 널널(1)
        int zfB = Mating.startingBaseline(zf);   // 여 신중(3)
        boolean instant = Mating.encounter(zm, zmB, zf, zfB) == Mating.Outcome.PAIR;
        int rounds = 0;
        boolean converged = false;
        for (; rounds < 30; rounds++) {
            if (Mating.encounter(zm, zmB, zf, zfB) == Mating.Outcome.PAIR) {
                converged = true;
                break;
            }
            zmB = Mating.lowerBaseline(zmB); // 남 눈낮춤
            if (Mating.encounter(zf, zfB, zm, zmB) == Mating.Outcome.PAIR) {
                converged = true;
                break;
            }
            zfB = Mating.lowerBaseline(zfB); // 여 눈낮춤
        }
        boolean balanced = easyCompatible && !instant && converged && rounds >= 2 && rounds <= 12;
        report.add("mating/수렴", balanced,
                "호환쌍 즉시 성립·비호환쌍은 즉시X·눈낮춤 반복으로 유한 수렴",
                "호환 " + yn(easyCompatible) + " · 비호환 즉시 " + yn(instant)
                        + " · " + rounds + "R 성립 " + yn(converged));

        // 6) 근친 회피(§13-E): 형제(부모 공유)·부모자식 회피, 사촌 허용, 1세대(부모 미상) 오탐 X
        Individual sibA = new Individual(2, Sex.MALE, 1, 5, 2);
        Individual sibB = new Individual(3, Sex.FEMALE, 1, 6, 2);   // 부모A(1) 공유 → 형제
        Individual kid = new Individual(7, Sex.FEMALE, 2, 9, 3);    // sibA(2)의 자식
        Individual cousin = new Individual(4, Sex.FEMALE, 8, 9, 2); // 부모 다름 → 사촌
        Individual g1a = new Individual(10, Sex.MALE, 0, 0, 1);
        Individual g1b = new Individual(11, Sex.FEMALE, 0, 0, 1);   // 둘 다 부모 미상(0)
        boolean kin = Kinship.isRelated(sibA, sibB)
                && Kinship.isRelated(sibA, kid)
                && !Kinship.isRelated(sibA, cousin)
                && !Kinship.isRelated(g1a, g1b); // 1세대는 부모 0 공유해도 근친 아님
        report.add("mating/근친회피", kin, "형제·부모자식 회피·사촌/1세대 허용",
                kin ? "정상" : "어긋남");
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest settlement — 거처 배치 (설계서 Phase 4, §13-D)
    // ──────────────────────────────────────────────────────────────
    private static void settlement(Report report) {
        Individual mig = one(Sex.MALE, TraitInstance.of(Trait.MIGRATORY));
        Individual home = one(Sex.MALE, TraitInstance.of(Trait.HOMEBOUND));
        Individual neu = one(Sex.MALE);

        // 1) 조합별 거리: 이주×이주 멀리·애향×애향 가까이·중립 기본·이주×애향 상쇄
        boolean dist = Settlement.homeDistance(mig, mig) == Settlement.BASE_DISTANCE * 2
                && Settlement.homeDistance(home, home) == Settlement.BASE_DISTANCE / 2
                && Settlement.homeDistance(neu, neu) == Settlement.BASE_DISTANCE
                && Settlement.homeDistance(mig, home) == Settlement.BASE_DISTANCE   // 상쇄
                && Settlement.homeDistance(neu, mig) == Settlement.BASE_DISTANCE * 2;
        report.add("settlement/거리", dist, "이주멀리·애향가까이·상쇄기본",
                String.format("이주×이주 %d · 애향×애향 %d · 상쇄 %d",
                        Settlement.homeDistance(mig, mig), Settlement.homeDistance(home, home),
                        Settlement.homeDistance(mig, home)));

        // 2) 비겹침: 마을에 거처 20개를 순차 배치 → 모든 쌍이 최소 간격 이상
        DeterministicRng rng = new DeterministicRng(4242L);
        List<int[]> homes = new ArrayList<>();
        homes.add(new int[] {0, 0});
        for (int i = 0; i < 20; i++) {
            int[] parent = homes.get(rng.nextInt(homes.size()));
            int[] pos = Settlement.placeHome(parent, Settlement.BASE_DISTANCE, homes, Settlement.MIN_GAP, rng);
            homes.add(pos);
        }
        int violations = 0;
        for (int i = 0; i < homes.size(); i++) {
            for (int j = i + 1; j < homes.size(); j++) {
                long dx = homes.get(i)[0] - homes.get(j)[0];
                long dz = homes.get(i)[1] - homes.get(j)[1];
                if (dx * dx + dz * dz < (long) Settlement.MIN_GAP * Settlement.MIN_GAP) {
                    violations++;
                }
            }
        }
        report.add("settlement/비겹침", violations == 0,
                "거처 " + homes.size() + "개 최소간격≥" + Settlement.MIN_GAP,
                violations + "건 겹침");
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest reproduction — 번식 임계치 + 출산 상한 (설계서 Phase 4, §6)
    // ──────────────────────────────────────────────────────────────
    private static void reproduction(Report report) {
        Individual m = one(Sex.MALE);
        Individual f = one(Sex.FEMALE);

        // 1) 임계치 보정: 기본 2.5, 번식선호 −1/−2, 번식불호 +1/+6
        boolean thr = Reproduction.threshold(m, f) == 2.5
                && Reproduction.threshold(one(Sex.MALE, TraitInstance.of(Trait.REPRODUCTION_EAGER)), f) == 1.5
                && Reproduction.threshold(one(Sex.MALE, TraitInstance.of(Trait.REPRODUCTION_EAGER)),
                        one(Sex.FEMALE, TraitInstance.of(Trait.REPRODUCTION_EAGER))) == 0.5
                && Reproduction.threshold(one(Sex.MALE, TraitInstance.of(Trait.REPRODUCTION_AVERSE)), f) == 3.5
                && Reproduction.threshold(one(Sex.MALE, TraitInstance.of(Trait.REPRODUCTION_AVERSE)),
                        one(Sex.FEMALE, TraitInstance.of(Trait.REPRODUCTION_AVERSE))) == 8.5;
        report.add("reproduction/임계치", thr, "기본2.5·선호−·불호+(둘 +6)",
                "기본 " + Reproduction.threshold(m, f) + " · 불호둘 "
                        + Reproduction.threshold(one(Sex.MALE, TraitInstance.of(Trait.REPRODUCTION_AVERSE)),
                                one(Sex.FEMALE, TraitInstance.of(Trait.REPRODUCTION_AVERSE))));

        // 2) 출산 상한: 기본 5, 다산 여+2/남+1/둘+3, 난임 여−1/남−2/둘−3
        boolean lim = Reproduction.birthLimit(f, m) == 5
                && Reproduction.birthLimit(one(Sex.FEMALE, TraitInstance.of(Trait.PROLIFIC)), m) == 7
                && Reproduction.birthLimit(f, one(Sex.MALE, TraitInstance.of(Trait.PROLIFIC))) == 6
                && Reproduction.birthLimit(one(Sex.FEMALE, TraitInstance.of(Trait.PROLIFIC)),
                        one(Sex.MALE, TraitInstance.of(Trait.PROLIFIC))) == 8
                && Reproduction.birthLimit(one(Sex.FEMALE, TraitInstance.of(Trait.INFERTILE)), m) == 4
                && Reproduction.birthLimit(f, one(Sex.MALE, TraitInstance.of(Trait.INFERTILE))) == 3
                && Reproduction.birthLimit(one(Sex.FEMALE, TraitInstance.of(Trait.INFERTILE)),
                        one(Sex.MALE, TraitInstance.of(Trait.INFERTILE))) == 2;
        report.add("reproduction/출산상한", lim, "기본5·다산+·난임−",
                "기본 " + Reproduction.birthLimit(f, m) + " · 다산둘 "
                        + Reproduction.birthLimit(one(Sex.FEMALE, TraitInstance.of(Trait.PROLIFIC)),
                                one(Sex.MALE, TraitInstance.of(Trait.PROLIFIC))));

        // 3) 잉여 ≥ 임계 → 번식
        boolean can = Reproduction.canReproduce(3.0, 2.5) && !Reproduction.canReproduce(2.0, 2.5);
        report.add("reproduction/잉여판정", can, "잉여≥임계 → 번식", can ? "정상" : "어긋남");
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest parenting — 육아 적극성 5단계 클래스 (설계서 육아 클래스)
    // ──────────────────────────────────────────────────────────────
    private static void parenting(Report report) {
        // 1) 돌봄 반경 단계: 적극 < 소극 < 평범 < 무심 < 무시
        boolean order = ParentingClass.DEVOTED.careRadius() < ParentingClass.CARING.careRadius()
                && ParentingClass.CARING.careRadius() < ParentingClass.MODERATE.careRadius()
                && ParentingClass.MODERATE.careRadius() < ParentingClass.DETACHED.careRadius()
                && ParentingClass.DETACHED.careRadius() < ParentingClass.NEGLECTFUL.careRadius()
                && ParentingClass.DEVOTED.careRadius() == 0.0;
        report.add("parenting/반경단계", order, "적극0<소극<평범<무심<무시",
                String.format("0/%.0f/%.0f/%.0f/∞", ParentingClass.CARING.careRadius(),
                        ParentingClass.MODERATE.careRadius(), ParentingClass.DETACHED.careRadius()));

        // 2) 저녁 배회: 무시만
        boolean evening = ParentingClass.NEGLECTFUL.eveningWander()
                && !ParentingClass.DEVOTED.eveningWander()
                && !ParentingClass.DETACHED.eveningWander();
        report.add("parenting/저녁배회", evening, "무시만 저녁 배회·산책",
                evening ? "정상" : "어긋남");

        // 3) 5단계 정확히 (적극/소극/평범/무심/무시)
        report.add("parenting/5단계", ParentingClass.values().length == 5,
                "클래스 5개", ParentingClass.values().length + "개");

        // 4) 남녀발현 세트 + 성별 발동: M=무심·F=적극 개체는 남이면 무심, 여면 적극
        Individual maleInd = new Individual(1, Sex.MALE, 0, 0, 1);
        maleInd.setParentingCareMale(ParentingClass.DETACHED);
        maleInd.setParentingCareFemale(ParentingClass.DEVOTED);
        Individual femInd = new Individual(2, Sex.FEMALE, 0, 0, 1);
        femInd.setParentingCareMale(ParentingClass.DETACHED);
        femInd.setParentingCareFemale(ParentingClass.DEVOTED);
        boolean sexExpr = maleInd.parentingCare() == ParentingClass.DETACHED
                && femInd.parentingCare() == ParentingClass.DEVOTED;
        report.add("parenting/성별발동", sexExpr, "세트 중 성별 쪽 발동(남=M·여=F)",
                "남 " + maleInd.parentingCare().label() + " · 여 " + femInd.parentingCare().label());

        // 5) 세트 유전: 부모A(남적극·여적극), 부모B(남무시·여무시) → 각 슬롯 독립 유전
        //    → '한쪽 전부'(둘 다 한 부모)와 '섞이기'(남·여 다른 부모) 모두 발생, 각 슬롯 부모유래 >80%
        DeterministicRng rng = new DeterministicRng(7777L);
        Individual dad = Genetics.randomFirstGen(1, rng);
        Individual mom = Genetics.randomFirstGen(2, rng);
        dad.setParentingCareMale(ParentingClass.DEVOTED);
        dad.setParentingCareFemale(ParentingClass.DEVOTED);
        mom.setParentingCareMale(ParentingClass.NEGLECTFUL);
        mom.setParentingCareFemale(ParentingClass.NEGLECTFUL);
        int mFromParent = 0;
        int fFromParent = 0;
        int wholeCount = 0;
        int mixedCount = 0;
        int n = 4000;
        for (int i = 0; i < n; i++) {
            Individual c = Genetics.breed(100 + i, dad, mom, rng, 2, null);
            ParentingClass cm = c.parentingCareMale();
            ParentingClass cf = c.parentingCareFemale();
            boolean mOk = cm == ParentingClass.DEVOTED || cm == ParentingClass.NEGLECTFUL;
            boolean fOk = cf == ParentingClass.DEVOTED || cf == ParentingClass.NEGLECTFUL;
            if (mOk) {
                mFromParent++;
            }
            if (fOk) {
                fFromParent++;
            }
            if (mOk && fOk) {
                if (cm == cf) {
                    wholeCount++; // 한쪽 전부(둘 다 DEVOTED 또는 둘 다 NEGLECTFUL)
                } else {
                    mixedCount++; // 섞이기
                }
            }
        }
        double mRate = (double) mFromParent / n;
        double fRate = (double) fFromParent / n;
        boolean inherit = mRate > 0.80 && fRate > 0.80 && wholeCount > 0 && mixedCount > 0;
        report.add("parenting/세트유전", inherit,
                "슬롯 독립 유전(>80%)·전부&섞이기 모두 발생",
                "남유래 " + pct(mRate) + " · 여유래 " + pct(fRate)
                        + " · 전부 " + wholeCount + " · 섞이기 " + mixedCount);
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest cycle — 하루 사이클: 밤 정산 → 잉여 → 번식 게이트 (설계서 §4 §6)
    //   식량이 확보되어야만 번식이 해금된다는 단일 판정을 헤드리스로 검증.
    // ──────────────────────────────────────────────────────────────
    private static void cycle(Report report) {
        // 1) 풍족: 부부가 넉넉히 수확 → 먹고도 잉여≥임계(2.5) → 번식 해금
        Feeding.Household rich = new Feeding.Household();
        rich.father = member(Sex.MALE, LifeStage.ADULT, 3.5, 0.0);
        rich.wives.add(member(Sex.FEMALE, LifeStage.ADULT, 3.5, 0.0));
        DailyCycle.DayResult r1 = DailyCycle.settleFamily(rich);
        boolean richOk = r1.reproductionUnlocked && r1.surplus >= Reproduction.BASE_THRESHOLD
                && r1.feeding.starved.isEmpty();
        report.add("cycle/풍족번식", richOk,
                "잉여≥임계 → 번식 해금·아무도 안 굶음",
                "잉여 " + String.format("%.1f", r1.surplus) + " · 번식 " + yn(r1.reproductionUnlocked));

        // 2) 근근이 생존: 먹을 만큼만 → 굶진 않지만 잉여 부족 → 번식 막힘
        Feeding.Household lean = new Feeding.Household();
        lean.father = member(Sex.MALE, LifeStage.ADULT, 1.5, 0.0);
        lean.wives.add(member(Sex.FEMALE, LifeStage.ADULT, 1.0, 0.0)); // 창고 2.5, 소모 2.0 → 잉여 0.5
        DailyCycle.DayResult r2 = DailyCycle.settleFamily(lean);
        boolean leanOk = !r2.reproductionUnlocked && r2.feeding.starved.isEmpty()
                && r2.surplus < Reproduction.BASE_THRESHOLD;
        report.add("cycle/근근번식막힘", leanOk,
                "먹고 살지만 잉여 부족 → 번식 안 함",
                "잉여 " + String.format("%.1f", r2.surplus) + " · 번식 " + yn(r2.reproductionUnlocked));

        // 3) 흉년: 수확 0 → 부부 굶주림 누적 + 번식 완전 차단
        Feeding.Household famine = new Feeding.Household();
        famine.father = member(Sex.MALE, LifeStage.ADULT, 0.0, 0.0);
        famine.wives.add(member(Sex.FEMALE, LifeStage.ADULT, 0.0, 0.0));
        DailyCycle.DayResult r3 = DailyCycle.settleFamily(famine);
        boolean famineOk = !r3.reproductionUnlocked && r3.feeding.starved.size() == 2
                && famine.father.ind.hungerCount() == 1;
        report.add("cycle/흉년번식차단", famineOk,
                "수확 0 → 굶주림↑·번식 차단",
                "굶은이 " + r3.feeding.starved.size() + " · 번식 " + yn(r3.reproductionUnlocked));

        // 4) 자식 우선: 창고가 남편+자식만 감당 → 아내 굶고 잉여 없음(번식 막힘)
        Feeding.Household withKid = new Feeding.Household();
        withKid.father = member(Sex.MALE, LifeStage.ADULT, 2.0, 0.0);
        withKid.children.add(member(Sex.MALE, LifeStage.BOY, 0.0, 0.0)); // 소모 0.5
        withKid.wives.add(member(Sex.FEMALE, LifeStage.ADULT, 0.0, 0.0));
        DailyCycle.DayResult r4 = DailyCycle.settleFamily(withKid);
        boolean kidOk = r4.feeding.fed.contains(withKid.children.get(0))
                && r4.feeding.starved.contains(withKid.wives.get(0))
                && !r4.reproductionUnlocked;
        report.add("cycle/자식우선", kidOk,
                "창고 부족 → 자식 먼저·아내 굶음·번식 막힘",
                "자식 " + fedStr(r4.feeding, withKid.children.get(0))
                        + " · 아내 " + fedStr(r4.feeding, withKid.wives.get(0)));

        // 5) 연속 흉년 → 사망: 이미 이틀 굶은 아내가 또 굶으면 3일 → 사망(부부 해체)
        Feeding.Household starve = new Feeding.Household();
        starve.father = member(Sex.MALE, LifeStage.ADULT, 1.0, 0.0);
        Feeding.Member wife = member(Sex.FEMALE, LifeStage.ADULT, 0.0, 0.0);
        wife.ind.setHungerCount(2);
        starve.wives.add(wife);
        DailyCycle.DayResult r5 = DailyCycle.settleFamily(starve);
        boolean deathOk = r5.feeding.died.contains(wife) && wife.dead && !r5.reproductionUnlocked;
        report.add("cycle/연속흉년사망", deathOk,
                "3일 연속 굶음 → 아내 사망·번식 불가",
                "아내 굶주림 " + wife.ind.hungerCount() + " · 사망 " + yn(wife.dead));

        // 6) 홀몸 방랑자 자급: 거처·짝 없는 성년은 스스로 수확해 자기부터 먹는다(번식은 당연히 없음)
        Feeding.Household lone = new Feeding.Household();
        lone.father = member(Sex.MALE, LifeStage.ADULT, 2.0, 0.0);
        DailyCycle.DayResult r6 = DailyCycle.settleFamily(lone);
        boolean loneOk = r6.feeding.fed.contains(lone.father) && !r6.reproductionUnlocked;
        report.add("cycle/홀몸자급", loneOk,
                "독신 성년 자급자족·번식 없음",
                "본인 " + fedStr(r6.feeding, lone.father) + " · 번식 " + yn(r6.reproductionUnlocked));
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest courtship — 수락 판정 베이지안 공식 (구애 사양서 v2 §3, §6)
    //   q̂=(better+k·q0)/(n+k), P=1-q̂. 분모 항상 (n+k), q0=0.5.
    // ──────────────────────────────────────────────────────────────
    private static void courtship(Report report) {
        // §6 k=2(보통)
        boolean k2 = close(Courtship.acceptProbability(1, 3, 2), 0.60)          // 2/5
                && close(Courtship.acceptProbability(1, 20, 2), 1.0 - 2.0 / 22.0) // ≈0.909
                && close(Courtship.acceptProbability(0, 0, 2), 0.50);            // 1/2
        report.add("courtship/k2", k2,
                "n3b1→60% · n20b1→91% · n0→50%",
                pct(Courtship.acceptProbability(1, 3, 2)) + " · "
                        + pct(Courtship.acceptProbability(1, 20, 2)) + " · "
                        + pct(Courtship.acceptProbability(0, 0, 2)));

        // §6 k=0(완전개방): 1등이면 100%, 후보0이면 50%
        boolean k0 = close(Courtship.acceptProbability(0, 3, 0), 1.00)
                && close(Courtship.acceptProbability(0, 0, 0), 0.50); // n+k==0 → q0
        report.add("courtship/k0", k0,
                "1등→100% · 후보0→50%",
                pct(Courtship.acceptProbability(0, 3, 0)) + " · "
                        + pct(Courtship.acceptProbability(0, 0, 0)));

        // §6 k=1(널널, 오타 정정): n1 b1 → q̂=1.5/2=0.75 → P=25%
        boolean k1 = close(Courtship.acceptProbability(1, 1, 1), 0.25);
        report.add("courtship/k1", k1, "n1b1 → 25% (사양 오타 정정)",
                pct(Courtship.acceptProbability(1, 1, 1)));

        // §6 k=8(엄격): n20 1등이어도 100% 미달(≈86%) — 의도된 동작
        boolean k8 = close(Courtship.acceptProbability(0, 20, 8), 1.0 - 4.0 / 28.0);
        report.add("courtship/k8", k8, "엄격 n20 1등 → 표본 커도 ≈86% (100% 미달)",
                pct(Courtship.acceptProbability(0, 20, 8)));

        // 경계: 동점(==)은 better에 미포함(엄격히 >) → C에게 유리
        int better = Courtship.betterCount(new int[] {3, 2, 2}, 2);
        report.add("courtship/동점제외", better == 1,
                "후보 [3,2,2]에서 매력 2인 구애자보다 나은 후보 = 1(동점 2는 제외)",
                "better=" + better);

        // 경계: q̂>1 이면 P는 0으로 클램프
        boolean clamp = close(Courtship.acceptProbability(10, 10, 0), 0.0);
        report.add("courtship/클램프", clamp, "q̂>1 → P=0",
                pct(Courtship.acceptProbability(10, 10, 0)));

        // 밸런싱 스케일: 실전 수락 = 공식값 × ACCEPT_SCALE (전체 살짝 하향)
        boolean scaled = close(Courtship.acceptChance(1, 3, 2),
                Courtship.acceptProbability(1, 3, 2) * Courtship.ACCEPT_SCALE)
                && Courtship.acceptChance(1, 3, 2) < Courtship.acceptProbability(1, 3, 2);
        report.add("courtship/스케일", scaled,
                "실전 수락 = 공식 × " + Courtship.ACCEPT_SCALE + " (하향)",
                pct(Courtship.acceptChance(1, 3, 2)) + " (원식 " + pct(Courtship.acceptProbability(1, 3, 2)) + ")");
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest matechoice — 짝 선정 까다로움 클래스 (5단계·성별발동·슬롯 독립 유전)
    // ──────────────────────────────────────────────────────────────
    private static void matechoice(Report report) {
        // 1) 테이블: 5단계 k=0/1/2/4/8 · 중립=보통
        boolean table = MateChoiceClass.values().length == 5
                && MateChoiceClass.OPEN.k() == 0 && MateChoiceClass.LOOSE.k() == 1
                && MateChoiceClass.MODERATE.k() == 2 && MateChoiceClass.PRUDENT.k() == 4
                && MateChoiceClass.STRICT.k() == 8
                && MateChoiceClass.NEUTRAL == MateChoiceClass.MODERATE;
        report.add("matechoice/테이블", table, "5단계 k=0/1/2/4/8·중립=보통",
                table ? "정상" : "어긋남");

        // 2) 성별 발동: 남=남슬롯, 여=여슬롯
        Individual male = new Individual(1, Sex.MALE, 0, 0, 1);
        male.setMateChoiceMale(MateChoiceClass.STRICT);
        male.setMateChoiceFemale(MateChoiceClass.OPEN);
        Individual female = new Individual(2, Sex.FEMALE, 0, 0, 1);
        female.setMateChoiceMale(MateChoiceClass.STRICT);
        female.setMateChoiceFemale(MateChoiceClass.OPEN);
        boolean sexExpr = male.mateChoice() == MateChoiceClass.STRICT
                && female.mateChoice() == MateChoiceClass.OPEN;
        report.add("matechoice/성별발동", sexExpr,
                "세트 중 성별 쪽 발동(남=엄격·여=완전개방)",
                "남 " + male.mateChoice().label() + " · 여 " + female.mateChoice().label());

        // 3) 슬롯 독립 유전: A(남엄격·여개방) × B(남개방·여엄격) 3000회 → 슬롯별 부모 유래 >80%,
        //    남·여 조합 4종 모두 발생(독립 유전 증거)
        Individual a = new Individual(10, Sex.MALE, 0, 0, 1);
        a.setMateChoiceMale(MateChoiceClass.STRICT);
        a.setMateChoiceFemale(MateChoiceClass.OPEN);
        Individual b = new Individual(11, Sex.FEMALE, 0, 0, 1);
        b.setMateChoiceMale(MateChoiceClass.OPEN);
        b.setMateChoiceFemale(MateChoiceClass.STRICT);
        DeterministicRng rng = new DeterministicRng(20240613L);
        int n = 3000;
        int maleFromParent = 0;
        int femaleFromParent = 0;
        int bothA = 0;
        int bothB = 0;
        int mixSS = 0; // 남엄격·여엄격
        int mixOO = 0; // 남개방·여개방
        for (int i = 0; i < n; i++) {
            Individual c = Genetics.breed(100 + i, a, b, rng, 2, null);
            MateChoiceClass cm = c.mateChoiceMale();
            MateChoiceClass cf = c.mateChoiceFemale();
            if (cm == MateChoiceClass.STRICT || cm == MateChoiceClass.OPEN) {
                maleFromParent++;
            }
            if (cf == MateChoiceClass.OPEN || cf == MateChoiceClass.STRICT) {
                femaleFromParent++;
            }
            if (cm == MateChoiceClass.STRICT && cf == MateChoiceClass.OPEN) {
                bothA++;
            } else if (cm == MateChoiceClass.OPEN && cf == MateChoiceClass.STRICT) {
                bothB++;
            } else if (cm == MateChoiceClass.STRICT && cf == MateChoiceClass.STRICT) {
                mixSS++;
            } else if (cm == MateChoiceClass.OPEN && cf == MateChoiceClass.OPEN) {
                mixOO++;
            }
        }
        double mRate = (double) maleFromParent / n;
        double fRate = (double) femaleFromParent / n;
        boolean inherit = mRate > 0.80 && fRate > 0.80
                && bothA > 0 && bothB > 0 && mixSS > 0 && mixOO > 0;
        report.add("matechoice/슬롯유전", inherit,
                "슬롯 독립 유전(>80%)·조합 4종 모두 발생",
                "남유래 " + pct(mRate) + " · 여유래 " + pct(fRate)
                        + " · 전부A " + bothA + " 전부B " + bothB + " 섞SS " + mixSS + " 섞OO " + mixOO);
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    private static Feeding.Member member(Sex sex, LifeStage stage, double harvest, double activity,
                                         TraitInstance... traits) {
        return new Feeding.Member(one(sex, traits), stage, harvest, activity);
    }

    private static String fedStr(Feeding.Result r, Feeding.Member m) {
        return r.fed.contains(m) ? "먹음" : "굶음";
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
        int maxHeldTotal = 0;
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
