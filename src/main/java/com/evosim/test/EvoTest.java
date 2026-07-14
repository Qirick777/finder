package com.evosim.test;

import com.evosim.core.BehaviorDecision;
import com.evosim.core.BreedStats;
import com.evosim.core.Category;
import com.evosim.core.Activity;
import com.evosim.core.Elder;
import com.evosim.core.Famine;
import com.evosim.core.BerryEconomy;
import com.evosim.core.FoodEconomy;
import com.evosim.core.Polygyny;
import com.evosim.core.Combat;
import com.evosim.core.Courtship;
import com.evosim.core.DailyCycle;
import com.evosim.core.DeterministicRng;
import com.evosim.core.ExpressionResolver;
import com.evosim.core.Feeding;
import com.evosim.core.Genetics;
import com.evosim.core.Individual;
import com.evosim.core.Kinship;
import com.evosim.core.Lineage;
import com.evosim.core.FarmLayout;
import com.evosim.core.FarmEconomy;
import com.evosim.core.LifeStage;
import com.evosim.core.Lifespan;
import com.evosim.core.Mating;
import com.evosim.core.MateChoiceClass;
import com.evosim.core.HomeResolution;
import com.evosim.core.MateHome;
import com.evosim.core.Multipliers;
import com.evosim.core.ParentingClass;
import com.evosim.core.Physique;
import com.evosim.core.Reproduction;
import com.evosim.core.Roaming;
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
            case "matehome" -> matehome(report);
            case "homeresolution" -> homeresolution(report);
            case "physique" -> physique(report);
            case "roaming" -> roaming(report);
            case "ability" -> ability(report);
            case "berry" -> berry(report);
            case "food" -> food(report);
            case "famine" -> famine(report);
            case "traitfx" -> traitfx(report);
            case "polygyny" -> polygyny(report);
            case "elder" -> elder(report);
            case "lineage" -> lineage(report);
            case "farm" -> farm(report);
            case "all" -> all(report);
            default -> report.add("evotest", false,
                    "genetics | traits | multiplier | simulate | combat | feeding | lifecycle | lifespan | mating | settlement | reproduction | parenting | cycle | courtship | matechoice | matehome | homeresolution | physique | roaming | ability | berry | food | famine | traitfx | polygyny | elder | lineage | farm | all",
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
        matehome(report);
        homeresolution(report);
        physique(report);
        roaming(report);
        ability(report);
        lineage(report);
        farm(report);
        berry(report);
        food(report);
        famine(report);
        traitfx(report);
        polygyny(report);
        elder(report);
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

        // 4) 저장: 요리사(+0.2)=1.2 / 날로먹기(-0.2)=0.8
        checkNum(report, "multiplier/저장", 1.2,
                Multipliers.storage(one(Sex.MALE, TraitInstance.of(Trait.COOK))), "요리사 = 1.2");
        checkNum(report, "multiplier/저장저하", 0.8,
                Multipliers.storage(one(Sex.MALE, TraitInstance.of(Trait.RAW_EATER))), "날로먹기 = 0.8");

        // 4b) 탐지거리 배율(페널티 특성의 반대급부): 식물혼동=동물 1.5·식물은 1.0(단독),
        //     공간지각=식물 1.25, 공간지각+식물혼동=식물 1.5(시너지), 피공포=식물 1.5,
        //     3종 동시=2.0(가산). 몬스터 감지: 피공포 +3(단독 11), 겁쟁이 −3과 상쇄(=8).
        checkNum(report, "multiplier/동물탐지", 1.5,
                Multipliers.huntRange(one(Sex.MALE, TraitInstance.of(Trait.PLANT_CONFUSED))),
                "식물혼동 동물탐지 1.5");
        checkNum(report, "multiplier/동물탐지기본", 1.0, Multipliers.huntRange(one(Sex.MALE)),
                "무특성 1.0");
        checkNum(report, "multiplier/식물탐지", 1.25,
                Multipliers.forageRange(one(Sex.MALE, TraitInstance.of(Trait.GOOD_SPATIAL))),
                "공간지각 1.25");
        checkNum(report, "multiplier/식물탐지시너지", 1.5,
                Multipliers.forageRange(one(Sex.MALE, TraitInstance.of(Trait.GOOD_SPATIAL),
                        TraitInstance.of(Trait.PLANT_CONFUSED))),
                "공간지각+식물혼동 = 1.0+0.5(시너지)");
        checkNum(report, "multiplier/식물탐지피공포", 1.5,
                Multipliers.forageRange(one(Sex.MALE, TraitInstance.of(Trait.BLOOD_FEARFUL))),
                "피공포 1.5");
        checkNum(report, "multiplier/식물탐지중첩", 2.0,
                Multipliers.forageRange(one(Sex.MALE, TraitInstance.of(Trait.BLOOD_FEARFUL),
                        TraitInstance.of(Trait.GOOD_SPATIAL), TraitInstance.of(Trait.PLANT_CONFUSED))),
                "피공포+시너지 = 1.0+0.5+0.5");
        checkNum(report, "multiplier/눌변가노동", 1.1,
                Multipliers.gather(one(Sex.MALE, TraitInstance.of(Trait.INARTICULATE))),
                "눌변가 채집 1.1(말 대신 손)");
        checkNum(report, "multiplier/피공포감지", 11.0,
                Combat.detectionRange(one(Sex.MALE, TraitInstance.of(Trait.BLOOD_FEARFUL))),
                "피공포 몬스터 감지 8+3");
        checkNum(report, "multiplier/피공포겁쟁이", 8.0,
                Combat.detectionRange(one(Sex.MALE, TraitInstance.of(Trait.BLOOD_FEARFUL),
                        TraitInstance.of(Trait.COWARD))),
                "겁쟁이 −3 + 피공포 +3 = 상쇄 8");

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

        // ①b 유인 반경 배율: 조심성 0.75(눈에 덜 띔 — 감지 −3의 반대급부), 그 외 1.0
        boolean aggro = close(Combat.aggroRangeMult(
                        one(Sex.MALE, TraitInstance.of(Trait.CAUTIOUS))), 0.75)
                && close(Combat.aggroRangeMult(neutral), 1.0)
                && close(Combat.aggroRangeMult(brave), 1.0);
        report.add("combat/유인배율", aggro, "조심성 0.75 · 그 외 1.0", aggro ? "정상" : "어긋남");

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

        // 5) 여성 페널티: 신체 60% 약함 (0.4배) — 홀로는 좀비도 못 이김
        boolean female = Math.abs(SurvivalRules.physicalFactor(Sex.FEMALE) - 0.4) < 1e-9
                && Math.abs(SurvivalRules.physicalFactor(Sex.MALE) - 1.0) < 1e-9;
        report.add("lifecycle/여성페널티", female, "여성 신체 0.4배 (60%↓)",
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

        // 6b) 직계 조상 차단 — breed 가 조상 명단(부모+양가 병합)을 저장 → 조부모·증조까지 금지,
        //     사촌(방계)은 여전히 허용(격리 집단 번식 보전). "가족 링크 겹침"의 사전 계산판.
        DeterministicRng krng = new DeterministicRng(77);
        Individual out1 = new Individual(21, Sex.FEMALE, 0, 0, 1);
        Individual out2 = new Individual(22, Sex.FEMALE, 0, 0, 1);
        Individual out3 = new Individual(23, Sex.MALE, 0, 0, 1);
        Individual p1 = Genetics.breed(30, g1a, g1b, krng, 2, null);   // g1a·g1b 의 자식
        Individual p2 = Genetics.breed(33, g1a, g1b, krng, 2, null);   // p1 의 형제
        Individual gc = Genetics.breed(31, p1, out1, krng, 3, null);   // 손주(g1a 기준)
        Individual ggc = Genetics.breed(32, gc, out3, krng, 4, null);  // 증손
        Individual cz = Genetics.breed(34, p2, out2, krng, 3, null);   // gc 의 사촌
        boolean lineal = Kinship.isRelated(g1a, gc)        // 조부-손주 금지
                && Kinship.isRelated(g1b, gc)              // 조모-손주 금지
                && Kinship.isRelated(g1a, ggc)             // 증조-증손 금지
                && Kinship.isRelated(p1, gc)               // 부모-자식(기존) 유지
                && !Kinship.isRelated(gc, cz)              // 사촌 허용 유지
                && !Kinship.isRelated(gc, out2)            // 무관 개체 허용
                && gc.ancestorIds().length == 4            // 명단 = 부모2 + 조부모2 (0 미상 제외)
                && ggc.ancestorIds().length == 6;          // 부모2 + 부계 조상4(out3 쪽은 미상 0)
        report.add("mating/직계차단", lineal,
                "조부모·증조-직계 금지 / 사촌·무관 허용 / 조상 명단 병합 저장",
                lineal ? "정상" : String.format("손주명단 %d개 · 증손명단 %d개",
                        gc.ancestorIds().length, ggc.ancestorIds().length));
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest settlement — 거처 배치 (설계서 Phase 4, §13-D)
    // ──────────────────────────────────────────────────────────────
    private static void settlement(Report report) {
        Individual mig = one(Sex.MALE, TraitInstance.of(Trait.MIGRATORY));
        Individual home = one(Sex.MALE, TraitInstance.of(Trait.HOMEBOUND));
        Individual neu = one(Sex.MALE);

        // 1) 밀집 거리 회귀 방지: 실 배치 규칙(HomeResolution)의 애향 거리가 최소 간격 이상(첫 링
        //    성립 — 과거 8<10 으로 링0 전멸→중립보다 멀어지는 역전)이고 중립(16)보다는 가까운지.
        //    (구 homeDistance 는 런타임 미사용 死코드라 삭제 — 검증 착시 제거.)
        int closeD = HomeResolution.plan(HomeResolution.dispositionOf(home),
                HomeResolution.dispositionOf(home)).distance();
        boolean dist = closeD >= Settlement.MIN_GAP && closeD < Settlement.BASE_DISTANCE;
        report.add("settlement/밀집거리", dist,
                "애향 신축 거리 ≥ 최소간격(" + Settlement.MIN_GAP + ") · < 중립(" + Settlement.BASE_DISTANCE + ")",
                "애향 거리 " + closeD);

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

        // 1b) 준비 임계(무모/신중): 무모 −1(덜 준비해도 낳음)·신중 +1(준비 더 함), 같은 축 반발이라
        //     부부 조합으로만 만남 — 무모+신중 상쇄(기본 2.5), 무모+번식선호 극단 조합은 하한 0.5.
        boolean prep = Reproduction.threshold(one(Sex.MALE, TraitInstance.of(Trait.RECKLESS)), f) == 1.5
                && Reproduction.threshold(one(Sex.MALE, TraitInstance.of(Trait.RECKLESS)),
                        one(Sex.FEMALE, TraitInstance.of(Trait.RECKLESS))) == 0.5
                && Reproduction.threshold(one(Sex.MALE, TraitInstance.of(Trait.RECKLESS)),
                        one(Sex.FEMALE, TraitInstance.of(Trait.PRUDENT))) == 2.5
                && Reproduction.threshold(one(Sex.MALE, TraitInstance.of(Trait.PRUDENT)),
                        one(Sex.FEMALE, TraitInstance.of(Trait.PRUDENT))) == 4.5
                && Reproduction.threshold(
                        one(Sex.MALE, TraitInstance.of(Trait.RECKLESS),
                                TraitInstance.of(Trait.REPRODUCTION_EAGER)),
                        one(Sex.FEMALE, TraitInstance.of(Trait.RECKLESS),
                                TraitInstance.of(Trait.REPRODUCTION_EAGER))) == Reproduction.MIN_THRESHOLD;
        report.add("reproduction/준비임계", prep,
                "무모−1(둘 0.5)·신중+1(둘 4.5)·무모+신중 상쇄 2.5·무모②+선호② 하한 " + Reproduction.MIN_THRESHOLD,
                prep ? "정상" : "무모한쪽 "
                        + Reproduction.threshold(one(Sex.MALE, TraitInstance.of(Trait.RECKLESS)), f)
                        + " · 상쇄 " + Reproduction.threshold(one(Sex.MALE, TraitInstance.of(Trait.RECKLESS)),
                                one(Sex.FEMALE, TraitInstance.of(Trait.PRUDENT))));

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

    // ──────────────────────────────────────────────────────────────
    // /evotest matehome — 재혼·분가 거처 귀속 판정 (MateHome)
    // ──────────────────────────────────────────────────────────────
    private static void matehome(Report report) {
        MateHome.Status W = MateHome.Status.WANDERER;
        MateHome.Status L = MateHome.Status.LONE_OWNER;
        MateHome.Status F = MateHome.Status.FAMILY_MEMBER;

        boolean ok =
                MateHome.resolve(W, W) == MateHome.Action.NEW
                && MateHome.resolve(W, L) == MateHome.Action.USE_B      // 혼자 사는 쪽으로
                && MateHome.resolve(W, F) == MateHome.Action.NEW        // 자식 분가 → 신축
                && MateHome.resolve(L, W) == MateHome.Action.USE_A
                && MateHome.resolve(L, L) == MateHome.Action.KEEP_ONE   // 랜덤 한쪽 폐기
                && MateHome.resolve(L, F) == MateHome.Action.USE_A      // 자식+혼자 → 혼자 거처로
                && MateHome.resolve(F, W) == MateHome.Action.NEW
                && MateHome.resolve(F, L) == MateHome.Action.USE_B
                && MateHome.resolve(F, F) == MateHome.Action.NEW;       // 둘 다 자식 → 분가 신축
        report.add("matehome/판정", ok,
                "방랑·자식→신축 · 혼자쪽 있으면 그 거처 · 둘다 혼자→랜덤 합류",
                ok ? "9종 전부 정상" : "판정 어긋남");
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest homeresolution — 정착 성향 조합별 거처 마련 계획 (설계서 §13-D 확장)
    // ──────────────────────────────────────────────────────────────
    private static void homeresolution(Report report) {
        HomeResolution.Disposition M = HomeResolution.Disposition.MIGRANT;
        HomeResolution.Disposition H = HomeResolution.Disposition.HOMER;
        HomeResolution.Disposition N = HomeResolution.Disposition.NEUTRAL;
        int base = Settlement.BASE_DISTANCE;

        // 1) 성향 판정: 이주자→MIGRANT, 애향심→HOMER, 무특성→NEUTRAL
        boolean disp = HomeResolution.dispositionOf(one(Sex.MALE, TraitInstance.of(Trait.MIGRATORY))) == M
                && HomeResolution.dispositionOf(one(Sex.MALE, TraitInstance.of(Trait.HOMEBOUND))) == H
                && HomeResolution.dispositionOf(one(Sex.MALE)) == N;
        report.add("homeresolution/성향", disp, "이주자·애향심·기본 판정",
                disp ? "정상" : "어긋남");

        // 2) 이주자는 항상 신축(재사용 0%), 애향심은 확률적 재사용(75/100%)
        boolean empty = HomeResolution.plan(M, N).emptyPercent() == 0     // 이주자+기본
                && HomeResolution.plan(M, M).emptyPercent() == 0          // 이주자+이주자
                && HomeResolution.plan(M, H).emptyPercent() == 50         // 이주자+애향심(랜덤)
                && HomeResolution.plan(N, H).emptyPercent() == 75         // 기본+애향심
                && HomeResolution.plan(H, H).emptyPercent() == 100        // 애향심+애향심
                && HomeResolution.plan(N, N).emptyPercent() == 50;        // 기본+기본
        report.add("homeresolution/재사용", empty,
                "이주자0%·이주×애향50%·기본×애향75%·애향×애향100%",
                empty ? "정상" : "어긋남");

        // 3) 신축 거리: 이주×이주 더멀리(×3) > 이주×기본 멀리(×2) > 기본 > 애향 가까이(÷2)
        boolean dist = HomeResolution.plan(M, M).distance() == base * 3
                && HomeResolution.plan(M, N).distance() == base * 2
                && HomeResolution.plan(M, H).distance() == base       // 기본처럼 가까이
                && HomeResolution.plan(N, N).distance() == base
                && HomeResolution.plan(N, H).distance() == base * 3 / 4
                && HomeResolution.plan(H, H).distance() == base * 3 / 4; // 밀집 12(≥MIN_GAP 10)
        report.add("homeresolution/거리", dist,
                "이주×이주 " + (base * 3) + " · 이주×기본 " + (base * 2) + " · 애향 " + (base * 3 / 4),
                dist ? "정상" : "어긋남");

        // 4) 신축 앵커: 기본+애향=애향 보유자 고향 · 애향+애향=두 거처 중간 · 그 외=짝 성사 자리
        boolean anchor = HomeResolution.plan(N, H).anchor() == HomeResolution.Anchor.HOMER_BIRTH
                && HomeResolution.plan(H, N).anchor() == HomeResolution.Anchor.HOMER_BIRTH
                && HomeResolution.plan(H, H).anchor() == HomeResolution.Anchor.MIDPOINT_HOMES
                && HomeResolution.plan(M, H).anchor() == HomeResolution.Anchor.MATING_SPOT
                && HomeResolution.plan(N, N).anchor() == HomeResolution.Anchor.MATING_SPOT;
        report.add("homeresolution/앵커", anchor,
                "기본+애향=고향 · 애향+애향=중간 · 그외=성사자리",
                anchor ? "정상" : "어긋남");

        // 5) 대칭성: plan(a,b) == plan(b,a) 전 조합
        HomeResolution.Disposition[] all = {M, H, N};
        boolean sym = true;
        for (HomeResolution.Disposition a : all) {
            for (HomeResolution.Disposition b : all) {
                if (!HomeResolution.plan(a, b).equals(HomeResolution.plan(b, a))) {
                    sym = false;
                }
            }
        }
        report.add("homeresolution/대칭", sym, "plan(a,b)=plan(b,a)",
                sym ? "정상" : "비대칭!");
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest physique — 신체 등급(I~V) 강도·등급 선호 매칭 (설계서 §14)
    // ──────────────────────────────────────────────────────────────
    private static void physique(Report report) {
        // 1) 등급 기반구조: clamp·roman·발동 등급 조회
        boolean infra = TraitInstance.clampGrade(0) == 1 && TraitInstance.clampGrade(9) == 5
                && TraitInstance.graded(Trait.TOUGH, 3).grade() == 3
                && "III".equals(TraitInstance.roman(3)) && "V".equals(TraitInstance.roman(5))
                && Trait.TOUGH.isGraded() && Trait.PREF_RECOVERY.isGraded()
                && !Trait.HERBALIST.isGraded()
                && ExpressionResolver.expressedGrade(graded(Sex.MALE, Trait.TOUGH, 4), Trait.TOUGH) == 4
                && ExpressionResolver.expressedGrade(graded(Sex.MALE, Trait.TOUGH, 4), Trait.FRAIL) == 0;
        report.add("physique/등급기반", infra, "clamp1~5·로마·isGraded·발동등급 조회",
                infra ? "정상" : "어긋남");

        // 2) 신체 배수: 등급 비례(튼튼 ±5%/속도 ±3%/시야 +8·−6%/회복 +30·−15%)
        boolean fac = close(Physique.toughness(graded(Sex.MALE, Trait.TOUGH, 5)), 1.25)
                && close(Physique.toughness(graded(Sex.MALE, Trait.FRAIL, 5)), 0.75)
                && close(Physique.toughness(one(Sex.MALE)), 1.0)
                && close(Physique.agility(graded(Sex.MALE, Trait.NIMBLE, 5)), 1.15)
                && close(Physique.vision(graded(Sex.MALE, Trait.FARSIGHTED, 5)), 1.40)
                && close(Physique.vision(graded(Sex.MALE, Trait.NEARSIGHTED, 5)), 0.70)
                && close(Physique.recovery(graded(Sex.MALE, Trait.HARDY, 5)), 2.50)
                && close(Physique.recovery(graded(Sex.MALE, Trait.SICKLY, 5)), 0.25);
        report.add("physique/배수", fac, "튼튼V 1.25·빈약V 0.75·천리안V 1.40·강건V 2.5",
                fac ? "정상" : "어긋남");

        // 2b) 힘 축 트레이드오프: 공격 힘센 +8%/약함 −6%/등급 ↔ 소모(식욕) ±4%/등급.
        //     V등급 소모가 종전 고정 배율(×1.2/×0.8)과 일치 — 최대 등급 밸런스 불변 확인.
        boolean str = close(Physique.strength(graded(Sex.MALE, Trait.STRONG, 5)), 1.40)
                && close(Physique.strength(graded(Sex.MALE, Trait.STRONG, 1)), 1.08)
                && close(Physique.strength(graded(Sex.MALE, Trait.WEAK, 5)), 0.70)
                && close(Physique.strength(graded(Sex.MALE, Trait.WEAK, 2)), 0.88)
                && close(Physique.strength(one(Sex.MALE)), 1.0)
                && close(Physique.appetite(graded(Sex.MALE, Trait.STRONG, 5)), 1.20)
                && close(Physique.appetite(graded(Sex.MALE, Trait.STRONG, 3)), 1.12)
                && close(Physique.appetite(graded(Sex.MALE, Trait.WEAK, 5)), 0.80)
                && close(Physique.appetite(one(Sex.MALE)), 1.0);
        report.add("physique/힘트레이드오프", str,
                "공격 힘센V 1.40·I 1.08·약함V 0.70 ↔ 소모 힘센V 1.20(종전 일치)·III 1.12·약함V 0.80",
                str ? "정상" : "어긋남");

        // 3) 등급 선호 매칭: 강건III선호 → 보유 I·V=1, II·IV=2, III=3, 미보유=0 (사용자 규칙)
        Individual pref = graded(Sex.MALE, Trait.PREF_RECOVERY, 3);
        boolean match = Multipliers.charmScore(pref, graded(Sex.FEMALE, Trait.HARDY, 3)) == 3
                && Multipliers.charmScore(pref, graded(Sex.FEMALE, Trait.HARDY, 2)) == 2
                && Multipliers.charmScore(pref, graded(Sex.FEMALE, Trait.HARDY, 4)) == 2
                && Multipliers.charmScore(pref, graded(Sex.FEMALE, Trait.HARDY, 1)) == 1
                && Multipliers.charmScore(pref, graded(Sex.FEMALE, Trait.HARDY, 5)) == 1
                && Multipliers.charmScore(pref, one(Sex.FEMALE)) == 0;
        report.add("physique/등급선호", match, "강건III선호 → I·V:1 II·IV:2 III:3 (완전일치 3점)",
                match ? "정상" : "어긋남");

        // 4) 유전: 1세대 등급 특성은 1~5, 무등급은 0
        DeterministicRng rng = new DeterministicRng(31337L);
        boolean genOk = true;
        for (int i = 0; i < 300 && genOk; i++) {
            Individual ind = Genetics.randomFirstGen(i + 1, rng);
            for (TraitInstance ti : ind.allTraits()) {
                if (ti.isAnti()) {
                    continue; // 반발 카드는 강도 등급 없음(억제자)
                }
                boolean g = ti.trait().isGraded();
                if (g && (ti.grade() < 1 || ti.grade() > 5)) genOk = false;
                if (!g && ti.grade() != 0) genOk = false;
            }
        }
        report.add("physique/유전등급", genOk, "등급특성 1~5·무등급 0",
                genOk ? "정상" : "범위이탈");
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest roaming — 특성별 활동반경 (설계서 §14 활동반경, 분산 방지)
    // ──────────────────────────────────────────────────────────────
    private static void roaming(Report report) {
        double b = Roaming.BASE_RADIUS;
        boolean ok = close(Roaming.radius(one(Sex.MALE)), b)                                   // 중립 32
                && close(Roaming.radius(one(Sex.MALE, TraitInstance.of(Trait.MIGRATORY))), b * 2.0)   // 이주자 넓게
                && close(Roaming.radius(one(Sex.MALE, TraitInstance.of(Trait.HOMEBOUND))), b * 0.5)   // 애향심 좁게
                && close(Roaming.radius(one(Sex.MALE, TraitInstance.of(Trait.SOLITARY))), b * 1.5)    // 고독 넓게
                && close(Roaming.radius(one(Sex.MALE, TraitInstance.of(Trait.GREGARIOUS))), b * 0.75) // 군집 좁게
                && close(Roaming.radius(one(Sex.MALE, TraitInstance.of(Trait.MIGRATORY),
                        TraitInstance.of(Trait.SOLITARY))), b * 3.0)                            // 이주+고독 최대
                && close(Roaming.radius(one(Sex.MALE, TraitInstance.of(Trait.HOMEBOUND),
                        TraitInstance.of(Trait.GREGARIOUS))), b * 0.375);                       // 애향+군집 최소
        report.add("roaming/반경", ok,
                "중립 32·이주 ×2·애향 ×0.5·고독 ×1.5·군집 ×0.75(조합 곱)",
                ok ? "정상" : "어긋남");
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest ability — 능력특성 재분류(성향 슬롯 공유) + 언변 매력 (설계서 §14)
    // ──────────────────────────────────────────────────────────────
    private static void ability(Report report) {
        // 1) 능력특성은 성향과 같은 슬롯(DISPOSITION) 공유 + isAbility 로 구분, 힘은 신체 유지
        boolean cat = Trait.HERBALIST.category() == Category.DISPOSITION
                && Trait.HUNTER.category() == Category.DISPOSITION
                && Trait.COOK.category() == Category.DISPOSITION
                && Trait.DEXTEROUS.category() == Category.DISPOSITION
                && Trait.HERBIVORE.category() == Category.DISPOSITION
                && Trait.ELOQUENT.category() == Category.DISPOSITION
                && Trait.HERBALIST.isAbility() && Trait.ELOQUENT.isAbility()
                && !Trait.STRONG.isAbility() && !Trait.DILIGENT.isAbility()
                && Trait.STRONG.category() == Category.PHYSICAL; // 힘은 신체 유지
        report.add("ability/슬롯공유", cat, "획득·언변=성향 슬롯 공유·능력 구분·힘은 신체",
                cat ? "정상" : "어긋남");

        // 2) 신체 스탯 전부 등급화(힘·공간지각 포함), 능력은 무등급
        boolean graded = Trait.STRONG.isGraded() && Trait.GOOD_SPATIAL.isGraded()
                && Trait.TOUGH.isGraded() && !Trait.HERBALIST.isGraded() && !Trait.ELOQUENT.isGraded();
        report.add("ability/신체등급", graded, "힘·공간지각 포함 신체 전부 등급·능력 무등급",
                graded ? "정상" : "어긋남");

        // 3) 언변 매력: 달변가 +1 / 눌변가 −1 (상대 기준, 기본 매력 가감)
        Individual plain = one(Sex.MALE);
        boolean charm = Multipliers.charmScore(plain, one(Sex.FEMALE, TraitInstance.of(Trait.ELOQUENT))) == 1
                && Multipliers.charmScore(plain, one(Sex.FEMALE, TraitInstance.of(Trait.INARTICULATE))) == -1
                && Multipliers.charmScore(plain, one(Sex.FEMALE)) == 0;
        report.add("ability/언변매력", charm, "달변가 +1·눌변가 −1·기본 0",
                charm ? "정상" : "어긋남");
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest berry — 베리 심기 잉여 배분(생존·번식 우선, 남으면 여러 그루)
    // ──────────────────────────────────────────────────────────────
    private static void berry(Report report) {
        // 잉여10·예비2·번식몫2.5 → 잔여5.5 → 5그루(넉넉할수록 여러 그루)
        boolean b1 = BerryEconomy.plant(10, 2, 2.5, 0, 8) == 5;
        // 번식몫까지 빼면 부족: 잉여5 → 잔여0.5 → 0그루(번식이 우선)
        boolean b2 = BerryEconomy.plant(5, 2, 2.5, 0, 8) == 0;
        // 잉여6 → 잔여1.5 → 1그루
        boolean b3 = BerryEconomy.plant(6, 2, 2.5, 0, 8) == 1;
        // 상한: 잉여20·현재6·상한8 → 잔여15.5지만 자리 2 → 2그루
        boolean b4 = BerryEconomy.plant(20, 2, 2.5, 6, 8) == 2;
        // 독신(번식몫0): 잉여3 → 잔여1 → 1그루
        boolean b5 = BerryEconomy.plant(3, 2, 0, 0, 8) == 1;
        // 굶는 가정: 잉여1 → 잔여<0 → 0그루(아사·출산 지장 없음)
        boolean b6 = BerryEconomy.plant(1, 2, 2.5, 0, 8) == 0;

        boolean ok = b1 && b2 && b3 && b4 && b5 && b6;
        report.add("berry/잉여배분", ok, "예비·번식 뺀 잔여로만 심기(넉넉할수록 여러 그루·상한)",
                ok ? "정상" : "어긋남");
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest food — 식량 경제 v2 (연속 저장고+개인 보유, 성별 채집 배율, 출산 비용)
    //   ※ 순수 경제 시뮬 — 길찾기(제때 귀가)는 표현층 관찰 대상이라 여기선 "정산 시 집" 가정.
    // ──────────────────────────────────────────────────────────────
    private static void food(Report report) {
        Individual man = one(Sex.MALE);
        Individual woman = one(Sex.FEMALE);

        // [food/소모] 단계·활동·특성·부상별 소모율
        boolean c1 = close(FoodEconomy.consumptionPerDay(LifeStage.ADULT, Activity.MOVE, man, false), 3.0)
                && close(FoodEconomy.consumptionPerDay(LifeStage.ADULT, Activity.SLEEP, man, false), 0.0)
                && close(FoodEconomy.consumptionPerDay(LifeStage.ADULT, Activity.IDLE, man, false), 1.2)
                && close(FoodEconomy.consumptionPerDay(LifeStage.BOY, Activity.MOVE, man, false), 1.5)
                && close(FoodEconomy.consumptionPerDay(LifeStage.INFANT, Activity.MOVE, man, false), 0.9)
                && close(FoodEconomy.consumptionPerDay(LifeStage.ADULT, Activity.MOVE, man, true), 3.5)
                // 힘/약 소모는 등급 비례(±4%/등급): 힘센V ×1.2(종전 고정값과 일치)·III ×1.12·약함V ×0.8.
                // 무등급 힘센(디버그 of())은 Physique 의미론대로 중립 1.0.
                && close(FoodEconomy.consumptionPerDay(LifeStage.ADULT, Activity.MOVE,
                        one(Sex.MALE, TraitInstance.graded(Trait.STRONG, 5)), false), 3.6)
                && close(FoodEconomy.consumptionPerDay(LifeStage.ADULT, Activity.MOVE,
                        one(Sex.MALE, TraitInstance.graded(Trait.STRONG, 3)), false), 3.0 * 1.12)
                && close(FoodEconomy.consumptionPerDay(LifeStage.ADULT, Activity.MOVE,
                        one(Sex.MALE, TraitInstance.graded(Trait.WEAK, 5)), false), 2.4)
                && close(FoodEconomy.consumptionPerDay(LifeStage.ADULT, Activity.MOVE,
                        one(Sex.MALE, TraitInstance.of(Trait.STRONG)), false), 3.0)
                && close(FoodEconomy.consumptionPerDay(LifeStage.ADULT, Activity.MOVE,
                        one(Sex.MALE, TraitInstance.of(Trait.LAZY)), false), 2.7);
        report.add("food/소모", c1, "성인3.0·소년1.5·유아0.9 × 활동 × 특성(힘/약 등급 ±4%/등급) +부상0.5",
                c1 ? "정상" : "어긋남");

        // [food/배율] 남성 3인 부양·여성 자급 경계·보정특성 잉여↑
        double familyNeed = 3.0 + 3.0 + 0.9; // 성인2 + 유아1 명목
        boolean y1 = close(FoodEconomy.tripYield(man), 3.0)            // 남 2.0×1.5
                && FoodEconomy.tripYield(man) * 3 >= familyNeed         // 3트립 9.0 ≥ 6.9
                && close(FoodEconomy.tripYield(woman), 1.0)             // 여 2.0×0.5 → 3트립=본인 소모
                && FoodEconomy.tripYield(one(Sex.MALE, TraitInstance.of(Trait.HERBALIST)))
                        > FoodEconomy.tripYield(man);                   // 보정특성 → 잉여↑
        report.add("food/배율", y1, "남 3.0/트립(3인 부양)·여 1.0(자급 경계)·약초학자 가산",
                y1 ? "정상" : "어긋남");

        // [food/밴드] 정수 입금·목표까지 인출, H 밴드 유지
        {
            var dad = new FoodEconomy.Eater(man, LifeStage.ADULT, 2.7, true);   // 여분 → 입금
            var mom = new FoodEconomy.Eater(woman, LifeStage.ADULT, 0.5, true); // 트리거 미만 → 인출
            var out = new FoodEconomy.Eater(woman, LifeStage.ADULT, 0.5, false); // 집 밖 → 건드리지 않음
            double l = FoodEconomy.settleHome(3.0, java.util.List.of(dad, mom, out));
            boolean b = close(dad.holding, 1.7) && close(mom.holding, 1.5)
                    && close(out.holding, 0.5) && close(l, 3.0) && isInt(l);
            report.add("food/밴드", b, "여분 정수 입금 → 1.7 / 0.5→1.5 인출 / 집 밖 불변 / L 정수",
                    b ? "정상" : String.format("dad %.2f mom %.2f L %.2f", dad.holding, mom.holding, l));
        }

        // [food/절약특성] 페널티 특성의 반대급부(소모↓): 아이불호·번식불호·빈약 ×0.95 / 병약 ×0.9 / 중첩 곱
        boolean sv = close(FoodEconomy.consumptionPerDay(LifeStage.ADULT, Activity.MOVE,
                        one(Sex.MALE, TraitInstance.of(Trait.CHILD_AVERSE)), false), 3.0 * 0.95)
                && close(FoodEconomy.consumptionPerDay(LifeStage.ADULT, Activity.MOVE,
                        one(Sex.MALE, TraitInstance.of(Trait.REPRODUCTION_AVERSE)), false), 3.0 * 0.95)
                && close(FoodEconomy.consumptionPerDay(LifeStage.ADULT, Activity.MOVE,
                        one(Sex.MALE, TraitInstance.of(Trait.FRAIL)), false), 3.0 * 0.95)
                && close(FoodEconomy.consumptionPerDay(LifeStage.ADULT, Activity.MOVE,
                        one(Sex.MALE, TraitInstance.of(Trait.SICKLY)), false), 3.0 * 0.9)
                && close(FoodEconomy.consumptionPerDay(LifeStage.ADULT, Activity.MOVE,
                        one(Sex.MALE, TraitInstance.graded(Trait.WEAK, 5), TraitInstance.of(Trait.SICKLY),
                                TraitInstance.of(Trait.REPRODUCTION_AVERSE)), false),
                        3.0 * 0.8 * 0.9 * 0.95); // 극단 중첩 2.052(약함V) — 하한 회귀 감시
        report.add("food/절약특성", sv, "아이불호·번식불호·빈약 ×0.95 · 병약 ×0.9 · 중첩 곱(약함V+병약+번식불호 2.052)",
                sv ? "정상" : "어긋남");

        // [food/날로먹기] 저장 손실 ↔ 섭취 효율(요리 축 v2 배선): L은 항상 정수 유닛 유지
        {
            Individual raw = one(Sex.MALE, TraitInstance.of(Trait.RAW_EATER));
            Individual cook = one(Sex.MALE, TraitInstance.of(Trait.COOK));
            // 입금: 날로먹기 1유닛에 H 1.25 소요(2.3→1.05), 요리사 0.833(2.0→1.167) — 무특성은 1.0(종전)
            var rDad = new FoodEconomy.Eater(raw, LifeStage.ADULT, 2.3, true);
            double l1 = FoodEconomy.settleHome(3.0, java.util.List.of(rDad));
            var cDad = new FoodEconomy.Eater(cook, LifeStage.ADULT, 2.0, true);
            double l2 = FoodEconomy.settleHome(3.0, java.util.List.of(cDad));
            // 인출: 날로먹기는 1유닛으로 H 1.2 회복(0.5→1.7) — 왕복 1.2/1.25 = 96%(순손실 유지)
            var rMom = new FoodEconomy.Eater(raw, LifeStage.ADULT, 0.5, true);
            double l3 = FoodEconomy.settleHome(2.0, java.util.List.of(rMom));
            boolean b = close(rDad.holding, 2.3 - 1.25) && close(l1, 4.0) && isInt(l1)
                    && close(cDad.holding, 2.0 - 1.0 / 1.2) && close(l2, 4.0) && isInt(l2)
                    && close(rMom.holding, 0.5 + 1.2) && close(l3, 1.0) && isInt(l3)
                    && close(FoodEconomy.intakeMult(raw), 1.2)
                    && close(FoodEconomy.intakeMult(man), 1.0);
            report.add("food/날로먹기", b,
                    "입금 1.25H/유닛(요리사 0.83) · 인출 1유닛=1.2H · 왕복 96% · L 정수 보존",
                    b ? "정상" : String.format("rDad %.3f L %.1f · cDad %.3f · rMom %.2f",
                            rDad.holding, l1, cDad.holding, rMom.holding));
        }

        // [food/우선순위] 기근: 저장고 2 vs 배고픈 3명 → 남편→자식만 급식, 아내 굶음
        {
            var dad = new FoodEconomy.Eater(man, LifeStage.ADULT, 0.5, true);
            var kid = new FoodEconomy.Eater(man, LifeStage.BOY, 0.5, true);
            var mom = new FoodEconomy.Eater(woman, LifeStage.ADULT, 0.5, true);
            double l = FoodEconomy.settleHome(2.0, java.util.List.of(dad, kid, mom));
            boolean b = close(dad.holding, 1.5) && close(kid.holding, 1.5)
                    && close(mom.holding, 0.5) && close(l, 0.0);
            report.add("food/우선순위", b, "기근 시 남편→자식→아내 순 급식(아내 최후)",
                    b ? "정상" : "어긋남");
        }

        // [food/번식] 출산비용 선차감 경계값 + 굶주림(집 한정) 게이트
        {
            double need = 6.9; // 성인2+유아1
            boolean b = FoodEconomy.canReproduce(13.0, need, 2, 0, false)      // 13−3−6.9=3.1 ≥ 3
                    && !FoodEconomy.canReproduce(12.0, need, 2, 0, false)      // 2.1 < 3
                    && !FoodEconomy.canReproduce(13.0, need, 2, 0, true)       // 굶주림 차단
                    && FoodEconomy.anyStarvingHome(java.util.List.of(
                            new FoodEconomy.Eater(man, LifeStage.ADULT, 0.2, true)))
                    && !FoodEconomy.anyStarvingHome(java.util.List.of(
                            new FoodEconomy.Eater(man, LifeStage.ADULT, 0.2, false))); // 밖 = 제외
            report.add("food/번식", b, "(L−출산비용−하루소모)≥성년수+1 · 굶주림은 집 구성원만",
                    b ? "정상" : "어긋남");
        }

        // [food/출산비용] 연쇄 출산 제동: 출산 직후 L−3 → 즉시 재출산 불가
        {
            double l = 13.0;
            boolean first = FoodEconomy.canReproduce(l, 6.9, 2, 0, false);
            l -= FoodEconomy.BIRTH_COST;
            boolean second = FoodEconomy.canReproduce(l, 6.9, 2, 0, false);
            boolean b = first && !second && isInt(l);
            report.add("food/출산비용", b, "출산 시 L−3.0 차감 → 같은 잉여로 연쇄 출산 불가",
                    b ? "정상" : "어긋남");
        }

        // [food/히스테리시스] 밴드 중간값 불간섭·채움 후 안정(진동 없음)
        {
            var mid = new FoodEconomy.Eater(man, LifeStage.ADULT, 1.2, true);  // 트리거~목표 사이
            double l1 = FoodEconomy.settleHome(5.0, java.util.List.of(mid));
            var low = new FoodEconomy.Eater(man, LifeStage.ADULT, 0.99, true);
            double l2 = FoodEconomy.settleHome(5.0, java.util.List.of(low));   // → 1.99
            double l3 = FoodEconomy.settleHome(l2, java.util.List.of(low));    // 재정산 → 불변
            boolean b = close(mid.holding, 1.2) && close(l1, 5.0)
                    && close(low.holding, 1.99) && close(l2, 4.0) && close(l3, 4.0);
            report.add("food/히스테리시스", b, "1.0~1.5 사이 불간섭 · 0.99→1.99 후 재정산 불변",
                    b ? "정상" : "어긋남");
        }

        // [food/생존시뮬] 부부+유아 100일: 남성 외벌이(9.0/일)로 무아사·출산상한 도달
        {
            SimOut s = foodSim(100, true, 1);
            boolean b = !s.starved && s.births == 5 && s.larderIntegerAlways && s.minLarder >= 0.0;
            report.add("food/생존시뮬", b,
                    "남성 외벌이 100일 무아사 · 출산 5회(상한) · L 항상 정수·비음수",
                    b ? String.format("출산 %d회 · 최종 L %.0f", s.births, s.finalLarder)
                      : String.format("아사 %s · 출산 %d · 정수 %s", s.starved, s.births, s.larderIntegerAlways));
        }

        // [food/과부시뮬] 육아 구속 과부(수입 0)+유아 → 예상된 붕괴(허용 경로 회귀)
        {
            SimOut s = foodSim(30, false, 1);
            boolean b = s.starved && s.firstStarveDay > 2; // 시작 L(정수 올림)이 며칠은 버팀
            report.add("food/과부시뮬", b, "수입 0 과부 가구는 시작 저장고 소진 후 붕괴(허용 경로)",
                    b ? String.format("%d일차 고갈 — 예상대로", s.firstStarveDay)
                      : String.format("아사 %s · 고갈일 %d", s.starved, s.firstStarveDay));
        }

        // [food/정수불변식] 시작값 올림·정산 반복 후에도 L 정수
        {
            boolean b = isInt(FoodEconomy.initialLarder(6.9)) && close(FoodEconomy.initialLarder(6.9), 7.0)
                    && isInt(FoodEconomy.initialLarder(3.0));
            report.add("food/정수불변식", b, "시작 L=ceil(하루소모) 정수 · 정산은 정수 입출금만",
                    b ? "정상" : "어긋남");
        }
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest famine — 기근→이주 판정(결과 기반·오탐 가드) + 방위 선택 결정론
    // ──────────────────────────────────────────────────────────────
    private static void famine(Report report) {
        long now = 200_000L;
        long settled = 100_000L;                    // 쿨다운(48000) 훨씬 경과
        long stale = now - 30_000L;                 // 성공 없음(창 24000 초과)
        long fresh = now - 10_000L;                 // 최근 성공(창 이내)

        boolean f1 = Famine.shouldMigrate(now, settled, new long[] {stale, stale}, 2.0, 6.9);   // 기근 → 이주
        boolean f2 = !Famine.shouldMigrate(now, settled, new long[] {stale, fresh}, 2.0, 6.9);  // 한 명은 벌이 중 → 잔류
        boolean f3 = !Famine.shouldMigrate(now, settled, new long[] {stale}, 20.0, 6.9);        // 비축 넉넉 → 잔류
        boolean f4 = !Famine.shouldMigrate(now, now - 10_000L, new long[] {stale}, 0.0, 6.9);   // 갓 정착 → 유예
        boolean f5 = !Famine.shouldMigrate(now, settled, new long[] {}, 0.0, 6.9);              // 채집자 0(과부) → 제자리
        report.add("famine/판정", f1 && f2 && f3 && f4 && f5,
                "전원 무수확+비축 바닥+쿨다운 경과일 때만 이주(과부·갓정착·벌이중 제외)",
                (f1 && f2 && f3 && f4 && f5) ? "정상" : "어긋남");

        boolean d1 = Famine.bestDirection(new int[] {0, 3, 7, 2}) == 2;      // 최다 방위
        boolean d2 = Famine.bestDirection(new int[] {5, 1, 5, 0}) == 0;      // 동률 → 앞 인덱스(결정론)
        boolean d3 = Famine.bestDirection(new int[] {0, 0, 0, 0}) == -1;     // 전부 0 → 폴백 신호
        report.add("famine/방향", d1 && d2 && d3, "풀 최다 방위·동률 앞 인덱스·전부0=-1",
                (d1 && d2 && d3) ? "정상" : "어긋남");
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest traitfx — 무기능 특성 14종의 효과(전부 순수 노브 — 육안 테스트 불필요)
    // ──────────────────────────────────────────────────────────────
    private static void traitfx(Report report) {
        Individual plain = one(Sex.MALE);

        // 나눔(이기/이타)·나눔범위(관대/인색)
        boolean s1 = close(FoodEconomy.shareThreshold(one(Sex.MALE, TraitInstance.of(Trait.ALTRUISTIC))), 1.0)
                && close(FoodEconomy.shareThreshold(plain), 1.5)
                && Double.isInfinite(FoodEconomy.shareThreshold(one(Sex.MALE, TraitInstance.of(Trait.SELFISH))))
                && close(FoodEconomy.shareAmount(one(Sex.MALE, TraitInstance.of(Trait.GENEROUS))), 0.75)
                && close(FoodEconomy.shareAmount(one(Sex.MALE, TraitInstance.of(Trait.STINGY))), 0.25)
                && close(FoodEconomy.shareAmount(plain), 0.5);
        report.add("traitfx/나눔", s1, "문턱 이타1.0·기본1.5·이기∞ / 전달량 관대0.75·기본0.5·인색0.25",
                s1 ? "정상" : "어긋남");

        // 책임감(무책임) — 문턱 3.0 + settleHome 통합(2.5로는 입금 없음, 3.2면 1개만)
        var irr = new FoodEconomy.Eater(one(Sex.MALE, TraitInstance.of(Trait.IRRESPONSIBLE)),
                LifeStage.ADULT, 2.5, true);
        double l1 = FoodEconomy.settleHome(0.0, java.util.List.of(irr));
        var irr2 = new FoodEconomy.Eater(one(Sex.MALE, TraitInstance.of(Trait.IRRESPONSIBLE)),
                LifeStage.ADULT, 3.2, true);
        double l2 = FoodEconomy.settleHome(0.0, java.util.List.of(irr2));
        boolean r1 = close(FoodEconomy.depositThreshold(plain), 2.0)
                && close(FoodEconomy.depositThreshold(irr.ind), 3.0)
                && close(irr.holding, 2.5) && close(l1, 0.0)      // 2.5 < 3.0 → 입금 안 함
                && close(irr2.holding, 2.2) && close(l2, 1.0);    // 3.2 → 1개만 입금
        report.add("traitfx/책임감", r1, "무책임 입금문턱 3.0 — 2.5 들고 다님·3.2면 1개만 저장",
                r1 ? "정상" : "어긋남");

        // 아이선호(선호/불호) — 출산 상한 ±1
        boolean c1 = Reproduction.birthLimit(one(Sex.FEMALE, TraitInstance.of(Trait.CHILD_LOVING)), plain) == 6
                && Reproduction.birthLimit(one(Sex.FEMALE, TraitInstance.of(Trait.CHILD_AVERSE)), plain) == 4
                && Reproduction.birthLimit(one(Sex.FEMALE), plain) == 5;
        report.add("traitfx/아이선호", c1, "출산 상한 선호+1(6)·기본5·불호−1(4)", c1 ? "정상" : "어긋남");

        // 경쟁·교육 — 배율 한 줄들
        boolean m1 = close(Multipliers.hunt(one(Sex.MALE, TraitInstance.of(Trait.COMPETITIVE))), 1.2)
                && close(Multipliers.gather(one(Sex.MALE, TraitInstance.of(Trait.BASIC_EDUCATION))), 1.1)
                && close(Multipliers.hunt(one(Sex.MALE, TraitInstance.of(Trait.BASIC_EDUCATION))), 1.1)
                && close(Multipliers.storage(one(Sex.MALE, TraitInstance.of(Trait.SPECIALIST_EDUCATION))), 1.15);
        report.add("traitfx/경쟁교육", m1, "경쟁 사냥+0.2 / 기본교육 채집·사냥+0.1 / 전문교육 저장+0.15",
                m1 ? "정상" : "어긋남");

        // 투자(장기/신속) — 베리 정원 조성 속도
        boolean b1 = close(BerryEconomy.costMult(one(Sex.MALE, TraitInstance.of(Trait.LONG_INVESTMENT))), 0.5)
                && close(BerryEconomy.costMult(one(Sex.MALE, TraitInstance.of(Trait.QUICK_INVESTMENT))), 2.0)
                && close(BerryEconomy.costMult(plain), 1.0)
                && BerryEconomy.plant(10, 2, 2.5, 0, 8, 0.5) == 8   // 잔여5.5/0.5=11 → 상한 8
                && BerryEconomy.plant(10, 2, 2.5, 0, 8, 2.0) == 2;  // 5.5/2 = 2그루
        report.add("traitfx/투자", b1, "장기투자 ×0.5(정원 2배 속도)·신속투자 ×2 — 같은 잉여 8그루 vs 2그루",
                b1 ? "정상" : "어긋남");

        // 시간지향(미래/현재) — R4 넉넉 기준 일수
        boolean t1 = close(FoodEconomy.comfortDays(one(Sex.MALE, TraitInstance.of(Trait.FUTURE_ORIENTED))), 3.0)
                && close(FoodEconomy.comfortDays(one(Sex.MALE, TraitInstance.of(Trait.PRESENT_ORIENTED))), 1.0)
                && close(FoodEconomy.comfortDays(plain), 2.0);
        report.add("traitfx/시간지향", t1, "넉넉 기준 미래3일·기본2일·현재1일(일찍 쉼/늦게 쉼)",
                t1 ? "정상" : "어긋남");

        // 혼기(조혼)·모성애(강함/없음) — 성장·허기 효율·본인 소모
        boolean g1 = close(SurvivalRules.growthMult(LifeStage.BOY,
                        one(Sex.MALE, TraitInstance.of(Trait.EARLY_MARRIAGE)), 0), 0.8)
                && close(SurvivalRules.growthMult(LifeStage.INFANT, plain, 1), 1.25)  // 강함 → 품에 오래
                && close(SurvivalRules.growthMult(LifeStage.BOY, plain, -1), 0.8)     // 없음 → 빨리 독립
                && close(SurvivalRules.growthMult(LifeStage.BOY,
                        one(Sex.MALE, TraitInstance.of(Trait.EARLY_MARRIAGE)), -1), 0.64)
                && close(SurvivalRules.growthMult(LifeStage.ADULT, plain, 1), 1.0);
        boolean g2 = close(FoodEconomy.maternalHungerMult(LifeStage.INFANT, 1), 0.7)  // 적게 먹어도 채워짐
                && close(FoodEconomy.maternalHungerMult(LifeStage.BOY, -1), 1.3)      // 돌봄 부실 → 더 소모
                && close(FoodEconomy.maternalHungerMult(LifeStage.ADULT, 1), 1.0)
                && close(FoodEconomy.consumptionPerDay(LifeStage.ADULT, Activity.MOVE,
                        one(Sex.FEMALE, TraitInstance.of(Trait.NO_MATERNAL)), false), 2.7); // 본인 ×0.9
        report.add("traitfx/혼기모성", g1 && g2,
                "조혼 소년기 0.8 / 강함 성장1.25·자식소모0.7 / 없음 성장0.8·자식소모1.3·본인0.9",
                (g1 && g2) ? "정상" : "어긋남");
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest polygyny — 일부다처 게이트(아내 용인·부양 증명·상한, 기본 수락)
    // ──────────────────────────────────────────────────────────────
    private static void polygyny(Report report) {
        Individual tolerant = one(Sex.FEMALE);
        Individual stingy = one(Sex.FEMALE, TraitInstance.of(Trait.STINGY));
        Individual competitive = one(Sex.FEMALE, TraitInstance.of(Trait.COMPETITIVE));
        double need = 6.9; // 성인2+유아1 — 부양 기준 = ×3일 = 20.7

        boolean g1 = Polygyny.canAccept(java.util.List.of(tolerant), 21.0, need)          // 전부 통과 → 수락
                && !Polygyny.canAccept(java.util.List.of(stingy), 30.0, need)             // 인색 아내 → 거절
                && !Polygyny.canAccept(java.util.List.of(competitive), 30.0, need)        // 경쟁 아내 → 거절
                && !Polygyny.canAccept(java.util.List.of(tolerant), 20.0, need)           // 부양 미달 → 거절
                && !Polygyny.canAccept(java.util.List.of(tolerant, tolerant), 99.0, need); // 상한 2처 → 거절
        boolean g2 = Polygyny.wifeObjects(stingy) && Polygyny.wifeObjects(competitive)
                && !Polygyny.wifeObjects(tolerant)
                && Polygyny.MARRIED_CHARM_PENALTY == 2 && Polygyny.MAX_WIVES == 2;
        report.add("polygyny/게이트", g1 && g2,
                "아내 용인(인색·경쟁 없음)·저장고 3일치·상한 2처 전부 통과 시만 수락 — 기혼 감점 2",
                (g1 && g2) ? "정상" : "어긋남");
    }

    // ──────────────────────────────────────────────────────────────
    // /evotest elder — 노년기: 쿼터 노동·공유 자격·기간·소모·속도·모성애 누출 차단
    // ──────────────────────────────────────────────────────────────
    private static void elder(Report report) {
        Individual plain = one(Sex.MALE);
        Individual resp = one(Sex.MALE, TraitInstance.of(Trait.OVER_RESPONSIBLE));
        Individual irre = one(Sex.MALE, TraitInstance.of(Trait.IRRESPONSIBLE));

        // 쿼터: 기본=필요 / 책임=+2 / 무책임=필요(대신 공유 없음) / 부지런·게으름 곱
        boolean q1 = close(Elder.dailyQuota(plain, 2.0), 2.0)
                && close(Elder.dailyQuota(resp, 2.0), 4.0)
                && close(Elder.dailyQuota(irre, 2.0), 2.0)
                && close(Elder.dailyQuota(one(Sex.MALE, TraitInstance.of(Trait.OVER_RESPONSIBLE),
                        TraitInstance.of(Trait.DILIGENT)), 2.0), 4.8)
                && close(Elder.dailyQuota(one(Sex.MALE, TraitInstance.of(Trait.LAZY)), 2.0), 1.6);
        report.add("elder/쿼터", q1, "기본2.0·책임4.0·무책임2.0·책임부지런4.8·게으름1.6",
                q1 ? "정상" : "어긋남");

        // 공유 자격·노년 기간
        boolean s1 = Elder.sharesLeftover(plain) && Elder.sharesLeftover(resp)
                && !Elder.sharesLeftover(irre)
                && Elder.elderDays(plain) == 8
                && Elder.elderDays(one(Sex.MALE, TraitInstance.of(Trait.HARDY))) == 10
                && Elder.elderDays(one(Sex.MALE, TraitInstance.of(Trait.SICKLY))) == 6;
        report.add("elder/공유기간", s1, "무책임만 안 나눔 · 기간 8일(강건10/병약6)",
                s1 ? "정상" : "어긋남");

        // 능력치: 소모 2.0 · 속도 0.8 · 채집·전투 가능 · 수확 배율 상수 0.5
        boolean c1 = close(FoodEconomy.consumptionPerDay(LifeStage.ELDER, Activity.MOVE, plain, false), 2.0)
                && close(SurvivalRules.moveSpeedFactor(LifeStage.ELDER), 0.8)
                && SurvivalRules.canGather(LifeStage.ELDER, plain)
                && SurvivalRules.canFight(LifeStage.ELDER)
                && close(Elder.FORAGE_MULT, 0.5) && Elder.WORK_END == 6000;
        report.add("elder/능력치", c1, "소모2.0·속도0.8·채집/전투 가능·수확0.5·노동마감6000",
                c1 ? "정상" : "어긋남");

        // 모성애 배율 누출 차단: 노년은 자식 취급 금지(허기·성장 모두 1.0)
        boolean m1 = close(FoodEconomy.maternalHungerMult(LifeStage.ELDER, 1), 1.0)
                && close(FoodEconomy.maternalHungerMult(LifeStage.ELDER, -1), 1.0)
                && close(SurvivalRules.growthMult(LifeStage.ELDER, plain, 1), 1.0)
                && close(FoodEconomy.maternalHungerMult(LifeStage.INFANT, 1), 0.7); // 자식은 유지
        report.add("elder/모성애차단", m1, "노년은 어미 배율 비적용(1.0) — 유아·소년만 적용 유지",
                m1 ? "정상" : "어긋남");
    }

    /** 순수 경제 시뮬 결과. */
    private static final class SimOut {
        boolean starved;
        int firstStarveDay = -1;
        int births;
        boolean larderIntegerAlways = true;
        double minLarder = Double.MAX_VALUE;
        double finalLarder;
    }

    /**
     * 부부(또는 과부)+유아 가구의 순수 경제 시뮬. 하루=20구간(1200틱). 채집자는 노동 8구간에
     * 하루 3트립 수확을 나눠 벌고, 정산은 매 구간(모두 집 가정 — 길찾기는 표현층 몫).
     * 출산: 밤 구간에 canReproduce+쿨다운 3일+상한 5 → 유아 추가·BIRTH_COST 차감.
     */
    private static SimOut foodSim(int days, boolean hasProvider, int infants) {
        SimOut out = new SimOut();
        Individual man = one(Sex.MALE);
        Individual woman = one(Sex.FEMALE);
        java.util.List<FoodEconomy.Eater> fam = new java.util.ArrayList<>();
        FoodEconomy.Eater provider = null;
        if (hasProvider) {
            provider = new FoodEconomy.Eater(man, LifeStage.ADULT, 1.5, true);
            fam.add(provider);
        }
        FoodEconomy.Eater wife = new FoodEconomy.Eater(woman, LifeStage.ADULT, 1.5, true);
        for (int i = 0; i < infants; i++) {
            fam.add(new FoodEconomy.Eater(one(Sex.FEMALE), LifeStage.INFANT, 1.5, true));
        }
        fam.add(wife); // 우선순위: 남편 → 자식 → 아내

        double larder = FoodEconomy.initialLarder(FoodEconomy.nominalDailyNeed(fam));
        int lastBirthDay = -100;
        double perTripIncome = FoodEconomy.tripYield(man) * 3 / 8.0; // 노동 8구간에 3트립 분산

        for (int day = 0; day < days; day++) {
            for (int slot = 0; slot < 20; slot++) {
                // 활동: 채집자는 노동 8구간 MOVE, 4구간 IDLE, 8구간 SLEEP. 비채집자 IDLE 12·SLEEP 8.
                for (FoodEconomy.Eater e : fam) {
                    Activity act;
                    if (e == provider) {
                        act = slot < 8 ? Activity.MOVE : (slot < 12 ? Activity.IDLE : Activity.SLEEP);
                    } else {
                        act = slot < 12 ? Activity.IDLE : Activity.SLEEP;
                    }
                    e.holding -= FoodEconomy.consumptionPerDay(e.stage, act, e.ind, false) / 20.0;
                    if (e.holding <= 0.0) {
                        e.holding = 0.0;
                        out.starved = true;
                        if (out.firstStarveDay < 0) {
                            out.firstStarveDay = day;
                        }
                    }
                }
                if (provider != null && slot < 8) {
                    provider.holding += perTripIncome; // 채집 수입(R2)
                }
                larder = FoodEconomy.settleHome(larder, fam); // R3 (모두 집 가정)
                out.minLarder = Math.min(out.minLarder, larder);
                if (!isInt(larder)) {
                    out.larderIntegerAlways = false;
                }
                // R5: 밤 구간(15) 하루 1회 판정
                if (slot == 15 && hasProvider && out.births < 5 && day - lastBirthDay >= 3) {
                    double need = FoodEconomy.nominalDailyNeed(fam);
                    boolean starving = FoodEconomy.anyStarvingHome(fam);
                    if (FoodEconomy.canReproduce(larder, need, 2, 0, starving)) {
                        larder -= FoodEconomy.BIRTH_COST;
                        fam.add(fam.size() - 1, // 아내 앞(자식 자리)에 삽입
                                new FoodEconomy.Eater(one(Sex.FEMALE), LifeStage.INFANT, 1.5, true));
                        out.births++;
                        lastBirthDay = day;
                    }
                }
            }
        }
        out.finalLarder = larder;
        return out;
    }

    private static boolean isInt(double v) {
        return Math.abs(v - Math.round(v)) < 1e-9;
    }

    /** 성별 + 등급 특성 하나만 가진 검증용 개체. */
    private static Individual graded(Sex sex, Trait trait, int grade) {
        Individual ind = new Individual(0, sex, 0, 0, 1);
        ind.addTrait(TraitInstance.graded(trait, grade));
        return ind;
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

    // ──────────────────────────────────────────────────────────────
    // /evotest lineage — 가계도 순수 연산 (조상 그리드·후손 수 중복 제거)
    // ──────────────────────────────────────────────────────────────
    // ──────────────────────────────────────────────────────────────
    // /evotest farm — 밭 배치 수열·경제 산식 (봉건 밭 경제 M0)
    // ──────────────────────────────────────────────────────────────
    private static void farm(Report report) {
        // 1) 배치 수열: 사용자 지정 순서 재현 — 1칸 → 둘째줄 → 3×3(9) → 5칸3줄(15) → 5칸5줄(25) → 7×7(49)
        var l2 = FarmLayout.layout(2);
        var l9 = FarmLayout.layout(9);
        var l25 = FarmLayout.layout(25);
        boolean seq = l2.get(1)[0] == 0 && l2.get(1)[1] == 1              // 2번째 타일 = 둘째 줄(한 칸 띄움)
                && java.util.Arrays.equals(FarmLayout.footprint(9), new int[] {3, 5})   // 3열×3줄(깊이 5)
                && java.util.Arrays.equals(FarmLayout.footprint(15), new int[] {5, 5})  // 5칸 3줄 = 발자국 5×5
                && java.util.Arrays.equals(FarmLayout.footprint(25), new int[] {5, 9})  // 5칸 5줄
                && java.util.Arrays.equals(FarmLayout.footprint(49), new int[] {7, 13}) // 7칸 7줄
                && l9.size() == 9 && l25.size() == 25
                && FarmLayout.TIERS[2] == 25 && FarmLayout.TIERS[3] == 35;
        // 중복 좌표 없음(수열 무결성)
        var seen = new java.util.HashSet<Long>();
        boolean dup = false;
        for (int[] t : FarmLayout.layout(49)) {
            if (!seen.add((long) t[0] << 32 | (t[1] & 0xffffffffL))) {
                dup = true;
            }
        }
        report.add("farm/배치수열", seq && !dup,
                "2번째=둘째줄 · 발자국 9→3x5, 15→5x5, 25→5x9, 49→7x13 · 49타일 좌표 중복 0",
                (seq && !dup) ? "정상" : "어긋남");

        // 2) 용량·슬롯: 기본 12 · 부지런 14 · 게으름 9 · 노년 6 / 부족분 최소 일감 10 게이트
        Individual man = one(Sex.MALE);
        boolean cap = FarmEconomy.capacity(man, LifeStage.ADULT) == 12
                && FarmEconomy.capacity(one(Sex.MALE, TraitInstance.of(Trait.DILIGENT)), LifeStage.ADULT) == 14
                && FarmEconomy.capacity(one(Sex.MALE, TraitInstance.of(Trait.LAZY)), LifeStage.ADULT) == 9
                && FarmEconomy.capacity(man, LifeStage.ELDER) == 6
                && FarmEconomy.shortfall(25, 24) == 0     // 잔여 1 < 최소일감 → 게시 안 함
                && FarmEconomy.shortfall(35, 24) == 11    // 첫 고용(부부 기준 7칸5줄)
                && FarmEconomy.shortfall(49, 24) == 25
                && FarmEconomy.shortfall(9, 24) == 0;     // 소형 밭 절대 무고용(슬롯0 가드의 순수부)
        report.add("farm/용량슬롯", cap, "C 12/14/9/6 · 부족 25→0(1<10)·35→11·49→25·9→0",
                cap ? "정상" : "어긋남");

        // 3) 지대 회계 항등식 + 비용 체증
        double y = 0.75;
        boolean acct = close(FarmEconomy.tenantShare(y), 0.525) && close(FarmEconomy.ownerShare(y), 0.225)
                && close(FarmEconomy.tenantShare(y) + FarmEconomy.ownerShare(y), y)
                && close(FarmEconomy.newFarmCost(0), 30.0) && close(FarmEconomy.newFarmCost(2), 67.5)
                // 게이트는 타일당 한계비용 비교: 확장 3 < 신규 30/9타일(T1) ≈ 3.33 — 소작 확장 유인 유지
                && FarmEconomy.EXPAND_COST < FarmEconomy.NEW_FARM_BASE / FarmLayout.TIERS[0];
        // 4) 능력 게이트·성장 상한: 무능력 35 캡 / 채집·저장 능력 발현이면 무제한
        boolean gate = !FarmEconomy.canManageLarge(man)
                && FarmEconomy.growthCap(man) == FarmEconomy.SKILL_GATE_TILES
                && FarmEconomy.canManageLarge(one(Sex.MALE, TraitInstance.of(Trait.HERBALIST)))
                && FarmEconomy.growthCap(one(Sex.MALE, TraitInstance.of(Trait.COOK)))
                        == Integer.MAX_VALUE
                && FarmEconomy.EXPAND_PER_DAY == 3
                && close(FarmEconomy.INVEST_RESERVE, 6.0);
        report.add("farm/능력게이트", gate, "무능력 캡 35 · 약초학자/요리사 무제한 · 일일확장 3 · 예비 6",
                gate ? "정상" : "어긋남");

        report.add("farm/지대비용", acct, "0.75→0.525/0.225(합=원액) · 신규 30/67.5 · 확장(3)<신규 타일당(3.33)",
                acct ? "정상" : "어긋남");
    }

    private static void lineage(Report report) {
        // 가계: 조부모 g1(1)·g2(2) → 부모 p1(10)·p2(11, 형제) / p1×외부미상 → c1(20)
        //       p1×p2(근친 가정 아님 — 다이아몬드 검증용 형제혼 모형) → c2(21)
        java.util.Map<Long, long[]> parents = new java.util.HashMap<>();
        parents.put(10L, new long[] {1L, 2L});
        parents.put(11L, new long[] {1L, 2L});
        parents.put(20L, new long[] {10L, 0L});
        parents.put(21L, new long[] {10L, 11L});

        long[][] grid = Lineage.ancestorGrid(20L, 2, parents);
        boolean g = grid[0][0] == 20L
                && grid[1][0] == 10L && grid[1][1] == 0L              // 부모: p1 · 미상
                && grid[2][0] == 1L && grid[2][1] == 2L               // p1 의 부모
                && grid[2][2] == 0L && grid[2][3] == 0L;              // 미상의 부모는 미상
        report.add("lineage/조상그리드", g,
                "focus→부모→조부모 2^d 배치 · 미상(0) 전파",
                g ? "정상" : java.util.Arrays.deepToString(grid));

        var idx = Lineage.childrenIndex(parents);
        // g1(1)의 후손 = {p1, p2, c1, c2} — c2 는 p1·p2 양쪽 경로로 닿지만 1번만 센다.
        boolean d = Lineage.descendantCount(1L, idx) == 4
                && Lineage.descendantCount(10L, idx) == 2   // c1, c2
                && Lineage.descendantCount(20L, idx) == 0   // 자식 없음
                && Lineage.childCount(1L, idx) == 2;        // 직접 자식 p1·p2
        report.add("lineage/후손수", d,
                "다이아몬드(양가 경로) 중복 제거 · 직접 자식 수",
                d ? "정상" : String.format("g1후손 %d · p1후손 %d",
                        Lineage.descendantCount(1L, idx), Lineage.descendantCount(10L, idx)));
    }

}
