package com.evosim.mod.net;

import com.evosim.mod.entity.GlowKeeper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 렌즈 고정 발광 패킷 (C→S, UX-D) — 고정 켜기/하트비트(on=true, 20틱 주기)와 해제(on=false).
 * 서버는 {@link GlowKeeper}에 위임 — 만료 유예(60틱)로 클라 비정상 종료 시에도 발광이
 * NBT 에 영구 잔존하지 않는다.
 */
public final class PinGlowPacket {

    private final int entityId;
    private final boolean on;

    public PinGlowPacket(int entityId, boolean on) {
        this.entityId = entityId;
        this.on = on;
    }

    public static void encode(PinGlowPacket p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.entityId);
        buf.writeBoolean(p.on);
    }

    public static PinGlowPacket decode(FriendlyByteBuf buf) {
        return new PinGlowPacket(buf.readVarInt(), buf.readBoolean());
    }

    public static void handle(PinGlowPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp != null) {
                GlowKeeper.heartbeat(sp.serverLevel(), p.entityId, p.on);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
