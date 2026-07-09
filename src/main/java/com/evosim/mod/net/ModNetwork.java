package com.evosim.mod.net;

import com.evosim.mod.EvoSimMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/** 모드 네트워크 채널 — 검사봉 모드 변경(클라 스크롤 → 서버) 패킷용. */
public final class ModNetwork {

    private static final String VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(EvoSimMod.MODID, "main"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    private ModNetwork() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, CycleScannerModePacket.class,
                CycleScannerModePacket::encode, CycleScannerModePacket::decode,
                CycleScannerModePacket::handle);
    }
}
