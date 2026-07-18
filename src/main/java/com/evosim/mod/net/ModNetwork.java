package com.evosim.mod.net;

import com.evosim.mod.EvoSimMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/** 모드 네트워크 채널 — 검사봉 모드 순환·짝매칭 현황판·가계도·인구 통계 패킷. */
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
                CycleScannerModePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, MateBoardPacket.class,
                MateBoardPacket::encode, MateBoardPacket::decode,
                MateBoardPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, PedigreeRequestPacket.class,
                PedigreeRequestPacket::encode, PedigreeRequestPacket::decode,
                PedigreeRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, PedigreePacket.class,
                PedigreePacket::encode, PedigreePacket::decode,
                PedigreePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, StatsPacket.class,
                StatsPacket::encode, StatsPacket::decode,
                StatsPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        // 신규 패킷은 반드시 맨 끝에 추가 — 중간 삽입은 기존 id 를 밀어 클라·서버 불일치를 만든다.
        CHANNEL.registerMessage(id++, ScanPacket.class,
                ScanPacket::encode, ScanPacket::decode,
                ScanPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, PinGlowPacket.class,
                PinGlowPacket::encode, PinGlowPacket::decode,
                PinGlowPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, OpenTraitEditorPacket.class,
                OpenTraitEditorPacket::encode, OpenTraitEditorPacket::decode,
                OpenTraitEditorPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, EditTraitPacket.class,
                EditTraitPacket::encode, EditTraitPacket::decode,
                EditTraitPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, OpenLandDeedPacket.class,
                OpenLandDeedPacket::encode, OpenLandDeedPacket::decode,
                OpenLandDeedPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
}
