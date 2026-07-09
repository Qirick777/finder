package com.evosim.mod.gui;

import com.evosim.core.Individual;
import com.evosim.mod.entity.CourtRecord;
import com.evosim.mod.entity.MimicEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 짝매칭 현황판 스냅샷 (GUI 데이터). 서버가 주변 미믹의 구애 상태를 모아 직렬화 → 클라 화면이 렌더.
 * 크기 유계를 위해 개체·후보·기록 수를 상한한다.
 */
public final class MateBoardSnapshot {

    public static final double RADIUS = 96.0;
    private static final int MAX_ENTRIES = 60;
    private static final int MAX_CAND = 8;
    private static final int MAX_REC = 6;

    /** 후보 한 명 — id + 내 기준 매력. */
    public static final class Cand {
        public final int id;
        public final int charm;

        public Cand(int id, int charm) {
            this.id = id;
            this.charm = charm;
        }
    }

    /** 구애 기록 한 건. */
    public static final class Rec {
        public final int kind; // 0=COURTED(내가 구애), 1=RECEIVED(구애받음)
        public final int otherId;
        public final boolean accepted;
        public final int charm;
        public final int rank;
        public final int pool;
        public final int percent;

        public Rec(int kind, int otherId, boolean accepted, int charm, int rank, int pool, int percent) {
            this.kind = kind;
            this.otherId = otherId;
            this.accepted = accepted;
            this.charm = charm;
            this.rank = rank;
            this.pool = pool;
            this.percent = percent;
        }
    }

    /** 개체 한 명의 현황. */
    public static final class Entry {
        public final int id;
        public final boolean female;
        public final String choice;   // 까다로움 라벨
        public final int k;
        public final String state;    // MateState 이름
        public final List<Cand> candidates;
        public final List<Rec> records;

        public Entry(int id, boolean female, String choice, int k, String state,
                     List<Cand> candidates, List<Rec> records) {
            this.id = id;
            this.female = female;
            this.choice = choice;
            this.k = k;
            this.state = state;
            this.candidates = candidates;
            this.records = records;
        }
    }

    public final List<Entry> entries;

    public MateBoardSnapshot(List<Entry> entries) {
        this.entries = entries;
    }

    /** 플레이어 주변 미믹들의 현황을 모은다. */
    public static MateBoardSnapshot build(Player viewer) {
        List<Entry> out = new ArrayList<>();
        List<MimicEntity> near = viewer.level().getEntitiesOfClass(
                MimicEntity.class, viewer.getBoundingBox().inflate(RADIUS));
        for (MimicEntity m : near) {
            if (out.size() >= MAX_ENTRIES) {
                break;
            }
            Individual ind = m.getIndividual();
            if (ind == null || m.getStage() != com.evosim.core.LifeStage.ADULT) {
                continue; // 성년만(구애 참여)
            }
            List<Cand> cands = new ArrayList<>();
            for (int id : m.getCandidateIds()) {
                if (cands.size() >= MAX_CAND) {
                    break;
                }
                cands.add(new Cand(id, m.candidateCharmOf(id)));
            }
            List<Rec> recs = new ArrayList<>();
            List<CourtRecord> log = m.getCourtLog();
            int from = Math.max(0, log.size() - MAX_REC);
            for (int i = from; i < log.size(); i++) {
                CourtRecord r = log.get(i);
                recs.add(new Rec(r.kind == CourtRecord.Kind.COURTED ? 0 : 1,
                        r.otherId, r.accepted, r.charm, r.rank, r.pool, r.percent));
            }
            out.add(new Entry(m.getId(), m.isFemale(), ind.mateChoice().label(),
                    ind.mateChoice().k(), m.getMateState().name(), cands, recs));
        }
        return new MateBoardSnapshot(out);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeVarInt(e.id);
            buf.writeBoolean(e.female);
            buf.writeUtf(e.choice);
            buf.writeVarInt(e.k);
            buf.writeUtf(e.state);
            buf.writeVarInt(e.candidates.size());
            for (Cand c : e.candidates) {
                buf.writeVarInt(c.id);
                buf.writeVarInt(c.charm);
            }
            buf.writeVarInt(e.records.size());
            for (Rec r : e.records) {
                buf.writeByte(r.kind);
                buf.writeVarInt(r.otherId);
                buf.writeBoolean(r.accepted);
                buf.writeVarInt(r.charm);
                buf.writeVarInt(r.rank);
                buf.writeVarInt(r.pool);
                buf.writeVarInt(r.percent);
            }
        }
    }

    public static MateBoardSnapshot decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int id = buf.readVarInt();
            boolean female = buf.readBoolean();
            String choice = buf.readUtf();
            int k = buf.readVarInt();
            String state = buf.readUtf();
            int cn = buf.readVarInt();
            List<Cand> cands = new ArrayList<>(cn);
            for (int c = 0; c < cn; c++) {
                cands.add(new Cand(buf.readVarInt(), buf.readVarInt()));
            }
            int rn = buf.readVarInt();
            List<Rec> recs = new ArrayList<>(rn);
            for (int r = 0; r < rn; r++) {
                recs.add(new Rec(buf.readByte(), buf.readVarInt(), buf.readBoolean(),
                        buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
            }
            entries.add(new Entry(id, female, choice, k, state, cands, recs));
        }
        return new MateBoardSnapshot(entries);
    }
}
