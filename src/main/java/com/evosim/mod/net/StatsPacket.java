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

    public StatsPacket(StatsSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public static void encode(StatsPacket msg, FriendlyByteBuf buf) {
        msg.snapshot.encode(buf);
    }

    public static StatsPacket decode(FriendlyByteBuf buf) {
        return new StatsPacket(StatsSnapshot.decode(buf));
    }

    public static void handle(StatsPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientStats.open(msg.snapshot)));
        ctx.setPacketHandled(true);
    }
}
