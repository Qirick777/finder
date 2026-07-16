package com.evosim.mod.client;

import com.evosim.mod.net.ScanSnapshot;

/**
 * 렌즈 스냅샷 클라 캐시 (P1) — 패킷이 넣고, HUD 오버레이(P2)가 읽는다. 클라 전용 클래스
 * (서버에서 로드되지 않도록 패킷 handle 은 DistExecutor 로 우회 — StatsPacket 선례).
 */
public final class ClientScanCache {

    private static volatile ScanSnapshot current;
    private static volatile long updatedMillis;

    private ClientScanCache() {
    }

    public static void set(ScanSnapshot snapshot) {
        current = snapshot;
        updatedMillis = System.currentTimeMillis();
    }

    public static ScanSnapshot get() {
        return current;
    }

    /** 마지막 갱신 후 경과(ms) — 페이드아웃 히스테리시스(P2)용. */
    public static long ageMillis() {
        return System.currentTimeMillis() - updatedMillis;
    }
}
