package com.evosim.mod.net;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 검사봉 렌즈 스냅샷 (P1 데이터 계층) — 조준한 미믹의 카드 표시용 전 필드. 서버가
 * {@code MimicEntity.buildScanSnapshot}으로 조립해 {@link ScanPacket}으로 내려보낸다.
 * 문턱(lack)류는 서버의 실제 판정식(familyTick과 동일한 순수 함수)을 그대로 역산한 값 —
 * 클라는 계산하지 않고 표시만 한다(판정-코드 대칭).
 */
public final class ScanSnapshot {

    // ── 개체 ──
    public int entityId;
    public long serial;          // Individual.id
    public boolean female;
    public int stage;            // LifeStage ordinal
    public int generation;
    public boolean stageActor;
    public float holding;        // H
    public float health;
    public float maxHealth;

    // ── 행동/목표 ──
    public String action = "";   // 실행 중 최우선 goal 라벨(예: 채집/귀가/구애/취침/대기)
    public boolean hasNav;
    public int navX;
    public int navZ;

    // ── 상태 배지 ──
    public boolean satisfied;
    public boolean critical;
    public boolean building;
    public boolean courtTravel;
    public long tenantFarm;      // 0 = 소작 아님

    /** 성명(짧은 표기 "윌리엄 스미스") — 카드 헤더용. */
    public String name = "";

    /** 소작 근무처 — "상시/일용 구획 N(T타일) · 지주 성명". 비소작이면 빈 문자열. */
    public String tenantInfo = "";

    /** 토지 요약(서버 사전 포맷, "\n" 구분 다행) — 이 미믹이 소유·창설한 밭의 원장 개요(LAND 탭). */
    public String landSummary = "";

    // ── 특성/성향 ──
    public String traits = "";   // 발현 특성 한글 나열
    public String parenting = "";
    public String mateChoice = "";

    // ── 가구 ──
    public float larder = -1;    // -1 = 무거처
    public int garden;           // 심어진 베리 그루
    public int gardenCap;
    public int farmPlots;
    public int farmTiles;
    public int adults;
    public int boys;
    public int infants;
    public int elders;
    public long spouseId;        // 0 = 미혼

    // ── 다음 문턱(부족량: 0 = 지금 충족, 음수 센티널: -1 완료/해당없음, -2 부부 아님) ──
    public float reproNeed;
    public float reproLack;
    /** 출산 쿨다운 잔여일(0 = 대기 없음) — 식량이 충족이어도 이 값이 남으면 출산하지 않는다. */
    public float reproCooldown;
    public float berryNeed;
    public float berryLack;
    public float farmNeed;
    public float farmLack;
    public boolean farmMotive;   // 개간 동기(불만족 ∧ 무욕 아님)
    /** 자금은 됐으나 성숙 트리거(최신 밭 24타일 + 상시 소작 ≥1)가 남았을 때의 사유. 빈 문자열 = 없음. */
    public String farmGate = "";

