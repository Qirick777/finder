package com.evosim.mod.perf;

import net.minecraft.world.entity.ai.goal.Goal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>연산 계측</b> — 어디에 틱 시간이 가는지 값으로 가른다(유흥·시장 층을 얹기 전 여유 확보용).
 *
 * <p>켜져 있을 때만 잰다(기본 꺼짐). 항목: 미믹 tick 합계, 길찾기 호출 수·시간·부분경로/실패 수,
 * 이벤트 로그 쓰기 시간, goal 별 canUse/tick 시간. goal 은 {@link TimedGoal} 로 감싸 재고,
 * 밖에서 goal 종류를 볼 때는 {@link #unwrap} 으로 벗긴다.
 */
public final class Perf {
    private Perf() {
    }

    public static volatile boolean on = false;
    private static long sinceNanos;
    private static long mimicTickNs;
    private static long mimicTicks;
    private static long pathNs;
    private static long pathCalls;
    private static long pathPartial;
    private static long pathNull;
    private static long logNs;
    private static long logLines;
    private static long serverTicks;
    /** 종류 → [canUseNs, canUseCalls, tickNs, tickCalls, contNs, contCalls] */
    private static final Map<String, long[]> GOALS = new HashMap<>();

    public static void reset() {
        sinceNanos = System.nanoTime();
        mimicTickNs = mimicTicks = pathNs = pathCalls = pathPartial = pathNull = logNs = logLines = serverTicks = 0;
        GOALS.clear();
        PATH_WHO.clear();
    }

    public static void serverTick() {
        if (on) {
            serverTicks++;
        }
    }

    public static void mimicTick(long ns) {
        mimicTickNs += ns;
        mimicTicks++;
    }

    public static void path(long ns, boolean nul, boolean partial) {
        pathNs += ns;
        pathCalls++;
        if (nul) {
            pathNull++;
        } else if (partial) {
            pathPartial++;
        }
    }

    /** 비싼 경로 계산(≥0.5ms)의 내역 — 부른 goal · 부분경로 여부 · 거리 구간 → [건수, ns 합]. */
    private static final Map<String, long[]> PATH_WHO = new HashMap<>();

    public static void pathDetail(String caller, boolean partial, double dist, long ns) {
        if (ns < 500_000L) {
            return;
        }
        String d = dist < 16 ? "<16" : dist < 48 ? "16-48" : dist < 96 ? "48-96" : dist < 160 ? "96-160" : "160+";
        String k = caller + (partial ? " 부분" : " 완주") + " " + d;
        long[] a = PATH_WHO.computeIfAbsent(k, x -> new long[2]);
        a[0]++;
        a[1] += ns;
    }

    public static void log(long ns) {
        logNs += ns;
        logLines++;
    }

    static void goal(String name, int slot, long ns) {
        long[] a = GOALS.computeIfAbsent(name, k -> new long[6]);
        a[slot] += ns;
        a[slot + 1]++;
    }

    public static Goal timed(Goal g) {
        return new TimedGoal(g);
    }

    public static Goal unwrap(Goal g) {
        return g instanceof TimedGoal t ? t.inner : g;
    }

    public static String report(double avgTickMs) {
        double sec = Math.max(1e-9, (System.nanoTime() - sinceNanos) / 1e9);
        double ticks = Math.max(1, serverTicks);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[계측] %.0f초 · 서버 %d틱(평균 틱 %.2fms) · 미믹 tick 합 %.2fms/틱(%d회/틱)%n", sec, serverTicks,
                avgTickMs, mimicTickNs / 1e6 / ticks, (long) (mimicTicks / ticks)));
        sb.append(String.format("  길찾기 %.2fms/틱 · 호출 %.1f/틱 · 부분경로 %.1f/틱 · 실패 %.1f/틱 · 호출당 %.3fms%n", pathNs / 1e6 / ticks,
                pathCalls / ticks, pathPartial / ticks, pathNull / ticks, pathCalls == 0 ? 0.0 : pathNs / 1e6 / pathCalls));
        sb.append(String.format("  이벤트 로그 %.2fms/틱 · %.1f줄/틱%n", logNs / 1e6 / ticks, logLines / ticks));
        List<Map.Entry<String, long[]>> who = new ArrayList<>(PATH_WHO.entrySet());
        who.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
        sb.append("  비싼 경로(≥0.5ms) 내역 — 부른 goal · 결과 · 거리:\n");
        int w = 0;
        for (var r : who) {
            if (w++ >= 8) {
                break;
            }
            sb.append(String.format("   %-28s %.2fms/틱 · %.2f회/틱 · 건당 %.2fms%n", r.getKey(), r.getValue()[1] / 1e6 / ticks,
                    r.getValue()[0] / ticks, r.getValue()[1] / 1e6 / r.getValue()[0]));
        }
        List<Map.Entry<String, long[]>> rows = new ArrayList<>(GOALS.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue()[0] + b.getValue()[2] + b.getValue()[4],
                a.getValue()[0] + a.getValue()[2] + a.getValue()[4]));
        long total = 0;
        for (var r : rows) {
            total += r.getValue()[0] + r.getValue()[2] + r.getValue()[4];
        }
        sb.append(String.format("  goal 합계 %.2fms/틱 — 상위:%n", total / 1e6 / ticks));
        int n = 0;
        for (var r : rows) {
            long[] a = r.getValue();
            long sum = a[0] + a[2] + a[4];
            if (n++ >= 12) {
                break;
            }
            sb.append(String.format("   %-22s %.3fms/틱 (%.0f%%) — canUse %.3f(%.0f회) · tick %.3f(%.0f회) · cont %.3f%n", r.getKey(),
                    sum / 1e6 / ticks, total == 0 ? 0.0 : 100.0 * sum / total, a[0] / 1e6 / ticks, a[1] / ticks, a[2] / 1e6 / ticks,
                    a[3] / ticks, a[4] / 1e6 / ticks));
        }
        return sb.toString();
    }

    /** goal 감싸기 — 플래그·중단 가능·매틱 갱신 여부를 그대로 넘기고 시간만 잰다. */
    static final class TimedGoal extends Goal {
        final Goal inner;
        final String name;

        TimedGoal(Goal inner) {
            this.inner = inner;
            this.name = inner.getClass().getSimpleName().replace("Mimic", "").replace("Goal", "");
            setFlags(inner.getFlags());
        }

        @Override
        public EnumSet<Flag> getFlags() {
            return inner.getFlags();
        }

        @Override
        public boolean canUse() {
            if (!on) {
                return inner.canUse();
            }
            long t = System.nanoTime();
            boolean r = inner.canUse();
            goal(name, 0, System.nanoTime() - t);
            return r;
        }

        @Override
        public boolean canContinueToUse() {
            if (!on) {
                return inner.canContinueToUse();
            }
            long t = System.nanoTime();
            boolean r = inner.canContinueToUse();
            goal(name, 4, System.nanoTime() - t);
            return r;
        }

        @Override
        public boolean isInterruptable() {
            return inner.isInterruptable();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return inner.requiresUpdateEveryTick();
        }

        @Override
        public void start() {
            inner.start();
        }

        @Override
        public void stop() {
            inner.stop();
        }

        @Override
        public void tick() {
            if (!on) {
                inner.tick();
                return;
            }
            long t = System.nanoTime();
            inner.tick();
            goal(name, 2, System.nanoTime() - t);
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
