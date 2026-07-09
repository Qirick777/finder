package com.evosim.mod.net;

import com.evosim.mod.item.ScannerMode;
import com.evosim.mod.item.TraitScannerItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 검사봉 모드 순환 패킷 (클라 스크롤 → 서버가 보유 검사봉 NBT 갱신). */
public class CycleScannerModePacket {

    private final int dir;

    public CycleScannerModePacket(int dir) {
        this.dir = dir;
    }

    public static void encode(CycleScannerModePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.dir);
    }

    public static CycleScannerModePacket decode(FriendlyByteBuf buf) {
        return new CycleScannerModePacket(buf.readVarInt());
    }

    public static void handle(CycleScannerModePacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            ItemStack held = player.getMainHandItem();
            if (!(held.getItem() instanceof TraitScannerItem)) {
                held = player.getOffhandItem();
            }
            if (!(held.getItem() instanceof TraitScannerItem)) {
                return;
            }
            ScannerMode next = ScannerMode.cycle(ScannerMode.of(held), msg.dir);
            next.writeTo(held);
            player.displayClientMessage(
                    Component.literal("검사봉 모드: " + next.label()).withStyle(ChatFormatting.AQUA), true);
        });
        ctx.setPacketHandled(true);
    }
}
