package com.evosim.mod.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 시설·가구 문서 열기 (S→C, UI P4) — 서버가 {@link com.evosim.mod.gui.DeedText} 로 조립한 문장
 * 사본. 밭 문서({@link OpenLandDeedPacket})와 달리 숫자 필드가 아니라 <b>완성된 줄</b>을 보낸다:
 * 시설 종류마다 실을 항목이 다르고, 화면이 판정을 되풀이하면 어긋난다(관측 전용, 편집 없음).
 */
public final class OpenDeedPacket {

    public final String title;
    public final List<String> lines;
    public final List<String> history;

    public OpenDeedPacket(String title, List<String> lines, List<String> history) {
        this.title = title;
        this.lines = lines;
        this.history = history;
    }

    public static void encode(OpenDeedPacket p, FriendlyByteBuf buf) {
        buf.writeUtf(p.title);
        buf.writeVarInt(p.lines.size());
        for (String l : p.lines) {
            buf.writeUtf(l);
        }
        buf.writeVarInt(p.history.size());
        for (String h : p.history) {
            buf.writeUtf(h);
        }
    }

    public static OpenDeedPacket decode(FriendlyByteBuf buf) {
        String title = buf.readUtf();
        int n = buf.readVarInt();
        List<String> lines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            lines.add(buf.readUtf());
        }
        int nh = buf.readVarInt();
        List<String> hist = new ArrayList<>(nh);
        for (int i = 0; i < nh; i++) {
            hist.add(buf.readUtf());
        }
        return new OpenDeedPacket(title, lines, hist);
    }

    public static void handle(OpenDeedPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.evosim.mod.client.DeedScreen.open(p)));
        ctx.get().setPacketHandled(true);
    }
}
