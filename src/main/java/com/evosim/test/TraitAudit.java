package com.evosim.test;

import com.evosim.core.DeterministicRng;
import com.evosim.core.FarmEconomy;
import com.evosim.core.Genetics;
import com.evosim.core.Individual;
import com.evosim.core.Multipliers;
import com.evosim.core.Physique;
import com.evosim.core.Schedule;
import com.evosim.core.Sex;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>인구 능력 분포 계측</b> — 특성 개편이 "엘리트만 올리고 평민은 안 올렸는가"를 수치로 판정한다.
 *
 * <p>고정 시드로 1세대 개체를 대량 생성해 개체별 소득 능력을 계산하고 평균·중앙값·상위 5%·최대를
 * 낸다. 개편 <b>전</b> 값을 먼저 찍어 두고, 개편 <b>후</b> 같은 시드로 다시 재서 비교한다.
 *
 * <p>합격 조건: 평균·중앙값·상위 5% 가 전부 기준선 <b>이하</b>. 최대값만 오르는 것은 통과다 —
 * 엘리트가 바로 그 최대값이기 때문이다. 하나라도 오르면 평민이 함께 커진 것이므로 실패다.
 *
 * <p>순수 함수만 쓴다(마인크래프트 없음): {@code ./gradlew evotest --args="audit [시드] [인원]"}.
 *
 * <p><b>종합 배율</b>은 세 채널의 곱이다 — 배율(gather) × 회전(1/쿨다운) × 시간(노동틱/기본 7000).
 * 세 채널을 따로 보지 않으면 "채집 계수를 깎았으니 약해졌다"는 식의 반쪽 보고가 나온다: 회전과
 * 시간이 그 이상으로 오르면 실제 소득은 커진다.
 */
public final class TraitAudit {

    /** 기준 노동창(틱) — 무특성 개체의 WORK 구간 길이(1000~8000). 시간 채널의 분모. */
    private static final int BASE_WORK_TICKS = 7000;

    private TraitAudit() {
    }

    /** 개체 하나의 소득 능력 — 계측 전용 값 묶음. */
    public record Row(double gather, double garden, int capacity,
                      double cooldown, int workTicks, double total) {
    }

    /**
     * 한 개체의 소득 능력.
     *
     * <p>노동틱은 {@link Schedule} 을 하루 전체(24000틱) 훑어 WORK 구간을 세고, 배회 노동
     * 조건({@link Multipliers#brightDriven})을 충족하면 WANDER 구간도 더한다 —
     * {@code MimicForageGoal} 의 문지기와 <b>같은 술어</b>를 읽어야 계측이 조용히 거짓말을
     * 하지 않는다.
     */
    public static Row measure(Individual ind) {
        double g = Multipliers.gather(ind);
        double garden = Multipliers.gardenAbility(ind);
        int cap = FarmEconomy.manageCapacity(ind);
        double cd = Physique.actionCooldown(ind);
        boolean wander = Multipliers.brightDriven(ind);
        int work = 0;
        for (int t = 0; t < Schedule.DAY; t++) {
            Schedule.Phase p = Schedule.phaseAt(ind, t);
            if (p == Schedule.Phase.WORK || (wander && p == Schedule.Phase.WANDER)) {
                work++;
            }
        }
        double total = g * (1.0 / cd) * ((double) work / BASE_WORK_TICKS);
        return new Row(g, garden, cap, cd, work, total);
    }

    /** 한 축의 분포 요약 — 평균 · 중앙값 · 상위 5% · 최대. */
    public record Stat(String name, double mean, double median, double p95, double max) {
        @Override
        public String toString() {
            return String.format("%-10s 평균 %7.3f · 중앙 %7.3f · 상위5%% %7.3f · 최대 %8.3f",
                    name, mean, median, p95, max);
        }
    }

    private static Stat stat(String name, double[] v) {
        double[] s = v.clone();
        java.util.Arrays.sort(s);
        double sum = 0;
        for (double d : s) {
            sum += d;
        }
        return new Stat(name, sum / s.length, s[s.length / 2],
                s[(int) Math.min(s.length - 1L, Math.round(s.length * 0.95))], s[s.length - 1]);
    }

    /** 시드·인원으로 1세대 인구를 생성해 분포를 낸다. 성별은 남녀 교대(성별 편향 제거). */
    public static List<String> report(long seed, int n) {
        DeterministicRng rng = new DeterministicRng(seed);
        double[] gather = new double[n];
        double[] garden = new double[n];
        double[] cap = new double[n];
        double[] cool = new double[n];
        double[] work = new double[n];
        double[] total = new double[n];
        for (int i = 0; i < n; i++) {
            Individual ind = Genetics.randomFirstGen(i + 1L, rng, i % 2 == 0 ? Sex.MALE : Sex.FEMALE);
            Row r = measure(ind);
            gather[i] = r.gather();
            garden[i] = r.garden();
            cap[i] = r.capacity();
            cool[i] = r.cooldown();
            work[i] = r.workTicks();
            total[i] = r.total();
        }
        List<String> out = new ArrayList<>();
        out.add(String.format("=== 인구 능력 분포 (시드 %d · %d명) ===", seed, n));
        out.add(stat("채집배율", gather).toString());
        out.add(stat("정원배율", garden).toString());
        out.add(stat("관리용량", cap).toString());
        out.add(stat("쿨다운", cool).toString());
        out.add(stat("노동틱", work).toString());
        out.add(stat("종합", total).toString());
        return out;
    }
}