    // ── 신분 탭(UI P4) — 전부 서버 사전 포맷 문자열. 빈 문자열 = 해당 없음. ──
    /** "학력 초급 · 학위 학사" */
    public String school = "";
    /** 배우자 성명(사후 포함) — 짝 탭의 "혼인 (배우자 N123)" 을 실명으로. */
    public String spouseName = "";
    /** "부 성명 · 모 성명" */
    public String parents = "";
    /** 자식 목록 — "자식 3(생존 2 · 사망 1)" + "\n" 구분 이름 줄. */
    public String children = "";
    /** 직위 — 마름/병사/교사/목사/선교사/경비대/학생. 여럿이면 " · " 연결. */
    public String role = "";
    /** 소속 시설 — "막사 @x,z" 처럼 직위가 딸린 시설. */
    public String facility = "";
    /** 추종 — "추종 성명(신뢰) · 신세 4.2 · 빚 0" */
    public String patron = "";
    /** 오늘 — "자영 수확 5칸 · 상시소작 근속 3일" */
    public String today = "";

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeLong(serial);
        buf.writeBoolean(female);
        buf.writeVarInt(stage);
        buf.writeVarInt(generation);
        buf.writeBoolean(stageActor);
        buf.writeFloat(holding);
        buf.writeFloat(health);
        buf.writeFloat(maxHealth);
        buf.writeUtf(action);
        buf.writeBoolean(hasNav);
        buf.writeVarInt(navX);
        buf.writeVarInt(navZ);
        buf.writeBoolean(satisfied);
        buf.writeBoolean(critical);
        buf.writeBoolean(building);
        buf.writeBoolean(courtTravel);
        buf.writeLong(tenantFarm);
        buf.writeUtf(traits);
        buf.writeUtf(parenting);
        buf.writeUtf(mateChoice);
        buf.writeFloat(larder);
        buf.writeVarInt(garden);
        buf.writeVarInt(gardenCap);
        buf.writeVarInt(farmPlots);
        buf.writeVarInt(farmTiles);
        buf.writeVarInt(adults);
        buf.writeVarInt(boys);
        buf.writeVarInt(infants);
        buf.writeVarInt(elders);
        buf.writeLong(spouseId);
        buf.writeFloat(reproNeed);
        buf.writeFloat(reproLack);
        buf.writeFloat(reproCooldown);
        buf.writeFloat(berryNeed);
        buf.writeFloat(berryLack);
        buf.writeFloat(farmNeed);
        buf.writeFloat(farmLack);
        buf.writeBoolean(farmMotive);
        buf.writeUtf(name); // 성명 — 신규 필드는 맨 끝(encode/decode 순서 불변식)
        buf.writeUtf(tenantInfo);
        buf.writeUtf(landSummary);
        buf.writeUtf(farmGate);
        buf.writeUtf(school);
        buf.writeUtf(spouseName);
        buf.writeUtf(parents);
        buf.writeUtf(children);
        buf.writeUtf(role);
        buf.writeUtf(facility);
        buf.writeUtf(patron);
        buf.writeUtf(today);
    }

    public static ScanSnapshot decode(FriendlyByteBuf buf) {
        ScanSnapshot s = new ScanSnapshot();
        s.entityId = buf.readVarInt();
        s.serial = buf.readLong();
        s.female = buf.readBoolean();
        s.stage = buf.readVarInt();
        s.generation = buf.readVarInt();
        s.stageActor = buf.readBoolean();
        s.holding = buf.readFloat();
        s.health = buf.readFloat();
        s.maxHealth = buf.readFloat();
        s.action = buf.readUtf();
        s.hasNav = buf.readBoolean();
        s.navX = buf.readVarInt();
        s.navZ = buf.readVarInt();
        s.satisfied = buf.readBoolean();
        s.critical = buf.readBoolean();
        s.building = buf.readBoolean();
        s.courtTravel = buf.readBoolean();
        s.tenantFarm = buf.readLong();
        s.traits = buf.readUtf();
        s.parenting = buf.readUtf();
        s.mateChoice = buf.readUtf();
        s.larder = buf.readFloat();
        s.garden = buf.readVarInt();
        s.gardenCap = buf.readVarInt();
        s.farmPlots = buf.readVarInt();
        s.farmTiles = buf.readVarInt();
        s.adults = buf.readVarInt();
        s.boys = buf.readVarInt();
        s.infants = buf.readVarInt();
        s.elders = buf.readVarInt();
        s.spouseId = buf.readLong();
        s.reproNeed = buf.readFloat();
        s.reproLack = buf.readFloat();
        s.reproCooldown = buf.readFloat();
        s.berryNeed = buf.readFloat();
        s.berryLack = buf.readFloat();
        s.farmNeed = buf.readFloat();
        s.farmLack = buf.readFloat();
        s.farmMotive = buf.readBoolean();
        s.name = buf.readUtf();
        s.tenantInfo = buf.readUtf();
        s.landSummary = buf.readUtf();
        s.farmGate = buf.readUtf();
        s.school = buf.readUtf();
        s.spouseName = buf.readUtf();
        s.parents = buf.readUtf();
        s.children = buf.readUtf();
        s.role = buf.readUtf();
        s.facility = buf.readUtf();
        s.patron = buf.readUtf();
        s.today = buf.readUtf();
        return s;
    }
}
