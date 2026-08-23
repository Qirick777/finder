package com.evosim.mod.item;

import com.evosim.mod.entity.FamilyLedger;
import com.evosim.mod.entity.FarmStore;
import com.evosim.mod.entity.MimicEntity;
import com.evosim.mod.net.ModNetwork;
import com.evosim.mod.net.OpenLandDeedPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 땅 문서 — 밭 타일 우클릭 → 그 구획의 원장(창설자·소유자·소작·확장 이력·수확 분배) GUI.
 * 크리에이티브 관찰·디버그 도구(/give evosim:land_deed). 표시만 하며 시뮬 상태를 바꾸지 않는다.
 */
public class LandDeedItem extends Item {

    public LandDeedItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (ctx.getLevel().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(ctx.getLevel() instanceof ServerLevel sl) || !(ctx.getPlayer() instanceof ServerPlayer sp)) {
            return InteractionResult.PASS;
        }
        BlockPos pos = ctx.getClickedPos();
        FarmStore store = FarmStore.get(sl);
        FarmStore.Plot p = store.plotAt(pos);
        if (p == null) {
            // 타일 바로 위/아래를 클릭한 경우도 포용(베리 블록은 지면 위) — 인접 y 재시도.
            p = store.plotAt(pos.above());
            if (p == null) {
                p = store.plotAt(pos.below());
            }
        }
        if (p == null) {
            sp.displayClientMessage(Component.literal("§7[땅 문서] 여기엔 등록된 밭 타일이 없다."), true);
            return InteractionResult.CONSUME;
        }
        sendDeed(sp, sl, p);
        return InteractionResult.CONSUME;
    }

    /** 구획 원장을 조립해 땅 문서 화면 패킷 전송(성명은 원장/생존 개체에서 해석). */
    public static void sendDeed(ServerPlayer sp, ServerLevel sl, FarmStore.Plot p) {
        String owner = p.ownerId == 0L ? "무주지" : nameOf(sl, p.ownerId);
        String founder = nameOf(sl, p.founderId);
        // 현재 상시 소작 성명 — 생존 개체에서 수집.
        List<String> tenants = new ArrayList<>();
        for (MimicEntity m : sl.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null && e.getTenantFarm() == p.id)) {
            tenants.add(m.getIndividual().shortName());
        }
        // 확장 이력 — 원장 병렬 배열을 최신순 유지(끝이 최신)로 그대로 전달.
        List<OpenLandDeedPacket.Expand> hist = new ArrayList<>();
        for (int i = 0; i < p.expandDay.length; i++) {
            long by = i < p.expandBy.length ? p.expandBy[i] : 0L;
            int tiles = i < p.expandTiles.length ? p.expandTiles[i] : 0;
            // 소작/자영 구분은 원장에 이력 단위로 저장돼 있지 않으므로, 기여자 == 소유자면 자영으로 근사.
            boolean byTenant = by != p.ownerId;
            hist.add(new OpenLandDeedPacket.Expand(p.expandDay[i], nameOf(sl, by), tiles, byTenant));
        }
        ModNetwork.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sp),
                new OpenLandDeedPacket(p.id, p.anchor.getX(), p.anchor.getZ(), owner, founder,
                        p.foundedDay, p.tiles.length, p.tilesByFounder, p.tilesByOwner, p.tilesByTenant,
                        p.account, p.totalYield, p.totalToOwner, p.totalToTenant, p.harvestCount,
                        p.stewardId == 0L ? "—" : nameOf(sl, p.stewardId), p.stewardDebt,
                        tenants, hist));
    }

    /** 개체 id → 성명. 원장 우선(사후 포함), 없으면 "#id". */
    private static String nameOf(ServerLevel sl, long id) {
        if (id == 0L) {
            return "—";
        }
        FamilyLedger.Rec r = FamilyLedger.get(sl).get(id);
        if (r != null && r.name != null && !r.name.isEmpty()) {
            return r.name;
        }
        return "#" + id;
    }
}
