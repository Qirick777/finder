package com.evosim.mod.gui;

import com.evosim.core.ExpressionResolver;
import com.evosim.core.Lineage;
import com.evosim.core.Trait;
import com.evosim.mod.entity.FamilyLedger;
import com.evosim.mod.entity.MimicEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 인구 통계 스냅샷 (서버 계산 → 클라 그래프). ① 생존 개체의 발현 특성 분포(최다→최소, 0개 특성
 * 포함 — "무엇이 적게 남았나"도 관측 대상) ② 최다 후손 랭킹(원장 전수 — 죽은 조상 포함).
 */
public class StatsSnapshot {

    public static final int TOP_LEGACY = 8;

    public record Bar(String name, int count) { }

    public record Top(long id, int serial, int entityId, boolean female, int gen,
                      boolean alive, int children, int descendants, String name) { }

    public final int living;
    public final List<Bar> bars;   // 발현 특성 분포(내림차순)
    public final List<Top> tops;   // 후손 랭킹(내림차순)

    public StatsSnapshot(int living, List<Bar> bars, List<Top> tops) {
        this.living = living;
        this.bars = bars;
        this.tops = tops;
    }

    /** 서버측 조립. 무대 개체(stageActor)는 분포에서 제외 — 원장과 같은 기준. */
    public static StatsSnapshot build(ServerLevel level) {
        Map<Trait, Integer> counts = new EnumMap<>(Trait.class);
        for (Trait t : Trait.values()) {
            counts.put(t, 0);
        }
        Map<Long, Integer> aliveIds = new HashMap<>();
        int living = 0;
        for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null && !e.isStageActor())) {
            living++;
            aliveIds.put(m.getIndividual().id(), m.getId());
            for (Trait t : ExpressionResolver.expressedTraits(m.getIndividual())) {
                counts.merge(t, 1, Integer::sum);
            }
        }
        List<Bar> bars = new ArrayList<>();
        for (Map.Entry<Trait, Integer> e : counts.entrySet()) {
            bars.add(new Bar(e.getKey().koreanName(), e.getValue()));
        }
        bars.sort((a, b) -> Integer.compare(b.count(), a.count()));

        FamilyLedger ledger = FamilyLedger.get(level);
        Map<Long, List<Long>> childrenIdx = Lineage.childrenIndex(ledger.parentsMap());
        List<Top> tops = new ArrayList<>();
        for (FamilyLedger.Rec r : ledger.all().values()) {
            int desc = Lineage.descendantCount(r.id, childrenIdx);
            if (desc == 0) {
                continue; // 후손 없는 개체는 랭킹 비후보(목록 폭주 방지)
            }
            Integer eid = aliveIds.get(r.id);
            tops.add(new Top(r.id, r.serial, eid == null ? -1 : eid, r.female, r.gen,
                    eid != null, Lineage.childCount(r.id, childrenIdx), desc,
                    r.name == null ? "N" + r.serial : r.name));
        }
        tops.sort((a, b) -> Integer.compare(b.descendants(), a.descendants()));
        if (tops.size() > TOP_LEGACY) {
            tops.subList(TOP_LEGACY, tops.size()).clear();
        }
        return new StatsSnapshot(living, bars, tops);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(living);
        buf.writeVarInt(bars.size());
        for (Bar b : bars) {
            buf.writeUtf(b.name());
            buf.writeVarInt(b.count());
        }
        buf.writeVarInt(tops.size());
        for (Top t : tops) {
            buf.writeLong(t.id());
            buf.writeVarInt(t.serial());
            buf.writeVarInt(t.entityId());
            buf.writeBoolean(t.female());
            buf.writeVarInt(t.gen());
            buf.writeBoolean(t.alive());
            buf.writeVarInt(t.children());
            buf.writeVarInt(t.descendants());
            buf.writeUtf(t.name());
        }
    }

    public static StatsSnapshot decode(FriendlyByteBuf buf) {
        int living = buf.readVarInt();
        int nb = buf.readVarInt();
        List<Bar> bars = new ArrayList<>(nb);
        for (int i = 0; i < nb; i++) {
            bars.add(new Bar(buf.readUtf(), buf.readVarInt()));
        }
        int nt = buf.readVarInt();
        List<Top> tops = new ArrayList<>(nt);
        for (int i = 0; i < nt; i++) {
            tops.add(new Top(buf.readLong(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                    buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
                    buf.readUtf()));
        }
        return new StatsSnapshot(living, bars, tops);
    }
}
