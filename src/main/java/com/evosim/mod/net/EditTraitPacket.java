package com.evosim.mod.net;

import com.evosim.mod.entity.TraitEditor;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 특성 편집 연산 (C→S). 서버가 {@link TraitEditor}로 검증·적용한 뒤, 갱신된
 * {@link OpenTraitEditorPacket}(적용 결과 + 상태 메시지)을 회신한다.
 */
public final class EditTraitPacket {

    private final int entityId;
    private final int op;
    private final int traitOrdinal;
    private final int index;
    private final int value;
    private final boolean dominant;

    public EditTraitPacket(int entityId, int op, int traitOrdinal, int index, int value,
                           boolean dominant) {
        this.entityId = entityId;
        this.op = op;
        this.traitOrdinal = traitOrdinal;
        this.index = index;
        this.value = value;
        this.dominant = dominant;
    }

    public static void encode(EditTraitPacket p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.entityId);
        buf.writeByte(p.op);
        buf.writeVarInt(p.traitOrdinal);
        buf.writeVarInt(p.index);
        buf.writeByte(p.value);
        buf.writeBoolean(p.dominant);
    }

    public static EditTraitPacket decode(FriendlyByteBuf buf) {
        return new EditTraitPacket(buf.readVarInt(), buf.readByte(), buf.readVarInt(),
                buf.readVarInt(), buf.readByte(), buf.readBoolean());
    }

    public static void handle(EditTraitPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp == null) {
                return;
            }
            String status = TraitEditor.apply(sp.serverLevel(), sp, p.entityId,
                    p.op, p.traitOrdinal, p.index, p.value, p.dominant);
            com.evosim.mod.item.TraitEditorItem.sendEditor(sp, p.entityId, status);
        });
        ctx.get().setPacketHandled(true);
    }
}
