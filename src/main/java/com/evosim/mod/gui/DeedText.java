package com.evosim.mod.gui;

import com.evosim.core.LifeStage;
import com.evosim.core.Schooling;
import com.evosim.mod.entity.AllegianceStore;
import com.evosim.mod.entity.Facilities;
import com.evosim.mod.entity.FacilityStore;
import com.evosim.mod.entity.FacilityTemplate;
import com.evosim.mod.entity.FamilyLedger;
import com.evosim.mod.entity.FarmStore;
import com.evosim.mod.entity.FarmTicker;
import com.evosim.mod.entity.HomeStore;
import com.evosim.mod.entity.LarderStore;
import com.evosim.mod.entity.MimicEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 관측 문장 조립(UI P4) — 검사봉 신분 탭·시설 문서·가구 문서·{@code evosim facility} 가 같은
 * 문장을 쓴다. <b>서버 전용</b>이고 표시만 한다: 여기서 읽는 값은 전부 시뮬이 이미 정한 것이고,
 * 이 클래스는 아무것도 바꾸지 않는다.
 *
 * <p>왜 한 곳에 두나 — 같은 사실을 화면마다 다르게 세면 "검사봉은 병사라는데 문서는 아니다" 같은
 * 어긋남이 생긴다. 직위·추종·학력의 문장은 여기 한 벌뿐이다.
 */
public final class DeedText {

    private DeedText() {
    }

    /** 개체 id → 성명. 원장 우선(사후 포함), 없으면 "#id", 0 이면 "—". */
    public static String nameOf(ServerLevel sl, long id) {
        if (id == 0L) {
            return "—";
        }
        FamilyLedger.Rec r = FamilyLedger.get(sl).get(id);
        if (r != null && r.name != null && !r.name.isEmpty()) {
            return r.name;
        }
        return "#" + id;
    }

    public static String stageName(LifeStage st) {
        return switch (st) {
            case INFANT -> "유아";
            case BOY -> "소년";
            case ELDER -> "노년";
            default -> "성년";
        };
    }

    public static String degreeName(int degree) {
        return com.evosim.core.Degree.name(degree);
    }

    /** "학력 초급 · 학위 학사" */
    public static String schoolLine(MimicEntity m) {
        return "학력 " + Schooling.name(m.schoolLevel()) + "(" + m.getSchoolDays() + "일)"
                + " · 학위 " + degreeName(m.getDegree());
    }

