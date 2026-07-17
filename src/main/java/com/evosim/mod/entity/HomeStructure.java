package com.evosim.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.List;

/**
 * 미믹 거처 천막 구조 (양털 계단식 A자 + 앞·뒤 참나무 울타리 기둥, 전면 모닥불). 앵커 H = 눕는 중앙 바닥
 * (공기), +z(front) = 모닥불 방향. 동서남북 회전 지원.
 *
 * <pre>
 *  y=0 : 옆벽 x=±2 (z=-2..+1) · 앞뒤기둥(울타리) x0 z=-2,+1
 *  y=1 : 옆벽 x=±2            · 앞뒤기둥
 *  y=2 : 어깨 x=±1            · 앞뒤기둥
 *  y=3 : 용마루 x=0
 *  모닥불 : (0,0,+3)
 * </pre>
 */
public final class HomeStructure {

    public static final int TOKEN_WOOL = 0;
    public static final int TOKEN_FENCE = 1;

    /** 배치 한 칸 — 월드 좌표 + 블록 토큰. */
    public record Placement(BlockPos pos, int token) {
    }

    // 로컬 오프셋 {dx, dy, dz, token} (+z=전방). 건축 순서는 y 오름차순으로 정렬.
    private static final int[][] LOCAL;

    static {
        List<int[]> l = new ArrayList<>();
        for (int z = -2; z <= 1; z++) {
            l.add(new int[] {2, 0, z, TOKEN_WOOL});
            l.add(new int[] {-2, 0, z, TOKEN_WOOL});
            l.add(new int[] {2, 1, z, TOKEN_WOOL});
            l.add(new int[] {-2, 1, z, TOKEN_WOOL});
            l.add(new int[] {1, 2, z, TOKEN_WOOL});
            l.add(new int[] {-1, 2, z, TOKEN_WOOL});
            l.add(new int[] {0, 3, z, TOKEN_WOOL}); // 용마루
        }
        for (int z : new int[] {-2, 1}) { // 앞·뒤 기둥(울타리)
            l.add(new int[] {0, 0, z, TOKEN_FENCE});
            l.add(new int[] {0, 1, z, TOKEN_FENCE});
            l.add(new int[] {0, 2, z, TOKEN_FENCE});
        }
        // 건축 순서: 낮은 층부터.
        l.sort((a, b) -> Integer.compare(a[1], b[1]));
        LOCAL = l.toArray(new int[0][]);
    }

    private HomeStructure() {
    }

    /** 앵커 H와 전방(facing) 기준 배치 목록(건축 순서). */
    public static List<Placement> plan(BlockPos home, Direction facing) {
        List<Placement> out = new ArrayList<>(LOCAL.length);
        for (int[] b : LOCAL) {
            out.add(new Placement(world(home, b[0], b[1], b[2], facing), b[3]));
        }
        return out;
    }

    public static int blockCount() {
        return LOCAL.length;
    }

    /** 모닥불(거처 모닥불 블록) 위치 — H 앞 3칸. */
    public static BlockPos hearthPos(BlockPos home, Direction facing) {
        return world(home, 0, 0, 3, facing);
    }

    /**
     * 하단 발자국 — 천막 바닥 사각형(벽 x=±2, z=-2..+1 + 앞 모닥불) + <b>옆 베리 자리(x=±3)</b>까지 포함.
     * 평탄화가 베리 심을 자리까지 미리 골라 "옆에 공간 없음"을 방지한다. y는 home.Y, x·z만 의미 있음.
     */
    public static List<BlockPos> footprint(BlockPos home, Direction facing) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) {          // ±3 = 옆 베리 자리 포함(평탄화 대상)
            for (int dz = -2; dz <= 3; dz++) {      // -2..+1 천막 몸체 + +2 입구 + +3 모닥불
                out.add(world(home, dx, 0, dz, facing));
            }
        }
        return out;
    }

    /** 옆 베리 정원 8칸 — 양털 옆벽(x=±2) 바로 바깥(x=±3), z=-2..+1. 심기·집계에 쓴다. */
    public static List<BlockPos> berryTiles(BlockPos home, Direction facing) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx : new int[] {3, -3}) {
            for (int dz = -2; dz <= 1; dz++) {
                out.add(world(home, dx, 0, dz, facing));
            }
        }
        return out;
    }

    /**
     * 정원 <b>영역</b> 셀(기본 8칸 우선 + 폴백 16칸: x=±3·±4, z=-3..+2) — 고정 8칸이 지형(모래·
     * 경사·수풀)에 막히면 그 가구는 정원 상한이 지형 복권이 되던 문제의 폴백. 심기 순서는
     * 기본 칸 먼저(모양 유지), 집계·수확·청소는 영역 전체를 같은 목록으로 본다(장부 대칭).
     */
    public static List<BlockPos> gardenCells(BlockPos home, Direction facing) {
        List<BlockPos> out = new ArrayList<>(berryTiles(home, facing));
        for (int dx : new int[] {3, -3, 4, -4}) {
            for (int dz = -3; dz <= 2; dz++) {
                BlockPos p = world(home, dx, 0, dz, facing);
                if (!out.contains(p)) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /** 로컬(dx,dy,dz, +z=전방)을 facing 회전해 월드 좌표로. 좌우 대칭이라 right 부호는 무관. */
    private static BlockPos world(BlockPos home, int dx, int dy, int dz, Direction facing) {
        Vec3i front = facing.getNormal();
        Vec3i right = facing.getClockWise().getNormal();
        int wx = front.getX() * dz + right.getX() * dx;
        int wz = front.getZ() * dz + right.getZ() * dx;
        return home.offset(wx, dy, wz);
    }
}
