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
     * 하단 발자국 — 천막이 덮는 바닥 사각형(양털 벽 x=±2, z=-2..+1 + 앞 모닥불 z=+3까지)의 모든 칸을
     * 앵커 Y로 반환. 평탄화(기단 높이 산정·하단 메움)에 쓴다. y는 home.Y, x·z만 의미 있음.
     */
    public static List<BlockPos> footprint(BlockPos home, Direction facing) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 3; dz++) { // -2..+1 천막 몸체 + +2 입구 + +3 모닥불
                out.add(world(home, dx, 0, dz, facing));
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
