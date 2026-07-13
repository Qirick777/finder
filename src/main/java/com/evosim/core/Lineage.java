package com.evosim.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 가계도 순수 연산 (§14 가계도는 계산). 표현층의 혈통 원장(FamilyLedger)이 넘겨주는
 * {@code id → [부모A, 부모B]} 맵만으로 ① 조상 그리드(가계도 GUI 배치) ② 자식 색인
 * ③ 후손 수(근친·사촌혼으로 생기는 다이아몬드 중복을 집합으로 제거)를 계산한다.
 *
 * <p>순수 함수 — {@code /evotest lineage}로 헤드리스 검증. id 0 = 미상(1세대 부모).
 */
public final class Lineage {

    private Lineage() {
    }

    /**
     * 조상 그리드 — GUI 한 페이지 배치용. {@code grid[0]={focus}}, {@code grid[d]}는 2^d 칸이며
     * {@code grid[d][i]}의 부모는 {@code grid[d+1][2i]}(부모A)·{@code grid[d+1][2i+1]}(부모B).
     * 미상/기록 없음은 0. 더 위 세대는 GUI가 조상을 다시 포커스해 재조회한다(무한 항해).
     *
     * @param depth 조상 세대 수(1=부모까지). 그리드 행 수는 depth+1.
     */
    public static long[][] ancestorGrid(long focus, int depth, Map<Long, long[]> parents) {
        long[][] grid = new long[depth + 1][];
        grid[0] = new long[] {focus};
        for (int d = 0; d < depth; d++) {
            long[] cur = grid[d];
            long[] next = new long[cur.length * 2];
            for (int i = 0; i < cur.length; i++) {
                long[] pa = cur[i] == 0 ? null : parents.get(cur[i]);
                next[i * 2] = pa == null ? 0 : pa[0];
                next[i * 2 + 1] = pa == null ? 0 : pa[1];
            }
            grid[d + 1] = next;
        }
        return grid;
    }

    /** 자식 색인 — 부모 id → 자식 id 목록. 미상(0) 부모는 색인하지 않는다. */
    public static Map<Long, List<Long>> childrenIndex(Map<Long, long[]> parents) {
        Map<Long, List<Long>> idx = new HashMap<>();
        for (Map.Entry<Long, long[]> e : parents.entrySet()) {
            for (long p : e.getValue()) {
                if (p != 0) {
                    idx.computeIfAbsent(p, k -> new ArrayList<>()).add(e.getKey());
                }
            }
        }
        return idx;
    }

    /**
     * 총 후손 수 — BFS + 방문 집합. 사촌혼·근친으로 한 후손에 이르는 경로가 여러 개여도
     * <b>한 번만</b> 센다(다이아몬드 중복 제거). 자기 자신은 제외.
     */
    public static int descendantCount(long id, Map<Long, List<Long>> childrenIndex) {
        Set<Long> seen = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(id);
        while (!queue.isEmpty()) {
            for (long c : childrenIndex.getOrDefault(queue.poll(), List.of())) {
                if (seen.add(c)) {
                    queue.add(c);
                }
            }
        }
        return seen.size();
    }

    /** 직접 자식 수. */
    public static int childCount(long id, Map<Long, List<Long>> childrenIndex) {
        return childrenIndex.getOrDefault(id, List.of()).size();
    }
}
