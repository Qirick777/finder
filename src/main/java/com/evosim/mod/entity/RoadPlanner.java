package com.evosim.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 길 경로 계산 — 새 집의 <b>진입 칸</b>에서 <b>이미 있는 도로망</b>까지 한 가닥.
 *
 * <p>설계 검토(평면도 위 20일 시뮬레이션)에서 확정한 규칙 그대로다.
 *
 * <h3>① 절대 못 지나는 칸</h3>
 * 집 지면층 · <b>문앞 계단</b> · <b>밭 몸통</b>. 계단은 앵커−1층이라 그 칸에 흙길을 깔면
 * 계단 밑에 묻히고, {@code dirt_path} 는 위에 고체가 오면 흙으로 되돌아간다.
 * 밭은 <b>타일이 아니라 몸통</b>(타일 + 그 사이 고랑)을 막는다 — 타일만 막으면 길이 고랑을
 * 타고 밭 한복판을 꿰뚫는다(실측: 폭3 길 2090칸 중 33칸이 두 구획을 관통했다).
 *
 * <h3>② 꺾임 벌점</h3>
 * 방향이 바뀌면 비용을 더 문다. 없으면 최단경로가 계단처럼 지저분해진다.
 *
 * <h3>③ 재사용 할인</h3>
 * 이미 길인 칸은 싸다. 새 가닥이 기존 길에 <b>합류</b>하게 만드는 장치이고, 없으면 집마다
 * 제 길을 따로 내서 거미줄이 된다.
 *
 * <h3>④ 밭 옆 벌점</h3>
 * 밭 몸통 2칸 이내는 비싸다. <b>막지는 않는다</b> — 하드 금지로 두면 밭 사이에 낀 집이 아예
 * 연결되지 못한다(연결성이 미관보다 앞선다).
 */
public final class RoadPlanner {

    /** 4방향만 쓴다 — 대각을 허용하면 폭 1 구간이 바둑판처럼 끊겨 보인다. */
    public static final int[][] D4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** 꺾임 1회의 추가 비용(칸 단위). */
    private static final double TURN = 2.2;
    /** 기존 길 칸의 비용 배율 — 합류를 유도한다. */
    private static final double REUSE = 0.15;
    /** 밭 몸통 근처 칸의 추가 비용. */
    private static final double NEAR_FARM = 6.0;
    /** 밭 옆 벌점이 붙는 반경. */
    private static final int NEAR_R = 2;
    /** 신축 가닥 길이 상한 — 넘으면 포기(고립 부지). 언덕은 돌아가므로 평지보다 넉넉해야 한다. */
    public static final int MAX_SPUR = 260;
    /** <b>우회로</b> 길이 상한 — 밭 몸통을 돌아야 해서 42칸까지 필요했다(실측). 40은 실패한다. */
    public static final int MAX_BYPASS = 80;
    /** 탐색 범위 상한(진입 칸 기준 반경) — 폭주 방지. */
    private static final int RANGE = 220;
    /** 이웃 칸과 허용하는 <b>높이차</b>. 1이면 계단 한 칸 — 미믹이 걸어 올라갈 수 있는 폭이다. */
    private static final int MAX_STEP_UP = 1;

    /**
     * 이 열의 <b>딛는 지면</b> Y — 없으면 {@link Integer#MIN_VALUE}.
     *
     * <p>물·용암 위는 지면이 아니다(길을 깔 수도, 걸어갈 수도 없다). 청크가 안 열렸으면
     * 판단을 미룬다 — 없는 것으로 치면 마을 밖으로 못 나가는 길이 된다.
     */
    public static int surfaceY(ServerLevel sl, int x, int z) {
        if (!sl.hasChunk(x >> 4, z >> 4)) {
            return Integer.MIN_VALUE;
        }
        int top = sl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types
                .MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        var st = sl.getBlockState(new net.minecraft.core.BlockPos(x, top, z));
        if (st.getFluidState().isEmpty() && !st.isAir()) {
            return top;
        }
        return Integer.MIN_VALUE;
    }

    private RoadPlanner() {
    }

