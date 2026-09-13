package com.evosim.mod.gui;

import com.evosim.core.ExpressionResolver;
import com.evosim.core.Lineage;
import com.evosim.core.Trait;
import com.evosim.mod.entity.FacilityStore;
import com.evosim.mod.entity.FacilityTemplate;
import com.evosim.mod.entity.FamilyLedger;
import com.evosim.mod.entity.FarmTicker;
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
    // ── 탭(UI P4) — 서버가 완성한 줄. 화면은 그리기만 한다. ──
    public final List<String> edu = new ArrayList<>();    // 학력·학위
    public final List<String> fac = new ArrayList<>();    // 시설
    public final List<String> army = new ArrayList<>();   // 군
    public final List<String> realm = new ArrayList<>();  // 영지

    public StatsSnapshot(int living, List<Bar> bars, List<Top> tops) {
        this.living = living;
        this.bars = bars;
        this.tops = tops;
    }

    /** 학력·학위 / 시설 / 군 / 영지 탭 줄 조립 — 관측 전용. */
    private static void buildTabs(ServerLevel level, StatsSnapshot out) {
        Map<Long, MimicEntity> living = com.evosim.mod.gui.DeedText.living(level);
        // 학력·학위 — 단계별 교육수준 분포, 학위자 명단, 학교 재적.
        int[] lvAdult = new int[4];
        int[] lvBoy = new int[4];
        int[] deg = new int[3];
        List<String> holders = new ArrayList<>();
        for (MimicEntity m : living.values()) {
            if (m.isStageActor()) {
                continue;
            }
            int lv = m.schoolLevel();
            if (m.getStage() == com.evosim.core.LifeStage.BOY) {
                lvBoy[lv]++;
            } else if (m.getStage() != com.evosim.core.LifeStage.INFANT) {
                lvAdult[lv]++;
            }
            deg[m.getDegree()]++;
            if (m.getDegree() > 0) {
                holders.add(m.getIndividual().shortName() + " "
                        + com.evosim.mod.gui.DeedText.degreeName(m.getDegree())
                        + " · " + com.evosim.mod.gui.DeedText.roleOf(level, m));
            }
        }
        out.edu.add(String.format("성년+ 교육수준: 무학 %d · 초급 %d · 중급 %d · 상급 %d",
                lvAdult[0], lvAdult[1], lvAdult[2], lvAdult[3]));
        out.edu.add(String.format("소년 교육수준: 무학 %d · 초급 %d · 중급 %d · 상급 %d",
                lvBoy[0], lvBoy[1], lvBoy[2], lvBoy[3]));
        out.edu.add(String.format("학위: 없음 %d · 학사 %d · 석사 %d", deg[0], deg[1], deg[2]));
        double[] ss = FarmTicker.schoolSums();
        out.edu.add(String.format("학교 %d채 · 새벽 소년 %.0f · 등록 %.0f",
                FacilityStore.get(level).countOf(FacilityTemplate.Kind.SCHOOL), ss[1], ss[0]));
        if (holders.isEmpty()) {
            out.edu.add("학위자 없음 (대학은 아직 없다)");
        } else {
            out.edu.add("학위자:");
            out.edu.addAll(holders);
        }
        // 시설 — 한 채 한 줄.
        FacilityStore reg = FacilityStore.get(level);
        out.fac.add(reg.all().isEmpty() ? "시설 없음" : "시설 " + reg.all().size() + "채 (땅 문서로 시설을 찍으면 상세)");
        for (FacilityStore.Entry e : reg.all()) {
            String staff = e.staffId == 0L ? "" : " · 직원 " + com.evosim.mod.gui.DeedText.nameOf(level, e.staffId)
                    + (e.staff2Id == 0L ? "" : ", " + com.evosim.mod.gui.DeedText.nameOf(level, e.staff2Id));
            out.fac.add(String.format("%s @%d,%d · 주인 %s%s · d%d · 순 %+.1f", e.kind.label,
                    e.pos.getX(), e.pos.getZ(), com.evosim.mod.gui.DeedText.nameOf(level, e.ownerId),
                    staff, e.foundedDay, e.net()));
        }
        // 군 — 막사별 정원/배속/봉급, 경비대.
        int soldiers = 0;
        int guards = 0;
        for (MimicEntity m : living.values()) {
            if (FarmTicker.isSoldier(m)) {
                soldiers++;
            }
            if (m.getPoorhouse() != null) {
                guards++;
            }
        }
        out.army.add("병사 " + soldiers + " · 경비대원 " + guards);
        for (FacilityStore.Entry e : reg.all()) {
            if (e.kind != FacilityTemplate.Kind.BARRACKS && e.kind != FacilityTemplate.Kind.POORHOUSE) {
                continue;
            }
            for (String l : com.evosim.mod.gui.DeedText.facilityLines(level, e)) {
                if (l.startsWith("정원") || l.startsWith("병사") || l.startsWith("대원")) {
                    out.army.add(e.kind.label + " @" + e.pos.getX() + "," + e.pos.getZ()
                            + "(" + com.evosim.mod.gui.DeedText.nameOf(level, e.ownerId) + ") " + l);
                }
            }
        }
        // 영지 — 밤 보고와 같은 문장.
        out.realm.addAll(FarmTicker.realmLines());
        if (out.realm.isEmpty()) {
            out.realm.add("아직 영지 보고 없음 (추종 가구·세수·통치지출이 전부 0)");
        }
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
        StatsSnapshot out = new StatsSnapshot(living, bars, tops);
        buildTabs(level, out);
        return out;
    }

    private static void writeLines(FriendlyByteBuf buf, List<String> ls) {
        buf.writeVarInt(ls.size());
        for (String l : ls) {
            buf.writeUtf(l);
        }
    }

    private static void readLines(FriendlyByteBuf buf, List<String> into) {
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++) {
            into.add(buf.readUtf());
        }
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
        writeLines(buf, edu); // 신규 필드는 맨 끝(encode/decode 순서 불변식)
        writeLines(buf, fac);
        writeLines(buf, army);
        writeLines(buf, realm);
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
        StatsSnapshot out = new StatsSnapshot(living, bars, tops);
        readLines(buf, out.edu);
        readLines(buf, out.fac);
        readLines(buf, out.army);
        readLines(buf, out.realm);
        return out;
    }
}
