package com.evosim.mod.gui;

import com.evosim.core.Lineage;
import com.evosim.mod.entity.FamilyLedger;
import com.evosim.mod.entity.MimicEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 가계도 스냅샷 (서버 계산 → 클라 표시). 포커스 개체 + 조상 {@link #DEPTH}세대를
 * {@link Lineage#ancestorGrid} 배치 그대로 담는다 — 노드 클릭 시 그 조상을 포커스로 재조회해
 * 위로 무한 항해한다. id 0 = 미상(회색), 살아있는 개체는 엔티티 id(#) 병기.
 */
public class PedigreeSnapshot {

    /** 한 화면에 담는 조상 세대 수(부모·조부모·증조) — 그 위는 클릭 항해. */
    public static final int DEPTH = 3;

    /** 그리드 한 칸. id==0 이면 나머지 필드는 무의미(미상). */
    public static final class Node {
        public final long id;
        public final int serial;      // 원장 일련번호(안정 보조 식별자 — 툴팁 병기)
        public final int entityId;    // 살아있으면 엔티티 id, 아니면 -1
        public final boolean female;
        public final int gen;
        public final boolean alive;
        public final long bornDay;
        public final long diedDay;    // -1 = 생존/미확인
        public final int descendants; // 총 후손 수(중복 제거)
        public final int children;    // 직접 자식 수
        public final String name;     // 짧은 성명(원장 박제 — 사후에도 이름 표시)

        public Node(long id, int serial, int entityId, boolean female, int gen, boolean alive,
                    long bornDay, long diedDay, int descendants, int children, String name) {
            this.id = id;
            this.serial = serial;
            this.entityId = entityId;
            this.female = female;
            this.gen = gen;
            this.alive = alive;
            this.bornDay = bornDay;
            this.diedDay = diedDay;
            this.descendants = descendants;
            this.children = children;
            this.name = name;
        }

        static Node unknown() {
            return new Node(0, 0, -1, false, 0, false, 0, -1, 0, 0, "");
        }
    }

    /** rows[d][i] — d=0 포커스 1칸, d 행은 2^d 칸(Lineage 그리드와 동일 인덱싱). */
    public final Node[][] rows;

    public PedigreeSnapshot(Node[][] rows) {
        this.rows = rows;
    }

    /** 서버측 조립 — 원장 + 살아있는 엔티티 대조(엔티티 id 병기·생존 표시). */
    public static PedigreeSnapshot build(ServerLevel level, long focusId) {
        FamilyLedger ledger = FamilyLedger.get(level);
        Map<Long, long[]> parents = ledger.parentsMap();
        Map<Long, List<Long>> childrenIdx = Lineage.childrenIndex(parents);
        // 살아있는 개체의 entity id 대조표(원장은 엔티티 id를 저장하지 않음 — 휘발이라).
        Map<Long, Integer> aliveIds = new HashMap<>();
        for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null)) {
            aliveIds.put(m.getIndividual().id(), m.getId());
        }
        long[][] grid = Lineage.ancestorGrid(focusId, DEPTH, parents);
        Node[][] rows = new Node[grid.length][];
        for (int d = 0; d < grid.length; d++) {
            rows[d] = new Node[grid[d].length];
            for (int i = 0; i < grid[d].length; i++) {
                long id = grid[d][i];
                FamilyLedger.Rec r = id == 0 ? null : ledger.get(id);
                if (r == null) {
                    rows[d][i] = Node.unknown();
                    continue;
                }
                Integer eid = aliveIds.get(id);
                rows[d][i] = new Node(id, r.serial, eid == null ? -1 : eid, r.female, r.gen,
                        eid != null, r.bornDay, r.diedDay,
                        Lineage.descendantCount(id, childrenIdx),
                        Lineage.childCount(id, childrenIdx),
                        r.name == null ? "N" + r.serial : r.name);
            }
        }
        return new PedigreeSnapshot(rows);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(rows.length);
        for (Node[] row : rows) {
            buf.writeVarInt(row.length);
            for (Node n : row) {
                buf.writeLong(n.id);
                buf.writeVarInt(n.serial);
                buf.writeVarInt(n.entityId);
                buf.writeBoolean(n.female);
                buf.writeVarInt(n.gen);
                buf.writeBoolean(n.alive);
                buf.writeLong(n.bornDay);
                buf.writeLong(n.diedDay);
                buf.writeVarInt(n.descendants);
                buf.writeVarInt(n.children);
                buf.writeUtf(n.name);
            }
        }
    }

    public static PedigreeSnapshot decode(FriendlyByteBuf buf) {
        int depth = buf.readVarInt();
        Node[][] rows = new Node[depth][];
        for (int d = 0; d < depth; d++) {
            int w = buf.readVarInt();
            rows[d] = new Node[w];
            for (int i = 0; i < w; i++) {
                rows[d][i] = new Node(buf.readLong(), buf.readVarInt(), buf.readVarInt(),
                        buf.readBoolean(), buf.readVarInt(), buf.readBoolean(),
                        buf.readLong(), buf.readLong(), buf.readVarInt(), buf.readVarInt(),
                        buf.readUtf());
            }
        }
        return new PedigreeSnapshot(rows);
    }
}
