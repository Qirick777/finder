package com.evosim.mod.net;

import com.evosim.mod.client.ClientMateBoard;
import com.evosim.mod.gui.MateBoardSnapshot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 짝매칭 현황판 갱신 패킷 (서버 → 클라). 현황판이 열려 있으면 새 스냅샷으로 화면을 갱신한다. */
public class MateBoardPacket {

    private final MateBoardSnapshot snapshot;

    public MateBoardPacket(MateBoardSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public static void encode(MateBoardPacket msg, FriendlyByteBuf buf) {
        msg.snapshot.encode(buf);
    }

    public static MateBoardPacket decode(FriendlyByteBuf buf) {
        return new MateBoardPacket(MateBoardSnapshot.decode(buf));
    }

    public static void handle(MateBoardPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientMateBoard.update(msg.snapshot)));
        ctx.setPacketHandled(true);
    }
}
