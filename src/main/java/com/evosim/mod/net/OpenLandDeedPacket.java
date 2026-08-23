package com.evosim.mod.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 땅 문서 화면 열기 (S→C) — 밭 한 구획의 원장 사본. 서버가 유일한 진실이고 화면은 이 사본만
 * 그린다(관측 전용, 편집 없음). {@link com.evosim.mod.item.LandDeedItem}이 밭 타일 우클릭 시 조립.
 */
public final class OpenLandDeedPacket {

    /** 확장 이력 한 줄 — 게임일·기여자 성명·그 확장에서 더한 타일 수(재투자/자영은 색으로). */
    public record Expand(long day, String by, int tiles, boolean byTenant) {
    }

    public final long plotId;
    public final int anchorX;
    public final int anchorZ;
    public final String ownerName;
    public final String founderName;
    public final long foundedDay;
    public final int tiles;
    public final long tilesByFounder;
    public final long tilesByOwner;
    public final long tilesByTenant;
    public final double account;
    public final double totalYield;
    public final double totalToOwner;
    public final double totalToTenant;
    public final int harvestCount;
    /** 현재 마름(위임 관리자) 성명 — 없으면 "—". 소작 명단과 별개의 직위라 따로 싣는다. */
    public final String stewardName;
    /** 마름이 영주에게 진 착공비 상환 채무(가문 편입 경로) — 0이면 없음. */
    public final double stewardDebt;
    public final List<String> tenants;   // 현재 상시 소작 성명
    public final List<Expand> history;   // 최근 확장 이력(최신이 뒤)

    public OpenLandDeedPacket(long plotId, int anchorX, int anchorZ, String ownerName,
                             String founderName, long foundedDay, int tiles, long tilesByFounder,
                             long tilesByOwner, long tilesByTenant, double account, double totalYield,
                             double totalToOwner, double totalToTenant, int harvestCount,
                             String stewardName, double stewardDebt,
                             List<String> tenants, List<Expand> history) {
        this.plotId = plotId;
        this.anchorX = anchorX;
        this.anchorZ = anchorZ;
        this.ownerName = ownerName;
        this.founderName = founderName;
        this.foundedDay = foundedDay;
        this.tiles = tiles;
        this.tilesByFounder = tilesByFounder;
        this.tilesByOwner = tilesByOwner;
        this.tilesByTenant = tilesByTenant;
        this.account = account;
        this.totalYield = totalYield;
        this.totalToOwner = totalToOwner;
        this.totalToTenant = totalToTenant;
        this.harvestCount = harvestCount;
        this.stewardName = stewardName;
        this.stewardDebt = stewardDebt;
        this.tenants = tenants;
        this.history = history;
    }

    public static void encode(OpenLandDeedPacket p, FriendlyByteBuf buf) {
        buf.writeLong(p.plotId);
        buf.writeVarInt(p.anchorX);
        buf.writeVarInt(p.anchorZ);
        buf.writeUtf(p.ownerName);
        buf.writeUtf(p.founderName);
        buf.writeLong(p.foundedDay);
        buf.writeVarInt(p.tiles);
        buf.writeLong(p.tilesByFounder);
        buf.writeLong(p.tilesByOwner);
        buf.writeLong(p.tilesByTenant);
        buf.writeDouble(p.account);
        buf.writeDouble(p.totalYield);
        buf.writeDouble(p.totalToOwner);
        buf.writeDouble(p.totalToTenant);
        buf.writeVarInt(p.harvestCount);
        buf.writeUtf(p.stewardName);
        buf.writeDouble(p.stewardDebt);
        buf.writeVarInt(p.tenants.size());
        for (String t : p.tenants) {
            buf.writeUtf(t);
        }
        buf.writeVarInt(p.history.size());
        for (Expand e : p.history) {
            buf.writeLong(e.day());
            buf.writeUtf(e.by());
            buf.writeVarInt(e.tiles());
            buf.writeBoolean(e.byTenant());
        }
    }

    public static OpenLandDeedPacket decode(FriendlyByteBuf buf) {
        long plotId = buf.readLong();
        int ax = buf.readVarInt();
        int az = buf.readVarInt();
        String owner = buf.readUtf();
        String founder = buf.readUtf();
        long fday = buf.readLong();
        int tiles = buf.readVarInt();
        long tByF = buf.readLong();
        long tByO = buf.readLong();
        long tByT = buf.readLong();
        double acct = buf.readDouble();
        double ty = buf.readDouble();
        double tto = buf.readDouble();
        double ttt = buf.readDouble();
        int hc = buf.readVarInt();
        String stw = buf.readUtf();
        double stwDebt = buf.readDouble();
        int nt = buf.readVarInt();
        List<String> tenants = new ArrayList<>(nt);
        for (int i = 0; i < nt; i++) {
            tenants.add(buf.readUtf());
        }
        int nh = buf.readVarInt();
        List<Expand> history = new ArrayList<>(nh);
        for (int i = 0; i < nh; i++) {
            history.add(new Expand(buf.readLong(), buf.readUtf(), buf.readVarInt(), buf.readBoolean()));
        }
        return new OpenLandDeedPacket(plotId, ax, az, owner, founder, fday, tiles, tByF, tByO, tByT,
                acct, ty, tto, ttt, hc, stw, stwDebt, tenants, history);
    }

    public static void handle(OpenLandDeedPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.evosim.mod.client.LandDeedScreen.open(p)));
        ctx.get().setPacketHandled(true);
    }
}
