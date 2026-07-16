package com.evosim.mod.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 검사봉 렌즈 스냅샷 패킷 (서버 → 클라, P1). snapshot == null 이면 "조준 해제"(카드 페이드아웃 신호).
 * 클라 처리는 {@code ClientScanCache}에 저장만 — 렌더(P2)는 캐시를 읽는다.
 */
public class ScanPacket {

    private final ScanSnapshot snapshot; // null = 대상 없음(clear)

    public ScanPacket(ScanSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public static void encode(ScanPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.snapshot != null);
        if (msg.snapshot != null) {
            msg.snapshot.encode(buf);
        }
    }

    public static ScanPacket decode(FriendlyByteBuf buf) {
        return new ScanPacket(buf.readBoolean() ? ScanSnapshot.decode(buf) : null);
    }

    public static void handle(ScanPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.evosim.mod.client.ClientScanCache.set(msg.snapshot)));
        ctx.setPacketHandled(true);
    }
}
