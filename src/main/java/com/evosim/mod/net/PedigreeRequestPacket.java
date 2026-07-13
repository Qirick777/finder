package com.evosim.mod.net;

import com.evosim.mod.gui.PedigreeSnapshot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * 가계도 재조회 패킷 (클라 → 서버). 가계도 화면에서 조상 노드를 클릭하면 그 조상을 새 포커스로
 * 스냅샷을 다시 요청한다 — 이 왕복이 "위로 무한 항해"의 전부다(클라는 원장을 갖지 않는다).
 */
public class PedigreeRequestPacket {

    private final long focusId;

    public PedigreeRequestPacket(long focusId) {
        this.focusId = focusId;
    }

    public static void encode(PedigreeRequestPacket msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.focusId);
    }

    public static PedigreeRequestPacket decode(FriendlyByteBuf buf) {
        return new PedigreeRequestPacket(buf.readLong());
    }

    public static void handle(PedigreeRequestPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || msg.focusId == 0) {
                return;
            }
            PedigreeSnapshot snap = PedigreeSnapshot.build(player.serverLevel(), msg.focusId);
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new PedigreePacket(snap));
        });
        ctx.setPacketHandled(true);
    }
}
