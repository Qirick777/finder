package com.evosim.mod.client;

import com.evosim.mod.net.ModNetwork;
import com.evosim.mod.net.PinGlowPacket;
import com.evosim.mod.net.ScanSnapshot;

/**
 * 렌즈 스냅샷 클라 캐시 (P1) — 패킷이 넣고, HUD 오버레이(P2)가 읽는다. 클라 전용 클래스
 * (서버에서 로드되지 않도록 패킷 handle 은 DistExecutor 로 우회 — StatsPacket 선례).
 *
 * <p>핀(P4·UX-B/C): 우클릭이면 마지막 조준 스냅샷을 <b>즉시</b> 고정(신선도 조건 없음 —
 * 카드가 사라진 뒤에도 마지막 조준 대상이 고정된다), 고정 중엔 <b>아무 우클릭</b>이나 해제.
 * 고정 개체는 발광 강조(UX-D) — 켜고 끌 때 + 주기 하트비트로 서버 GlowKeeper 에 알린다.
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

    /** 고정 켜기 — 마지막 조준 스냅샷이 있으면 즉시(신선도 무관, UX-B). 성공 여부 반환. */
    public static boolean pin() {
        ScanSnapshot s = current;
        if (s == null) {
            return false;
        }
        pinned = s;
        ModNetwork.CHANNEL.sendToServer(new PinGlowPacket(s.entityId, true));
        return true;
    }

    /** 고정 해제(UX-C) — 발광 즉시 끄기 패킷 포함. 해제했으면 true. */
    public static boolean unpin() {
        ScanSnapshot p = pinned;
        if (p == null) {
            return false;
        }
        pinned = null;
        ModNetwork.CHANNEL.sendToServer(new PinGlowPacket(p.entityId, false));
        return true;
    }

    public static boolean isPinned() {
        return pinned != null;
    }

    public static ScanSnapshot pinnedSnapshot() {
        return pinned;
    }

    /** 고정 중 발광 유지 하트비트(UX-D) — 클라 20틱마다 호출(PinGlowHeartbeat). */
    public static void glowHeartbeat() {
        ScanSnapshot p = pinned;
        if (p != null) {
            ModNetwork.CHANNEL.sendToServer(new PinGlowPacket(p.entityId, true));
        }
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