    /**
     * 진입 칸 후보 — 계단 앞 한 칸이 기본. 막혔으면 좌우로, 그다음 한 칸 더 밖으로 물러난다.
     * (문 앞이 밭 고랑에 들어앉은 집이 실제로 있었다.)
     */
    public static List<BlockPos> entries(ServerLevel sl, HomeBlueprint bp, Obstacles ob) {
        List<BlockPos> stairs = bp.doorSteps();
        Direction d = bp.doorDir();
        int dx = d.getStepX();
        int dz = d.getStepZ();
        int px = -dz;
        int pz = dx;
        // <b>우선순위대로</b> 한 겹씩 시도한다. 계단 정면(off 0)이 열려 있으면 거기서 끝이다 —
        // 후보를 한꺼번에 담으면 다익스트라가 그중 제일 싼 것을 고르는데, 그게 옆으로 두 칸
        // 비낀 칸이면 길이 <b>문 정면이 아니라 집 모서리에서</b> 시작한다(실측: 5채 중 4채).
        int[][] tiers = {{1, 0}, {1, -1}, {1, 1}, {2, 0}, {1, -2}, {1, 2}, {2, -1}, {2, 1}};
        for (int[] t : tiers) {
            List<BlockPos> out = new ArrayList<>();
            for (BlockPos s : stairs) {
                int ex = s.getX() + dx * t[0] + px * t[1];
                int ez = s.getZ() + dz * t[0] + pz * t[1];
                int ey = surfaceY(sl, ex, ez);
                BlockPos c = new BlockPos(ex, ey, ez);
                if (ey != Integer.MIN_VALUE && !ob.blocked(ex, ez) && !out.contains(c)) {
                    out.add(c);
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        return List.of();
    }

    /** 통행 금지 판정의 단일 출처 — 집·계단·밭 몸통. */
    public static final class Obstacles {
        private final Set<Long> hard = new HashSet<>();
        private final Set<Long> soft = new HashSet<>();

        private static Obstacles cache;
        private static long cacheTick = Long.MIN_VALUE;

        /**
         * 등기된 모든 거처의 지면층·계단 + 모든 밭 <b>몸통</b>을 모은다.
         *
         * <p><b>틱 캐시가 필수다.</b> 이 계산은 집 수 × 발자국 + 밭 몸통 × 25(둘레 5×5) 라
         * 마을이 서른 채만 되어도 삽입이 2~3만 회다. 길은 6틱마다 한 칸씩 깔리므로 캐시가
         * 없으면 그 비용을 시공 내내 되문다. 집·밭은 하루 단위로나 바뀌므로 20틱이면 넉넉하다.
         */
        public static Obstacles of(ServerLevel sl) {
            long now = com.evosim.mod.entity.SimTime.tick(sl);
            if (cache != null && now - cacheTick < 20L && now >= cacheTick) {
                return cache;
            }
            cacheTick = now;
            cache = build(sl);
            return cache;
        }

        /** 캐시 무효화 — 밭이 길을 먹은 직후처럼 <b>즉시</b> 반영해야 할 때. */
        public static void invalidate() {
            cache = null;
        }

        /**
         * <b>집 한 채가 막는 열쇠들</b> — 지면층 발자국 + 문 앞 계단. 집마다 캐시한다.
         *
         * <p>{@link #build}는 20틱마다 도는데, 종전에는 집집마다 {@link HomeBlueprint#of}를 불러
         * <b>도면 전체</b>(배치 목록과 리스트 예닐곱 개, 저택은 수백 블록)를 새로 만들고는
         * 그중 두 리스트만 읽고 버렸다. 실제로 필요한 것은 여기 담긴 {@code long} 몇십 개뿐이다.
         *
         * <p>값이 안 바뀌는 근거: {@code HomeBlueprint.of}는 월드를 읽지 않는다(블록 조회·
         * 하이트맵·난수 0건). 오직 (위치·도면·회전·반전)으로 결정되므로, 열쇠에 그 넷을 모두
         * 넣으면 재사용되는 항목은 <b>어차피 내용이 같은</b> 항목뿐이다.
         *
         * <p>도면 객체 자체가 아니라 {@code long} 목록만 붙드는 이유는 메모리다 — 도면을 캐시하면
         * 금방 회수될 쓰레기를 오히려 상주시킨다. 또 등기된 집만 열쇠가 되므로, 부지 후보를
         * 위치마다 평가하는 경로가 캐시를 오염시키지 않는다.
         */
        private static final java.util.HashMap<String, long[]> HOME_KEYS = new java.util.HashMap<>();

        private static long[] homeKeys(ServerLevel sl, BlockPos h, HomeStore.Entry e) {
            String ck = h.asLong() + "|" + e.design() + '|' + e.rotation() + '|' + e.mirrored();
            long[] hit = HOME_KEYS.get(ck);
            if (hit != null) {
                return hit;
            }
            HomeBlueprint bp = HomeBlueprint.of(sl, h, e.design(), e.rotation(), e.mirrored());
            java.util.LinkedHashSet<Long> keys = new java.util.LinkedHashSet<>();
            for (BlockPos c : bp.groundFootprint()) {
                keys.add(RoadStore.key(c.getX(), c.getZ()));
            }
            for (BlockPos c : bp.doorSteps()) {
                keys.add(RoadStore.key(c.getX(), c.getZ()));
            }
            long[] made = new long[keys.size()];
            int i = 0;
            for (long k : keys) {
                made[i++] = k;
            }
            if (HOME_KEYS.size() >= 4096) {
                HOME_KEYS.clear(); // 이사·재건축이 오래 쌓인 경우에만 — 다음 번에 다시 채워진다
            }
            HOME_KEYS.put(ck, made);
            return made;
        }

        private static Obstacles build(ServerLevel sl) {
            Obstacles ob = new Obstacles();
            HomeStore reg = HomeStore.get(sl);
            for (BlockPos h : reg.positions()) {
                HomeStore.Entry e = reg.entry(h);
                if (e == null) {
                    continue;
                }
                for (long k : homeKeys(sl, h, e)) {
                    ob.hard.add(k);
                }
            }
            // <b>시설 몸통</b>(학교·교회) — 길이 건물을 관통하지 않게. 거처·밭·가로등은 이미
            // 넣고 있었는데 시설만 빠져 있었다. 문 앞 칸({@code doorSteps})은 <b>빼지 않는다</b> —
            // 거기가 길의 종점이라야 학교에 길이 닿는다.
            FacilityStore fr = FacilityStore.get(sl);
            for (FacilityStore.Entry fe : fr.all()) {
                var ft = FacilityTemplate.of(sl, fe.kind, fe.rotation, fe.mirrored);
                if (ft.isEmpty()) {
                    continue;
                }
                java.util.Set<Long> steps = new java.util.HashSet<>();
                for (BlockPos d : ft.get().doorSteps()) {
                    steps.add(RoadStore.key(fe.pos.getX() + d.getX(), fe.pos.getZ() + d.getZ()));
                }
                for (BlockPos c : ft.get().groundCols()) {
                    long k = RoadStore.key(fe.pos.getX() + c.getX(), fe.pos.getZ() + c.getZ());
                    if (!steps.contains(k)) {
                        ob.hard.add(k);
                    }
                }
            }
            // 가로등 기둥 — 울타리라 통행을 막는다. 등은 길 바깥에 서지만, 나중에 나는 길이
            // 그 칸을 지나면 새 길 한복판에 기둥이 박힌다. 길이 알아서 비껴가게 둔다.
            ob.hard.addAll(LampPlanner.postColumns(sl));
            // 가로수 밑동과 분수 몸통 — 가로등 기둥과 같은 이유로 막는다. 등기해 두지 않으면
            // 나중에 나는 길이 나무를 관통하거나 분수 한복판을 지난다. 나뭇잎은 머리 위라
            // 통행을 막지 않으므로 <b>밑동 한 칸</b>만 넣는다(기둥과 같은 취급).
            StreetStore street = StreetStore.get(sl);
            for (BlockPos t : street.all(false)) {
                ob.hard.add(RoadStore.key(t.getX(), t.getZ()));
            }
            for (BlockPos f : street.all(true)) {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        ob.hard.add(RoadStore.key(f.getX() + dx, f.getZ() + dz));
                    }
                }
            }
            // 밭 몸통과 그 둘레(NEAR_R)를 부드러운 회피로 표시한다.
            //
            // 몸통 칸은 <b>넣지 않는다</b> — 바로 위에서 hard 에 들어갔고 아래 removeAll 로
            // 어차피 빠진다. 몸통 칸마다 25개씩 넣던 것이 태반 이 경우였다(칸 수백 개면 만
            // 단위의 Long 박싱). 결과 집합은 그대로다.
            java.util.Set<Long> body = FarmStore.get(sl).bodyColumns();
            ob.hard.addAll(body);
            for (long l : body) {
                int x = RoadStore.keyX(l);
                int z = RoadStore.keyZ(l);
                for (int ax = -NEAR_R; ax <= NEAR_R; ax++) {
                    for (int az = -NEAR_R; az <= NEAR_R; az++) {
                        long k = RoadStore.key(x + ax, z + az);
                        if (!body.contains(k)) {
                            ob.soft.add(k);
                        }
                    }
                }
            }
            ob.soft.removeAll(ob.hard);
            return ob;
        }

        public boolean blocked(int x, int z) {
            return hard.contains(RoadStore.key(x, z));
        }

        boolean nearFarm(int x, int z) {
            return soft.contains(RoadStore.key(x, z));
        }
    }

    /**
     * 진입 칸 → 도로망 최단(비용) 경로. 도로망이 비어 있으면 문 앞 짧은 도막을 돌려준다
     * (첫 짝은 이을 곳이 없다 — 그 도막이 도로망의 뿌리가 된다).
     */
    public static List<BlockPos> planSpur(ServerLevel sl, List<BlockPos> starts, RoadStore roads,
                                          Obstacles ob) {
        if (starts.isEmpty()) {
            return List.of();
        }
        if (roads.size() == 0) {
            return rootStub(starts.get(0), ob);
        }
        return dijkstra(sl, starts, roads.raw(), ob, MAX_SPUR);
    }

    /** 첫 집의 문 앞 도막 — 진입 칸에서 문이 보는 쪽으로 곧게 3칸. */
    private static List<BlockPos> rootStub(BlockPos entry, Obstacles ob) {
        List<BlockPos> out = new ArrayList<>();
        out.add(entry);
        return out;
    }

    /** 조각 → 본체 우회로. 밭 몸통을 돌아가야 하므로 상한이 넉넉해야 한다. */
    public static List<BlockPos> planBypass(ServerLevel sl, Set<Long> from, Set<Long> to,
                                            Obstacles ob, int y) {
        List<BlockPos> starts = new ArrayList<>();
        for (long l : from) {
            starts.add(RoadStore.posOf(l, y));
            if (starts.size() >= 64) {
                break;
            }
        }
        return dijkstra(sl, starts, to, ob, MAX_BYPASS);
    }

    private static List<BlockPos> dijkstra(ServerLevel sl, List<BlockPos> starts, Set<Long> goals,
                                           Obstacles ob, int cap) {
        Map<Long, Integer> surf = new HashMap<>();
        int cx = starts.get(0).getX();
        int cz = starts.get(0).getZ();
        java.util.function.BiFunction<Integer, Integer, Integer> sy = (x, z) ->
                surf.computeIfAbsent(RoadStore.key(x, z), k -> surfaceY(sl, x, z));
        Map<Long, Double> dist = new HashMap<>();
        Map<Long, Long> prev = new HashMap<>();
        PriorityQueue<double[]> pq = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        for (BlockPos s : starts) {
            long st = state(s.getX(), s.getZ(), 4);
            dist.put(st, 0.0);
            pq.add(new double[] {0.0, s.getX(), s.getZ(), 4});
        }
        long best = Long.MIN_VALUE;
        while (!pq.isEmpty()) {
            double[] cur = pq.poll();
            int x = (int) cur[1];
            int z = (int) cur[2];
            int pd = (int) cur[3];
            long st = state(x, z, pd);
            Double dv = dist.get(st);
            if (dv == null || cur[0] > dv + 1e-9) {
                continue;
            }
            if (pd != 4 && goals.contains(RoadStore.key(x, z))) {
                best = st;
                break;
            }
            for (int i = 0; i < 4; i++) {
                int nx = x + D4[i][0];
                int nz = z + D4[i][1];
                if (Math.abs(nx - cx) > RANGE || Math.abs(nz - cz) > RANGE) {
                    continue;
                }
                if (ob.blocked(nx, nz)) {
                    continue;
                }
                // <b>경사 제약</b> — 이웃과 높이차가 1을 넘으면 못 간다. 이게 없으면 경로가
                // 절벽·호수를 2D 로 가로지르고, 그 칸은 포장도 안 되며 놓으러 갈 수도 없다
                // (실측: 일반 지형에서 중심선의 58%가 블록을 한 칸도 못 깔았다).
                int hy = sy.apply(nx, nz);
                int cy = sy.apply(x, z);
                if (hy == Integer.MIN_VALUE || cy == Integer.MIN_VALUE
                        || Math.abs(hy - cy) > MAX_STEP_UP) {
                    continue;
                }
                double step = 1.0;
                if (goals.contains(RoadStore.key(nx, nz))) {
                    step *= REUSE;
                }
                if (ob.nearFarm(nx, nz)) {
                    step += NEAR_FARM;
                }
                if (pd != 4 && pd != i) {
                    step += TURN;
                }
                long ns = state(nx, nz, i);
                double nd = cur[0] + step;
                Double old = dist.get(ns);
                if (old == null || nd < old - 1e-9) {
                    dist.put(ns, nd);
                    prev.put(ns, st);
                    pq.add(new double[] {nd, nx, nz, i});
                }
            }
        }
        if (best == Long.MIN_VALUE) {
            return List.of();
        }
        List<BlockPos> out = new ArrayList<>();
        long cur = best;
        while (true) {
            int px = sx(cur);
            int pz = sz(cur);
            out.add(new BlockPos(px, sy.apply(px, pz), pz)); // 칸마다 <b>제</b> 지표 높이
            Long p = prev.get(cur);
            if (p == null) {
                break;
            }
            cur = p;
        }
        if (out.size() > cap) {
            return List.of();
        }
        java.util.Collections.reverse(out);
        return out;
    }

    /** 상태 = (x, z, 들어온 방향). 방향을 넣어야 꺾임을 벌할 수 있다. */
    private static final int OFF = 1 << 20;

    private static long state(int x, int z, int dir) {
        return ((long) (x + OFF) << 43) | ((long) (z + OFF) << 22) | (dir & 7);
    }

    private static int sx(long s) {
        return (int) ((s >>> 43) & 0x1FFFFFL) - OFF;
    }

    private static int sz(long s) {
        return (int) ((s >>> 22) & 0x1FFFFFL) - OFF;
    }

    /**
     * 중심선 한 칸의 <b>폭 3 띠</b> — 3×3 중 통행 금지·밭 옆이 아닌 칸.
     *
     * <p>밭 <b>바로 옆</b>으로는 넓히지 않는다. 넓히기는 미관이고 밭은 생계다 — 부딪히면
     * 언제나 밭이 이긴다. 그래서 밭 사이나 집 옆을 지날 때 길이 저절로 2칸·1칸으로 좁아진다
     * (실측: 중심선의 24%가 3×3을 다 못 채운다 — 획일적 대로가 아니라 마을길로 읽히는 이유).
     */
    public static List<BlockPos> band(ServerLevel sl, BlockPos center, Obstacles ob,
                                      FarmStore farms) {
        List<BlockPos> out = new ArrayList<>(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                if (ob.blocked(x, z)) {
                    continue;
                }
                if ((dx != 0 || dz != 0) && farms.nearBody(x, z, 1)) {
                    continue; // 밭 바로 옆으로는 살을 안 찌운다(중심선 자체는 통과 허용)
                }
                out.add(new BlockPos(x, center.getY(), z));
            }
        }
        return out;
    }

    /** 이 칸에 흙길을 깔아도 되는 지면인가 — 잔디·흙 계열만, 물·구조물 위는 금지. */
    public static boolean pavable(ServerLevel sl, BlockPos ground) {
        var s = sl.getBlockState(ground);
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT)
                || s.is(Blocks.PODZOL) || s.is(Blocks.ROOTED_DIRT);
    }
}