    /** 살아 있는 개체 id → 개체. 한 번 만들어 여러 문장이 나눠 쓴다. */
    public static Map<Long, MimicEntity> living(ServerLevel sl) {
        Map<Long, MimicEntity> byId = new java.util.HashMap<>();
        for (MimicEntity m : sl.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null)) {
            byId.putIfAbsent(m.getIndividual().id(), m);
        }
        return byId;
    }

    /**
     * 직위 — 마름·병사·교사·목사·성직자·선교사·경비대·학생. 여럿이면 " · " 로 잇는다. 없으면 "".
     * 판정은 시뮬의 단일 출처(FarmStore.stewardOf · FarmTicker.isSoldier/isPastor/schoolOf ·
     * FacilityStore.staffId)를 그대로 읽는다 — 여기서 새로 정하지 않는다.
     */
    public static String roleOf(ServerLevel sl, MimicEntity m) {
        if (m.getIndividual() == null) {
            return "";
        }
        long id = m.getIndividual().id();
        List<String> roles = new ArrayList<>();
        long stewardPlot = FarmStore.get(sl).stewardOf(id);
        if (stewardPlot != 0L) {
            roles.add("마름(구획 " + stewardPlot + ")");
        }
        long overseerPlot = FarmStore.get(sl).overseerOf(id);
        if (overseerPlot != 0L) {
            roles.add("감독관(구획 " + overseerPlot + ")");
        }
        if (FarmTicker.isSoldier(m)) {
            roles.add(FarmTicker.isCommander(m)
                    ? String.format("지휘관(급여 %.0f)", com.evosim.core.Commander.WAGE)
                    : String.format("병사(봉급 %.1f)", FarmTicker.soldierWageOf(m)));
        }
        if (m.getPoorhouse() != null) {
            roles.add(String.format("경비대(봉급 %.1f)", m.getGuardWage()));
        }
        for (FacilityStore.Entry e : FacilityStore.get(sl).all()) {
            if (e.staffId == id) {
                if (e.kind == FacilityTemplate.Kind.SCHOOL) {
                    roles.add("교사");
                } else if (e.kind.group == FacilityTemplate.Group.CHURCH) {
                    roles.add(FarmTicker.isPastor(m) ? "목사(전업)" : "성직자");
                } else {
                    roles.add(e.kind.label + " 직원");
                }
            }
            if (e.staff2Id == id && e.kind.group == FacilityTemplate.Group.CHURCH) {
                roles.add("선교사(부업)");
            }
        }
        if (m.getStage() == LifeStage.BOY && FarmTicker.schoolOf(m) != null) {
            roles.add("학생");
        }
        int tiles = FarmStore.get(sl).ownedTiles(id);
        if (tiles > 0) {
            roles.add("지주(" + tiles + "타일)");
        }
        return String.join(" · ", roles);
    }

    /** 소속 시설 — 직위가 딸린 시설 좌표들. 없으면 "". */
    public static String facilityOf(ServerLevel sl, MimicEntity m) {
        if (m.getIndividual() == null) {
            return "";
        }
        long id = m.getIndividual().id();
        List<String> out = new ArrayList<>();
        BlockPos post = FarmTicker.postOf(m);
        if (post != null) {
            out.add("막사 @" + post.getX() + "," + post.getZ());
        }
        if (m.getPoorhouse() != null) {
            out.add("경비대 @" + m.getPoorhouse().getX() + "," + m.getPoorhouse().getZ());
        }
        for (FacilityStore.Entry e : FacilityStore.get(sl).all()) {
            if (e.staffId == id || e.staff2Id == id) {
                out.add(e.kind.label + " @" + e.pos.getX() + "," + e.pos.getZ());
            }
        }
        BlockPos school = m.getStage() == LifeStage.BOY ? FarmTicker.schoolOf(m) : null;
        if (school != null) {
            out.add("학교 @" + school.getX() + "," + school.getZ());
        }
        return String.join(" · ", out);
    }

    /** "추종 성명(신뢰) · 신세 4.2 · 빚 0.0" — 주인이 없으면 "". */
    public static String patronLine(ServerLevel sl, MimicEntity m) {
        if (m.getIndividual() == null) {
            return "";
        }
        long id = m.getIndividual().id();
        AllegianceStore ledger = AllegianceStore.get(sl);
        int tiles = FarmStore.get(sl).ownedTiles(id);
        long patron = ledger.patronOf(id, tiles);
        double owed = ledger.owedOf(id);
        if (patron == 0L) {
            return owed > 0.005 ? String.format("추종 없음 · 빚 %.1f", owed) : "";
        }
        AllegianceStore.Tier tier = ledger.tierOf(id, tiles, null);
        return String.format("추종 %s(%s) · 신세 %.1f · 빚 %.1f", nameOf(sl, patron), tier.label(),
                ledger.bondTo(id, patron), owed);
    }

    /** "자영 수확 5칸 · 상시소작 근속 3일 · 오늘 출근포기 1" — 아무것도 없으면 "". */
    public static String todayLine(MimicEntity m) {
        List<String> out = new ArrayList<>();
        int self = FarmTicker.selfHarvestToday(m.getId());
        if (self > 0) {
            out.add("자영 수확 " + self + "칸");
        }
        if (m.getTenantFarm() != 0L) {
            out.add("상시소작 근속 " + m.getTenantStreak() + "일");
            if (m.getTenantNoShow() > 0) {
                out.add("출근포기 " + m.getTenantNoShow() + "일째");
            }
        }
        return String.join(" · ", out);
    }

    /** "부 성명 · 모 성명" — 부모 기록이 없으면 "". */
    public static String parentsLine(ServerLevel sl, MimicEntity m) {
        if (m.getIndividual() == null) {
            return "";
        }
        long a = m.getIndividual().parentAId();
        long b = m.getIndividual().parentBId();
        if (a == 0L && b == 0L) {
            return "";
        }
        return nameOf(sl, a) + " · " + nameOf(sl, b);
    }

    /**
     * 자식 목록 — 첫 줄 "자식 3(생존 2 · 사망 1)", 다음 줄부터 "성명 소년 학력 초급" 식. 원장
     * 전수라 죽은 자식도 센다. 없으면 "".
     */
    public static String childrenLines(ServerLevel sl, long id, Map<Long, MimicEntity> living) {
        if (id == 0L) {
            return "";
        }
        List<FamilyLedger.Rec> kids = new ArrayList<>();
        for (FamilyLedger.Rec r : FamilyLedger.get(sl).all().values()) {
            if (r.pa == id || r.pb == id) {
                kids.add(r);
            }
        }
        if (kids.isEmpty()) {
            return "";
        }
        kids.sort((x, y) -> Long.compare(x.bornDay, y.bornDay));
        int alive = 0;
        StringBuilder sb = new StringBuilder();
        for (FamilyLedger.Rec r : kids) {
            MimicEntity e = living.get(r.id);
            boolean isAlive = e != null;
            if (isAlive) {
                alive++;
            }
            sb.append('\n').append(r.female ? "♀" : "♂").append(r.name == null ? "N" + r.serial : r.name);
            if (isAlive) {
                sb.append(' ').append(stageName(e.getStage()))
                        .append(" 학력 ").append(Schooling.name(e.schoolLevel()));
                if (e.getDegree() > 0) {
                    sb.append(' ').append(degreeName(e.getDegree()));
                }
            } else {
                sb.append(" 사망 d").append(r.diedDay);
            }
        }
        return "자식 " + kids.size() + "(생존 " + alive + " · 사망 " + (kids.size() - alive) + ")" + sb;
    }

    // ── 시설 문서 ─────────────────────────────────────────────────────────────

    public static String facilityTitle(FacilityStore.Entry e) {
        return e.kind.label + " 문서  @" + e.pos.getX() + ", " + e.pos.getZ();
    }

    /**
     * 시설 한 채의 문서 본문 — 종류·주인·등기자·등기일·직원과 급여·정원/재적·계정·수입·지출·
     * 보전·분배·손익. 이력은 {@link FacilityStore.Entry#history} 를 따로 싣는다.
     */
    public static List<String> facilityLines(ServerLevel sl, FacilityStore.Entry e) {
        List<String> ls = new ArrayList<>();
        Map<Long, MimicEntity> living = living(sl);
        boolean ownerAlive = living.containsKey(e.ownerId);
        ls.add("주인 " + nameOf(sl, e.ownerId) + (ownerAlive ? "" : " (사망)")
                + " · 추종자 " + FarmTicker.followersOf(e.ownerId));
        ls.add("등기 " + nameOf(sl, e.founderId) + " · d" + e.foundedDay
                + (e.founderId == e.ownerId ? "" : " (승계)"));
        int seats = FacilityTemplate.of(sl, e.kind, e.rotation, e.mirrored)
                .map(t -> t.seats().size()).orElse(0);
        switch (e.kind) {
            case SCHOOL -> {
                ls.add("교사 " + staff(sl, living, e.staffId, Facilities.TEACHER_WAGE_PER_DAY));
                ls.add("정원 " + seats + " · 재적 " + FarmTicker.studentsAt(e.pos));
            }
            case CHURCH, SMALL_CHURCH -> {
                boolean big = e.kind == FacilityTemplate.Kind.CHURCH;
                boolean pastor = FarmTicker.hasPastor(e);
                ls.add((pastor ? "목사 " : "성직자 ") + staff(sl, living, e.staffId,
                        pastor ? com.evosim.core.Church.PASTOR_WAGE : Facilities.CLERGY_WAGE_PER_DAY));
                if (big) {
                    ls.add("선교사 " + staff(sl, living, e.staff2Id, com.evosim.core.Church.MISSIONARY_WAGE));
                }
                ls.add(String.format("예배 정원 %d/일 · 헌금 %.2f/회 · 오늘 계정 %.1f",
                        com.evosim.core.Church.visitCap(big, pastor, Facilities.CHURCH_CAP,
                                Facilities.SMALL_CHURCH_CAP),
                        com.evosim.core.Church.tithe(pastor, Facilities.TITHE_PER_VISIT), e.account));
                ls.add(String.format("보전(주인 사비) 누계 %.1f · 분배(주인 수입) 누계 %.1f",
                        e.covered, e.paidOut));
            }
            case BARRACKS -> {
                ls.add("정원 " + seats + " · 배속 " + FarmTicker.soldiersAt(e.pos)
                        + String.format(" · 주인 어제 세수 %.1f", FarmTicker.lastTaxOf(e.ownerId)));
                ls.add("지휘관 " + (e.commanderId == 0L ? "— (학위자 없음 또는 정원 미달)"
                        : staff(sl, living, e.commanderId, com.evosim.core.Commander.WAGE)));
                ls.add(soldiersLine(sl, e.pos, living));
            }
            case POORHOUSE -> ls.add("정원 " + seats + " · " + guardsLine(sl, e.pos, living));
            case WINDMILL -> ls.add(String.format("제분 보너스 %.0f%% · 제분세 몫 %.0f%%",
                    Facilities.MILL_BONUS * 100.0, Facilities.MILL_TOLL_SHARE * 100.0));
            default -> {
                if (seats > 0) {
                    ls.add("자리 " + seats);
                }
            }
        }
        ls.add(String.format("수입 누계 %.1f · 지출 누계(건축+급여) %.1f · 순 %+.1f",
                e.earned, e.spent, e.net()));
        return ls;
    }

    private static String staff(ServerLevel sl, Map<Long, MimicEntity> living, long id, double wage) {
        if (id == 0L) {
            return "— (공석)";
        }
        MimicEntity m = living.get(id);
        String s = nameOf(sl, id) + String.format(" · 급여 %.1f", wage);
        if (m != null) {
            s += " · 학력 " + Schooling.name(m.schoolLevel());
            if (m.getDegree() > 0) {
                s += " " + degreeName(m.getDegree());
            }
        } else {
            s += " (사망)";
        }
        return s;
    }

    private static String soldiersLine(ServerLevel sl, BlockPos barracks, Map<Long, MimicEntity> living) {
        List<String> names = new ArrayList<>();
        double wages = 0.0;
        for (MimicEntity m : living.values()) {
            if (barracks.equals(FarmTicker.postOf(m))) {
                names.add(m.getIndividual().shortName());
                wages += FarmTicker.soldierWageOf(m);
            }
        }
        if (names.isEmpty()) {
            return "병사 없음";
        }
        return String.format("병사 %d(봉급 합 %.1f/일): ", names.size(), wages) + String.join(", ", names);
    }

    private static String guardsLine(ServerLevel sl, BlockPos house, Map<Long, MimicEntity> living) {
        List<String> names = new ArrayList<>();
        double wages = 0.0;
        for (MimicEntity m : living.values()) {
            if (house.equals(m.getPoorhouse())) {
                names.add(m.getIndividual().shortName());
                wages += m.getGuardWage();
            }
        }
        if (names.isEmpty()) {
            return "대원 없음";
        }
        return String.format("대원 %d(봉급 합 %.1f/일): ", names.size(), wages) + String.join(", ", names);
    }

    // ── 가구 문서 ─────────────────────────────────────────────────────────────

    public static String householdTitle(BlockPos home) {
        return "가구 문서  @" + home.getX() + ", " + home.getZ();
    }

    /**
     * 가구 한 집의 문서 본문 — 도면·가장·저장고·소유 밭·구성원(단계·학력·직위)·추종·유지비.
     * 구성원은 지금 이 집을 거처로 둔 산 개체(householdMembers 와 같은 기준).
     */
    public static List<String> householdLines(ServerLevel sl, BlockPos home) {
        List<String> ls = new ArrayList<>();
        HomeStore.Entry he = HomeStore.get(sl).entry(home);
        List<MimicEntity> fam = new ArrayList<>(sl.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null && home.equals(e.getHomePos())));
        fam.sort((a, b) -> {
            int c = Integer.compare(stageOrder(a.getStage()), stageOrder(b.getStage()));
            return c != 0 ? c : Long.compare(a.getIndividual().id(), b.getIndividual().id());
        });
        if (he != null) {
            ls.add("도면 " + he.design() + " · 가장 " + nameOf(sl, he.ownerId())
                    + " · 마지막 거주 확인 d" + he.lastSeenDay()
                    + (he.upkeepDue() > 0.005 ? String.format(" · 유지비 미납 %.1f", he.upkeepDue()) : ""));
        } else {
            ls.add("등기 없음(거처 원장에 없는 집)");
        }
        double larder = LarderStore.get(sl).get(home);
        int plots = 0;
        int tiles = 0;
        for (MimicEntity m : fam) {
            long id = m.getIndividual().id();
            plots += FarmStore.get(sl).ownedCount(id);
            tiles += FarmStore.get(sl).ownedTiles(id);
        }
        ls.add(String.format("저장고 %.1f · 밭 %d구획 %d타일 · 식구 %d", larder, plots, tiles, fam.size()));
        MimicEntity head = null;
        int headTiles = -1;
        for (MimicEntity m : fam) {
            if (m.getStage() == LifeStage.ADULT || m.getStage() == LifeStage.ELDER) {
                int t = FarmStore.get(sl).ownedTiles(m.getIndividual().id());
                if (t > headTiles) {
                    headTiles = t;
                    head = m;
                }
            }
        }
        if (head != null) {
            String p = patronLine(sl, head);
            ls.add(p.isEmpty() ? "추종 없음" : p);
        }
        for (MimicEntity m : fam) {
            String role = roleOf(sl, m);
            ls.add((m.isFemale() ? "♀" : "♂") + m.getIndividual().shortName() + " " + stageName(m.getStage())
                    + " · " + Schooling.name(m.schoolLevel())
                    + (m.getDegree() > 0 ? " " + degreeName(m.getDegree()) : "")
                    + (role.isEmpty() ? "" : " · " + role));
        }
        return ls;
    }

    private static int stageOrder(LifeStage st) {
        return switch (st) {
            case ELDER -> 0;
            case ADULT -> 1;
            case BOY -> 2;
            default -> 3;
        };
    }
}
