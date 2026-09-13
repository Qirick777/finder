package com.evosim.mod.net;

import com.evosim.mod.client.ClientStats;
import com.evosim.mod.gui.StatsSnapshot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 인구 통계 스냅샷 패킷 (서버 → 클라). 특성 분포 그래프 + 후손 랭킹 화면을 연다. */
public class StatsPacket {

    private final StatsSnapshot snapshot;
    /** 처음 열 탭(UI P4) — 0 인구 · 1 학력 · 2 시설 · 3 군 · 4 영지. {@code evosim stats <tab>}. */
    private final int tab;

    public StatsPacket(StatsSnapshot snapshot) {
        this(snapshot, 0);
    }

    public StatsPacket(StatsSnapshot snapshot, int tab) {
        this.snapshot = snapshot;
        this.tab = tab;
    }

    public static void encode(StatsPacket msg, FriendlyByteBuf buf) {
        msg.snapshot.encode(buf);
        buf.writeVarInt(msg.tab); // 신규 필드는 맨 끝
    }

    public static StatsPacket decode(FriendlyByteBuf buf) {
        StatsSnapshot snap = StatsSnapshot.decode(buf);
        return new StatsPacket(snap, buf.readVarInt());
    }

    public static void handle(StatsPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientStats.open(msg.snapshot, msg.tab)));
        ctx.setPacketHandled(true);
    }
}
