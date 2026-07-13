package com.evosim.mod.net;

import com.evosim.mod.client.ClientPedigree;
import com.evosim.mod.gui.PedigreeSnapshot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 가계도 스냅샷 패킷 (서버 → 클라). 화면이 없으면 열고, 열려 있으면 새 포커스로 갱신한다. */
public class PedigreePacket {

    private final PedigreeSnapshot snapshot;

    public PedigreePacket(PedigreeSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public static void encode(PedigreePacket msg, FriendlyByteBuf buf) {
        msg.snapshot.encode(buf);
    }

    public static PedigreePacket decode(FriendlyByteBuf buf) {
        return new PedigreePacket(PedigreeSnapshot.decode(buf));
    }

    public static void handle(PedigreePacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPedigree.open(msg.snapshot)));
        ctx.setPacketHandled(true);
    }
}
