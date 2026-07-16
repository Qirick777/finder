package com.evosim.mod.client;

import com.evosim.mod.net.ScanSnapshot;

/**
 * 렌즈 스냅샷 클라 캐시 (P1) — 패킷이 넣고, HUD 오버레이(P2)가 읽는다. 클라 전용 클래스
 * (서버에서 로드되지 않도록 패킷 handle 은 DistExecutor 로 우회 — StatsPacket 선례).
 *
 * <p>핀(P4): 우클릭으로 현재 스냅샷을 고정 — 조준을 벗어나도 카드 유지, 재우클릭 해제.
 * 핀 중에는 수정키 없는 휠도 탭 전환에 쓰인다(ScannerScrollHandler).
 */
public final class ClientScanCache {

    private static volatile ScanSnapshot current;
    private static volatile long updatedMillis;
    private static volatile ScanSnapshot pinned;

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

    /** 핀 토글 — 켤 때는 신선한(1초 내) 조준 스냅샷이 있어야 한다(허공 핀 방지). */
    public static void togglePin() {
        if (pinned != null) {
            pinned = null;
            return;
        }
        ScanSnapshot s = current;
        if (s != null && ageMillis() < 1000) {
            pinned = s;
        }
    }

    public static boolean isPinned() {
        return pinned != null;
    }

    public static ScanSnapshot pinnedSnapshot() {
        return pinned;
    }

    /** 핀이 켜져 있고 같은 개체의 신선한 스냅샷이 오면 핀 내용을 최신으로 갱신(살아있는 카드). */
    public static void refreshPinIfSame() {
        ScanSnapshot p = pinned;
        ScanSnapshot c = current;
        if (p != null && c != null && c.entityId == p.entityId) {
            pinned = c;
        }
    }
}
