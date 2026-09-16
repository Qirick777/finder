package com.evosim.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>이정표 망 경로표</b> — 이정표(길 칸)와 시설(바깥 문 칸)을 마디로, 직선 {@link #LINK} 안의 마디끼리 잇고
 * 마디마다 "어느 마디로 가려면 다음은 어디"를 너비우선탐색으로 뽑아 둔다.
 *
 * <p>파생 자료라 저장하지 않는다. 이정표·시설 등기, 밤 정산, 월드 교체 때 더러움 표시가 붙고 다음 조회에서
 * 다시 만든다(마디 N ≤ 수십 · N번의 BFS). 틱마다 하는 일은 없다 — 길찾기는 {@link #via} 한 번에 최근접 마디
 * 두 번 찾기와 표 조회뿐이다.
 *
 * <p>거리 규약(설계서 §2): 시설이 이정표의 인지반경 안이면 <b>거리 0</b>(마디 간선 1개), 이정표 하나를 더 거치면
 * 거리 1. 종류별 상한은 {@link #hopCap} — 학교 1 · 교회 2 · 병원 2 · 대학 3, 나머지는 망을 쓰지 않는다.
 * 자격 판정({@link #reaches})은 "집에서 가장 가까운 이정표(96 안)에서 그 시설까지의 거리 ≤ 상한"이다.
 */
public final class RelayNet {

    private RelayNet() {
    }

    /** 마디 사이 연결 거리(직선). 이정표 간격 96 이 사거리 160 안에 들도록 여유를 조금 둔다. */
    public static final double LINK = 100.0;
    /** 집·표적에서 이정표를 "내 이정표"로 치는 거리(인지반경). */
    public static final double STATION_REACH = 96.0;
    /** 이보다 가까운 표적은 곧장 낸다(망을 쓰지 않는다). */
    public static final double DIRECT = 120.0;

    /** 종류별 거리 상한 — 0 이면 망을 쓰지 않는 시설. */
    public static int hopCap(FacilityTemplate.Group g) {
        return switch (g) {
            case SCHOOL -> 1;
            case CHURCH, HOSPITAL -> 2;
            case UNIVERSITY -> 3;
            default -> 0;
        };
    }

    /** 마디 — 이정표(길 칸) 또는 시설(바깥 문 칸). */
    public static final class Node {
        public final BlockPos pos;
        @Nullable
        public final FacilityStore.Entry facility;
        @Nullable
        public final SignpostStore.Post post;
        final int index;

        Node(int index, BlockPos pos, @Nullable FacilityStore.Entry facility, @Nullable SignpostStore.Post post) {
            this.index = index;
            this.pos = pos;
            this.facility = facility;
            this.post = post;
        }

        public String name() {
            return facility != null ? facility.kind.label : "이정표";
        }
    }

    private static boolean dirty = true;
    private static ServerLevel builtLevel;
    private static long builtDay = Long.MIN_VALUE;
    private static List<Node> nodes = List.of();
    private static int[][] hops = new int[0][0];
    private static int[][] next = new int[0][0];
    private static final Map<Long, Integer> FACILITY_INDEX = new HashMap<>();
    private static final Map<Long, Integer> POST_INDEX = new HashMap<>();

    public static void dirty() {
        dirty = true;
    }

    private static void ensure(ServerLevel sl) {
        long day = SimTime.tick(sl) / 24000L;
        if (!dirty && builtLevel == sl && builtDay == day) {
            return;
        }
        build(sl);
        builtLevel = sl;
        builtDay = day;
        dirty = false;
    }

    private static void build(ServerLevel sl) {
        List<Node> list = new ArrayList<>();
        FACILITY_INDEX.clear();
        POST_INDEX.clear();
        List<SignpostStore.Post> posts = SignpostStore.get(sl).all();
        for (SignpostStore.Post p : posts) {
            POST_INDEX.put(p.base().asLong(), list.size());
            list.add(new Node(list.size(), p.road(), null, p));
        }
        for (FacilityStore.Entry e : FacilityStore.get(sl).all()) {
            if (hopCap(e.kind.group) == 0) {
                continue;
            }
            var tpl = FacilityTemplate.of(sl, e.kind, e.rotation, e.mirrored);
            if (tpl.isEmpty() || tpl.get().entryDoors().isEmpty()) {
                continue;
            }
            // 문이 여럿이면 가장 가까운 이정표 쪽 문을 마디로(없으면 첫 문).
            BlockPos door = null;
            double bd = Double.MAX_VALUE;
            for (BlockPos rel : tpl.get().entryDoors()) {
                BlockPos d = e.pos.offset(rel);
                double dist = Double.MAX_VALUE - 1;
                for (SignpostStore.Post p : posts) {
                    dist = Math.min(dist, p.road().distSqr(d));
                }
                if (door == null || dist < bd) {
                    bd = dist;
                    door = d;
                }
            }
            FACILITY_INDEX.put(e.pos.asLong(), list.size());
            list.add(new Node(list.size(), door, e, null));
        }
        int n = list.size();
        boolean[][] adj = new boolean[n][n];
        double link2 = LINK * LINK;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (list.get(i).pos.distSqr(list.get(j).pos) <= link2) {
                    adj[i][j] = true;
                    adj[j][i] = true;
                }
            }
        }
        int[][] h = new int[n][n];
        int[][] nx = new int[n][n];
        for (int s = 0; s < n; s++) {
            Arrays.fill(h[s], -1);
            Arrays.fill(nx[s], -1);
            int[] parent = new int[n];
            Arrays.fill(parent, -1);
            int[] queue = new int[n];
            int qh = 0;
            int qt = 0;
            h[s][s] = 0;
            queue[qt++] = s;
            while (qh < qt) {
                int cur = queue[qh++];
                for (int k = 0; k < n; k++) { // 이웃은 색인 순 — 동률이면 등기 번호가 작은 쪽으로 고정
                    if (adj[cur][k] && h[s][k] < 0) {
                        h[s][k] = h[s][cur] + 1;
                        parent[k] = cur;
                        queue[qt++] = k;
                    }
                }
            }
            for (int t = 0; t < n; t++) {
                if (t == s || h[s][t] < 0) {
                    continue;
                }
                int step = t;
                while (parent[step] != s) {
                    step = parent[step];
                }
                nx[s][t] = step;
            }
        }
        nodes = List.copyOf(list);
        hops = h;
        next = nx;
    }

    /** 마디 전부(이정표 먼저, 시설 다음) — 보고용. */
    public static List<Node> nodes(ServerLevel sl) {
        ensure(sl);
        return nodes;
    }

    /** 이정표·시설 마디 좌표 — 부지 고르기의 "이미 닿는 곳" 씨앗. */
    public static List<BlockPos> stationPositions(ServerLevel sl) {
        ensure(sl);
        List<BlockPos> out = new ArrayList<>(nodes.size());
        for (Node nd : nodes) {
            out.add(nd.pos);
        }
        return out;
    }

    @Nullable
    private static Node nearestNode(BlockPos from, double reach, boolean postsOnly) {
        Node best = null;
        double bd = reach * reach;
        for (Node nd : nodes) {
            if (postsOnly && nd.post == null) {
                continue;
            }
            double d = nd.pos.distSqr(from);
            if (d <= bd) {
                bd = d;
                best = nd;
            }
        }
        return best;
    }

    /**
     * 길찾기 경유점 — 표적이 {@link #DIRECT} 밖이면 "내 이정표 → 표적의 마디"로 가는 다음 마디의 칸. 망이 없거나
     * 안 이어지면 null(종전처럼 곧장). 표적이 시설 안이면 그 시설 마디, 아니면 표적에서 96 안의 마디가 표적 마디다.
     * 내가 내 마디에서 3블록 넘게 떨어져 있으면 먼저 내 마디로 간다(한 구간이 사거리 안에 들게).
     */
    @Nullable
    public static BlockPos via(ServerLevel sl, BlockPos me, BlockPos target) {
        if (me.distSqr(target) <= DIRECT * DIRECT) {
            return null;
        }
        ensure(sl);
        if (nodes.size() < 2) {
            return null;
        }
        Node tn = null;
        FacilityStore.Entry cover = FacilityStore.get(sl).covering(sl, target);
        if (cover != null) {
            Integer idx = FACILITY_INDEX.get(cover.pos.asLong());
            if (idx != null) {
                tn = nodes.get(idx);
            }
        }
        if (tn == null) {
            tn = nearestNode(target, STATION_REACH, false);
        }
        if (tn == null) {
            return null;
        }
        // 첫 마디 — 내게 가장 가까운 마디가 아니라 <b>나→마디 직선 + 마디→표적 홉×연결거리</b>가 가장 싼 마디.
        // 실측(런 38): 등거리의 두 마디 사이에서 "가장 가까운 마디"가 걸음마다 뒤바뀌어 표적 반대쪽 마디로
        // 되돌아갔다(루비 @2,-55: 서쪽 @-40,-42 ↔ 동쪽 @39,-28). 표적 쪽 홉이 작은 마디가 이기면 걸을수록
        // 그 마디가 더 싸져 선택이 흔들리지 않는다.
        Node mn = null;
        double bc = Double.MAX_VALUE;
        double reach2 = STATION_REACH * STATION_REACH;
        for (Node nd : nodes) {
            double d2 = nd.pos.distSqr(me);
            if (d2 > reach2) {
                continue;
            }
            int h = nd == tn ? 0 : hops[nd.index][tn.index];
            if (h < 0) {
                continue; // 그 마디에서는 표적 마디로 못 간다
            }
            double cost = Math.sqrt(d2) + h * LINK;
            if (cost < bc) {
                bc = cost;
                mn = nd;
            }
        }
        if (mn == null) {
            return null; // 내 주변 96 안에 표적으로 이어지는 마디가 없다 — 곧장 시도(리시 정체 진단이 남는다)
        }
        if (mn == tn) {
            return me.distSqr(mn.pos) > 9.0 ? mn.pos : null;
        }
        if (me.distSqr(mn.pos) > 9.0) {
            return mn.pos;
        }
        return nodes.get(next[mn.index][tn.index]).pos;
    }

    /**
     * 자격 — 집에서 가장 가까운 이정표(96 안)에서 이 시설까지의 거리가 종류별 상한 안인가.
     * 직선 반경 규칙의 <b>대안</b>이지 대체가 아니다: 부르는 쪽이 종전 규칙 OR 이것으로 본다.
     */
    public static boolean reaches(ServerLevel sl, @Nullable BlockPos home, FacilityStore.Entry f) {
        int cap = hopCap(f.kind.group);
        if (cap == 0 || home == null) {
            return false;
        }
        ensure(sl);
        Integer fi = FACILITY_INDEX.get(f.pos.asLong());
        if (fi == null) {
            return false;
        }
        Node s = nearestNode(home, STATION_REACH, true);
        if (s == null) {
            return false;
        }
        int h = hops[s.index][fi];
        return h >= 1 && h - 1 <= cap;
    }

    /** 이정표 한 기의 표 — 상한 안의 시설마다 "종류 @x,z 거리 d 다음 @x,z". 보고·표지판용. */
    public static List<Route> routes(ServerLevel sl, SignpostStore.Post post) {
        ensure(sl);
        List<Route> out = new ArrayList<>();
        Integer si = POST_INDEX.get(post.base().asLong());
        if (si == null) {
            return out;
        }
        for (Node nd : nodes) {
            if (nd.facility == null) {
                continue;
            }
            int h = hops[si][nd.index];
            if (h < 1 || h - 1 > hopCap(nd.facility.kind.group)) {
                continue;
            }
            out.add(new Route(nd.facility, h - 1, nodes.get(next[si][nd.index]).pos));
        }
        out.sort((a, b) -> a.distance != b.distance ? Integer.compare(a.distance, b.distance)
                : Long.compare(a.facility.pos.asLong(), b.facility.pos.asLong()));
        return out;
    }

    /** 표 한 줄 — 시설, 거리(0 = 바로 닿음), 다음 마디 칸. */
    public record Route(FacilityStore.Entry facility, int distance, BlockPos nextPos) {
    }
}
