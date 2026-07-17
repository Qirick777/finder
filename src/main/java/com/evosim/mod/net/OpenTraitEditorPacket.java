package com.evosim.mod.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 특성 편집 화면 열기/갱신 (S→C). 서버가 유일한 진실 — 화면은 항상 이 패킷의 사본만 그린다
 * (편집 연산마다 서버가 적용 결과를 재전송).
 */
public final class OpenTraitEditorPacket {

    /** 보유 인스턴스 한 줄 — 특성 ordinal·태그 비트(1=우성 2=남발 4=여발)·등급·반발. */
    public record Entry(int traitOrdinal, int tagBits, int grade, boolean anti) {
    }

    public static final int BIT_DOMINANT = 1;
    public static final int BIT_MALE = 2;
    public static final int BIT_FEMALE = 4;

    public final int entityId;
    public final String headline;
    public final List<Entry> entries;
    public final String status;
    // 성명 편집란 프리필(First/Middle/Last)
    public final String nameFirst;
    public final String nameMiddle;
    public final String nameLast;

    public OpenTraitEditorPacket(int entityId, String headline, List<Entry> entries, String status,
                                 String nameFirst, String nameMiddle, String nameLast) {
        this.entityId = entityId;
        this.headline = headline;
        this.entries = entries;
        this.status = status;
        this.nameFirst = nameFirst;
        this.nameMiddle = nameMiddle;
        this.nameLast = nameLast;
    }

    public static void encode(OpenTraitEditorPacket p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.entityId);
        buf.writeUtf(p.headline);
        buf.writeVarInt(p.entries.size());
        for (Entry e : p.entries) {
            buf.writeVarInt(e.traitOrdinal());
            buf.writeByte(e.tagBits());
            buf.writeByte(e.grade());
            buf.writeBoolean(e.anti());
        }
        buf.writeUtf(p.status);
        buf.writeUtf(p.nameFirst);
        buf.writeUtf(p.nameMiddle);
        buf.writeUtf(p.nameLast);
    }

    public static OpenTraitEditorPacket decode(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        String head = buf.readUtf();
        int n = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            entries.add(new Entry(buf.readVarInt(), buf.readByte(), buf.readByte(), buf.readBoolean()));
        }
        return new OpenTraitEditorPacket(id, head, entries, buf.readUtf(),
                buf.readUtf(), buf.readUtf(), buf.readUtf());
    }

    public static void handle(OpenTraitEditorPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.evosim.mod.client.TraitEditorScreen.openOrRefresh(p)));
        ctx.get().setPacketHandled(true);
    }
}
