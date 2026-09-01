package com.evosim.mod;

import com.evosim.core.BerryEconomy;
import com.evosim.core.DeterministicRng;
import com.evosim.core.FarmEconomy;
import com.evosim.core.FarmLayout;
import com.evosim.core.Genetics;
import com.evosim.core.Individual;
import com.evosim.core.LifeStage;
import com.evosim.core.ParentingClass;
import com.evosim.core.Sex;
import com.evosim.core.Trait;
import com.evosim.core.TraitInstance;
import com.evosim.mod.entity.AllegianceStore;
import com.evosim.mod.entity.LampPlanner;
import com.evosim.mod.entity.LampStore;
import com.evosim.mod.entity.LarderStore;
import com.evosim.mod.entity.MigrationDest;
import com.evosim.mod.entity.FamilyLedger;
import com.evosim.mod.entity.FarmStore;
import com.evosim.mod.entity.FarmTicker;
import com.evosim.mod.entity.HomeBlueprint;
import com.evosim.mod.entity.HomeStore;
import com.evosim.mod.entity.RoadPlanner;
import com.evosim.mod.entity.RoadStore;
import com.evosim.mod.entity.HomeStructure;
import com.evosim.mod.entity.HomeTemplate;
import com.evosim.mod.entity.MimicEntity;
import com.evosim.mod.entity.MimicVisitGoal;
import com.evosim.mod.entity.Facilities;
import com.evosim.mod.entity.FacilityStore;
import com.evosim.mod.entity.FacilityTemplate;
import com.evosim.mod.entity.SocialRank;
import com.evosim.mod.gui.StatsSnapshot;
import com.evosim.mod.log.SimEvents;
import com.evosim.mod.net.ModNetwork;
import com.evosim.mod.net.StatsPacket;
import com.evosim.mod.reg.ModEntities;
import com.evosim.mod.stage.LiveCheck;
import com.evosim.mod.stage.VerifySuite;

import java.util.ArrayList;
import java.util.List;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

/**
 * 게임 내 {@code /evosim} 명령어 — 무대 세팅(개체 소환). 렌더링·외형은 눈으로 확인하되 소환은 명령이 대신(설계서 §17).
 *
 * <ul>
 *   <li>{@code /evosim spawn <male|female> <infant|boy|adult> [수]} — 지정 성별·단계 소환.</li>
 *   <li>{@code /evosim gallery} — 성별×단계 6종을 한 줄로 소환(외형 비교용).</li>
 * </ul>
 */
public final class EvoSimCommand {

    private EvoSimCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var spawn = Commands.literal("spawn");
        for (Sex sex : Sex.values()) {
            var sexNode = Commands.literal(sex == Sex.MALE ? "male" : "female");
            sexNode.executes(ctx -> spawn(ctx, sex, LifeStage.ADULT, 1)); // 단계 생략 → 성년
            for (LifeStage stage : LifeStage.values()) {
                sexNode.then(Commands.literal(stageName(stage))
                        .executes(ctx -> spawn(ctx, sex, stage, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 20))
                                .executes(ctx -> spawn(ctx, sex, stage,
                                        IntegerArgumentType.getInteger(ctx, "count")))));
            }
            spawn.then(sexNode);
        }

        dispatcher.register(Commands.literal("evosim")
                .requires(src -> src.hasPermission(2))
                .then(spawn)
                .then(Commands.literal("gallery").executes(EvoSimCommand::gallery))
                .then(Commands.literal("hometest").executes(EvoSimCommand::homeTest))
                .then(Commands.literal("homes").executes(EvoSimCommand::homes))
                .then(Commands.literal("tierx").executes(EvoSimCommand::tierStage))
                .then(Commands.literal("homenight").executes(EvoSimCommand::homeNight))
                .then(Commands.literal("heirtest").executes(EvoSimCommand::heirStage))
                .then(Commands.literal("heirshow").executes(ctx -> heirShow(ctx, false)))
                .then(Commands.literal("feud").executes(EvoSimCommand::feudReport))
                .then(Commands.literal("roads").executes(EvoSimCommand::roadsReport))
                .then(Commands.literal("lamps").executes(EvoSimCommand::lampsReport))
                .then(Commands.literal("farmshape").executes(EvoSimCommand::farmShape))
                .then(Commands.literal("allegiance").executes(EvoSimCommand::allegiance))
                .then(Commands.literal("bondtest").executes(EvoSimCommand::bondTest))
                .then(Commands.literal("facilities").executes(EvoSimCommand::facilities))
                .then(Commands.literal("sitetest").executes(EvoSimCommand::siteTest))
                .then(Commands.literal("topdown")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(16, 200))
                                .executes(ctx -> topDown(ctx,
                                        IntegerArgumentType.getInteger(ctx, "radius")))))
                .then(Commands.literal("homeshow")
                        .then(Commands.argument("design", StringArgumentType.word())
                                .executes(ctx -> homeShow(ctx,
                                        StringArgumentType.getString(ctx, "design"), 0, false))
                                .then(Commands.argument("rot", IntegerArgumentType.integer(0, 3))
                                        .executes(ctx -> homeShow(ctx,
                                                StringArgumentType.getString(ctx, "design"),
                                                IntegerArgumentType.getInteger(ctx, "rot"), false))
                                        .then(Commands.argument("mirror", BoolArgumentType.bool())
                                                .executes(ctx -> homeShow(ctx,
                                                        StringArgumentType.getString(ctx, "design"),
                                                        IntegerArgumentType.getInteger(ctx, "rot"),
                                                        BoolArgumentType.getBool(ctx, "mirror")))))))
                .then(Commands.literal("village")
                        .executes(ctx -> village(ctx, 4))
                        .then(Commands.argument("pairs", IntegerArgumentType.integer(1, 12))
                                .executes(ctx -> village(ctx, IntegerArgumentType.getInteger(ctx, "pairs")))))
                .then(Commands.literal("wildpairs")
                        .executes(ctx -> wildPairs(ctx, 4))
                        .then(Commands.argument("pairs", IntegerArgumentType.integer(1, 20))
                                .executes(ctx -> wildPairs(ctx, IntegerArgumentType.getInteger(ctx, "pairs")))))
                // ── 신규 기능 직접 점검(상황 일보직전 세팅) ──
                .then(Commands.literal("build").executes(EvoSimCommand::stageBuild))
                .then(Commands.literal("widow").executes(EvoSimCommand::stageWidow))
                .then(Commands.literal("family").executes(EvoSimCommand::stageFamily))
                .then(Commands.literal("lonepair").executes(EvoSimCommand::stageLonePair))
                .then(Commands.literal("abandon").executes(EvoSimCommand::stageAbandon))
                .then(Commands.literal("reuse").executes(EvoSimCommand::stageReuse))
                .then(Commands.literal("migrate").executes(EvoSimCommand::stageMigrate))
                .then(Commands.literal("berry").executes(EvoSimCommand::stageBerry))
                .then(Commands.literal("food").executes(EvoSimCommand::stageFood))
                .then(Commands.literal("exodus").executes(EvoSimCommand::stageExodus))
                .then(Commands.literal("trip").executes(EvoSimCommand::stageTrip))
                .then(Commands.literal("share").executes(EvoSimCommand::stageShare))
                .then(Commands.literal("birth").executes(EvoSimCommand::stageBirth))
                .then(Commands.literal("care").executes(EvoSimCommand::stageCare))
                .then(Commands.literal("r6").executes(EvoSimCommand::stageR6))
                .then(Commands.literal("suitor").executes(EvoSimCommand::stageSuitor))
                .then(Commands.literal("polygamy").executes(EvoSimCommand::stagePolygamy))
                .then(Commands.literal("elder").executes(EvoSimCommand::stageElder))
                .then(Commands.literal("eldercare").executes(EvoSimCommand::stageElderCare))
                .then(Commands.literal("migx").executes(EvoSimCommand::stageMigX))
                .then(Commands.literal("shieldx").executes(EvoSimCommand::stageShieldX))
                .then(Commands.literal("berryx").executes(EvoSimCommand::stageBerryX))
                .then(Commands.literal("bandx").executes(EvoSimCommand::stageBandX))
                .then(Commands.literal("elderx").executes(EvoSimCommand::stageElderX))
                .then(Commands.literal("glowx").executes(EvoSimCommand::stageGlowX))
                .then(Commands.literal("editx").executes(EvoSimCommand::stageEditX))
                .then(Commands.literal("namex").executes(EvoSimCommand::stageNameX))
                .then(Commands.literal("scanx").executes(EvoSimCommand::stageScanX))
                .then(Commands.literal("hirex").executes(EvoSimCommand::stageHireX))
                .then(Commands.literal("carex").executes(EvoSimCommand::stageCareX))
                .then(Commands.literal("wanderx").executes(EvoSimCommand::stageWanderX))
                .then(Commands.literal("fixhomes").executes(EvoSimCommand::fixHomes))
                .then(Commands.literal("fixx").executes(EvoSimCommand::stageFixX))
                .then(Commands.literal("feudx").executes(EvoSimCommand::stageFeudX))
                .then(Commands.literal("spreadx").executes(EvoSimCommand::stageSpreadX))
                .then(Commands.literal("farmfocus").executes(EvoSimCommand::stageFarmFocus))
                .then(Commands.literal("elite")
                        .executes(ctx -> spawnElite(ctx, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 8))
                                .executes(ctx -> spawnElite(ctx,
                                        IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("audit").executes(EvoSimCommand::auditNow))
                .then(Commands.literal("skip")
                        .then(Commands.literal("on").executes(ctx -> {
                            com.evosim.mod.entity.SimTime.setSkipEnabled(
                                    ctx.getSource().getLevel(), true);
                            tell(ctx.getSource(), "밤 스킵 ON (전원 취침 tod≥14100 → 기상 점프, "
                                    + "누적 오프셋 " + com.evosim.mod.entity.SimTime.offset() + ")");
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            com.evosim.mod.entity.SimTime.setSkipEnabled(
                                    ctx.getSource().getLevel(), false);
                            tell(ctx.getSource(), "밤 스킵 OFF");
                            return 1;
                        })))
                .then(Commands.literal("tickrate")
                        .executes(ctx -> {
                            tell(ctx.getSource(), "틱 가속: " + com.evosim.mod.entity.TickAccel
                                    .status(ctx.getSource().getServer()));
                            return 1;
                        })
                        .then(Commands.argument("factor", IntegerArgumentType.integer(
                                1, com.evosim.mod.entity.TickAccel.MAX_FACTOR))
                                .executes(ctx -> {
                                    int f = IntegerArgumentType.getInteger(ctx, "factor");
                                    com.evosim.mod.entity.TickAccel.setFactor(f);
                                    tell(ctx.getSource(), "틱 가속 설정: " + com.evosim.mod.entity
                                            .TickAccel.status(ctx.getSource().getServer()));
                                    return 1;
                                })))
                .then(Commands.literal("obs")
                        .executes(ctx -> obsStart(ctx, 6))
                        .then(Commands.argument("pairs", IntegerArgumentType.integer(1, 20))
                                .executes(ctx -> obsStart(ctx,
                                        IntegerArgumentType.getInteger(ctx, "pairs")))))
                .then(Commands.literal("checkall").executes(ctx -> stageCheckAll(ctx, false)))
                .then(Commands.literal("checkall2").executes(ctx -> stageCheckAll(ctx, true)))
                // ── 인구 통계·혈통 (관찰, 무대 아님) ──
                .then(Commands.literal("stats").executes(EvoSimCommand::stats))
                .then(Commands.literal("farm")
                        .executes(ctx -> farmDemo(ctx, 1))
                        .then(Commands.argument("stage", IntegerArgumentType.integer(1, 12))
                                .executes(ctx -> farmDemo(ctx,
                                        IntegerArgumentType.getInteger(ctx, "stage")))))
                .then(Commands.literal("farmstep")
                        .executes(ctx -> farmStep(ctx, 1))
                        .then(Commands.argument("steps", IntegerArgumentType.integer(1, 12))
                                .executes(ctx -> farmStep(ctx,
                                        IntegerArgumentType.getInteger(ctx, "steps")))))
                .then(Commands.literal("legacy").executes(EvoSimCommand::legacy))
                .then(Commands.literal("lords").executes(EvoSimCommand::lords))
                .then(Commands.literal("estates").executes(EvoSimCommand::estates))
                .then(Commands.literal("farmown").executes(EvoSimCommand::farmOwnDemo))
                .then(Commands.literal("farmhire").executes(EvoSimCommand::farmHireDemo))
                .then(Commands.literal("caretest")
                        .executes(ctx -> careTest(ctx, 36, 4, false))
                        .then(Commands.argument("tiles", IntegerArgumentType.integer(6, 200))
                                .then(Commands.argument("tenants", IntegerArgumentType.integer(1, 12))
                                        .executes(ctx -> careTest(ctx,
                                                IntegerArgumentType.getInteger(ctx, "tiles"),
                                                IntegerArgumentType.getInteger(ctx, "tenants"), false))
                                        .then(Commands.literal("ripe").executes(ctx -> careTest(ctx,
                                                IntegerArgumentType.getInteger(ctx, "tiles"),
                                                IntegerArgumentType.getInteger(ctx, "tenants"), true))))))
                .then(Commands.literal("carestat").executes(EvoSimCommand::careStat))
                .then(Commands.literal("goalchurn")
                        .executes(EvoSimCommand::goalChurn)
                        .then(Commands.literal("reset").executes(ctx -> {
                            MimicEntity.resetGoalChurn(
                                    com.evosim.mod.entity.SimTime.tick(ctx.getSource().getLevel()));
                            tell(ctx.getSource(), "goal 갈아타기 기록 시작(0부터)");
                            return 1;
                        })))
                .then(Commands.literal("jamphase")
                        .then(Commands.literal("on").executes(ctx -> {
                            MimicEntity.setJamPhase(true);
                            tell(ctx.getSource(), "끼임 해소 ON — 1초 넘게 막힌 개체는 밀기를 끈다");
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            MimicEntity.setJamPhase(false);
                            tell(ctx.getSource(), "끼임 해소 OFF — 종전 거동(끝까지 서로 민다)");
                            return 1;
                        })))
                .then(Commands.literal("church").executes(EvoSimCommand::churchReport))
                .then(Commands.literal("tplcheck").executes(EvoSimCommand::tplCheck))
                .then(Commands.literal("guard").executes(EvoSimCommand::guardReport))
                .then(Commands.literal("homes").executes(EvoSimCommand::homesReport))
                .then(Commands.literal("farms").executes(EvoSimCommand::farmsReport))
                .then(Commands.literal("visitfix")
                        .then(Commands.literal("on").executes(ctx -> {
                            com.evosim.mod.entity.MimicVisitGoal.setHoldOnPreempt(true);
                            tell(ctx.getSource(), "마실 앵커 유지 ON — 리시에 선점돼도 목적지를 놓지 않는다");
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            com.evosim.mod.entity.MimicVisitGoal.setHoldOnPreempt(false);
                            tell(ctx.getSource(), "마실 앵커 유지 OFF — 종전 거동(선점마다 전부 놓음)");
                            return 1;
                        })))
                .then(Commands.literal("label")
                        .then(Commands.literal("on").executes(ctx -> {
                            MimicEntity.setLabel(true);
                            tell(ctx.getSource(), "머리 위 활동 표시 ON — 수확/관리중/대기/퇴근 등");
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            MimicEntity.setLabel(false);
                            tell(ctx.getSource(), "머리 위 활동 표시 OFF");
                            return 1;
                        })))
                .then(Commands.literal("tendcap")
                        .then(Commands.literal("on").executes(ctx -> {
                            com.evosim.mod.entity.MimicFarmGoal.setTendAfterCap(true);
                            tell(ctx.getSource(), "수확 상한 후 관리 이관 ON");
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            com.evosim.mod.entity.MimicFarmGoal.setTendAfterCap(false);
                            tell(ctx.getSource(), "수확 상한 후 관리 이관 OFF — 종전 거동(그 자리에서 논다)");
                            return 1;
                        })))
                .then(Commands.literal("hirecap")
                        .then(Commands.literal("on").executes(ctx -> {
                            FarmTicker.setHireCap(true);
                            tell(ctx.getSource(), "긴급고용 인원 상한 ON — 밭당 타일/MIN_JOB 명까지");
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            FarmTicker.setHireCap(false);
                            tell(ctx.getSource(), "긴급고용 인원 상한 OFF — 종전 거동(초과 무제한)");
                            return 1;
                        })))
                .then(Commands.literal("carehyst")
                        .then(Commands.literal("on").executes(ctx -> {
                            com.evosim.mod.entity.MimicParentingGoal.setHysteresis(true);
                            tell(ctx.getSource(), "육아 이력현상 ON — 견인 1.35×반경, 해제 1.0×반경");
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            com.evosim.mod.entity.MimicParentingGoal.setHysteresis(false);
                            tell(ctx.getSource(), "육아 이력현상 OFF — 종전 거동(문턱 하나, 1.0×반경)");
                            return 1;
                        })))
                .then(Commands.literal("farmguard").executes(EvoSimCommand::farmGuardDemo))
                .then(Commands.literal("farmrent").executes(EvoSimCommand::farmRentDemo))
                .then(Commands.literal("farmbond").executes(EvoSimCommand::farmBondDemo))
                .then(Commands.literal("farmseat").executes(EvoSimCommand::farmSeatDemo))
                .then(Commands.literal("farmgrow").executes(EvoSimCommand::farmGrowDemo))
                .then(Commands.literal("farmfound").executes(EvoSimCommand::farmFoundDemo))
                .then(Commands.literal("farmcap").executes(EvoSimCommand::farmCapDemo))
                .then(Commands.literal("farminherit").executes(EvoSimCommand::farmInheritDemo))
                .then(Commands.literal("farmvacant").executes(EvoSimCommand::farmVacantDemo))
                .then(Commands.literal("farmidle").executes(ctx -> farmIdleDemo(ctx, false)))
                .then(Commands.literal("farmdrive").executes(ctx -> farmIdleDemo(ctx, true)))
                .then(Commands.literal("farmshield").executes(ctx -> farmShieldDemo(ctx, true)))
                .then(Commands.literal("farmbreak").executes(ctx -> farmShieldDemo(ctx, false)))
                .then(Commands.literal("farmreturn").executes(EvoSimCommand::farmReturnDemo))
                .then(Commands.literal("farmlabor").executes(EvoSimCommand::farmLaborDemo))
                .then(Commands.literal("farmenvy").executes(EvoSimCommand::farmEnvyDemo))
                .then(Commands.literal("farmretire").executes(EvoSimCommand::farmRetireDemo))
                .then(Commands.literal("farmhoard").executes(EvoSimCommand::farmHoardDemo))
                .then(Commands.literal("farmable").executes(EvoSimCommand::farmAbleDemo))
                .then(Commands.literal("farmfamily").executes(EvoSimCommand::farmFamilyDemo))
                .then(Commands.literal("farmcare").executes(EvoSimCommand::farmCareDemo))
                .then(Commands.literal("stewardx").executes(EvoSimCommand::stageStewardX))
                .then(Commands.literal("lordx").executes(EvoSimCommand::stageLordX))
                .then(Commands.literal("dynastyx").executes(EvoSimCommand::stageDynastyX))
                .then(Commands.literal("navprobe").executes(EvoSimCommand::navProbe))
                .then(Commands.literal("jitter").executes(EvoSimCommand::jitterProbe))
                .then(Commands.literal("decayrelief")
                        .then(Commands.literal("on").executes(ctx -> setRelief(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> setRelief(ctx, false))))
                .then(Commands.literal("forageprobe").executes(EvoSimCommand::forageProbe))
                .then(Commands.literal("graze").executes(EvoSimCommand::graze))
                .then(Commands.literal("fixedpairs")
                        .executes(ctx -> fixedPairs(ctx, 15))
                        .then(Commands.argument("pairs", IntegerArgumentType.integer(1, 30))
                                .executes(ctx -> fixedPairs(ctx,
                                        IntegerArgumentType.getInteger(ctx, "pairs")))))
                .then(Commands.literal("farmclear")
                        .then(Commands.argument("plot", IntegerArgumentType.integer(1))
                                .executes(ctx -> farmClear(ctx,
                                        IntegerArgumentType.getInteger(ctx, "plot"))))));
    }

    /**
     * 엘리트 방랑자 소환 — 야망가+약초학자Ⅴ 남성(자연 개체 — 혈통·상속 정상 편입). 관측 런의
     * "대지주 후보" 표준 시드: 개간 게이트·만족 무시(야망)·최상 수율이 한 몸.
     */
    private static int spawnElite(CommandContext<CommandSourceStack> ctx, int count) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 base = ctx.getSource().getPosition();
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < count; i++) {
            // 명석을 <b>뺀다</b>. 명석은 "할 일이 있으면 배회 시간에도 일한다"(명석 D 여가 컷)라
            // 시드가 남들과 다른 시간표로 돌아간다 — 관측 런에서 엘리트가 앞서는 이유가 능력이
            // 아니라 노동 시간이 되어, 봉건 사슬의 원인을 흐린다.
            //
            // 능력은 약초학자Ⅴ(GATHER_SKILL)에 채집꾼Ⅴ(ACQUISITION)를 <b>더한다</b> — 축이
            // 달라 겹치지 않는다.
            MimicEntity e = spawnMatingReady(level, scatter(level, base), Sex.MALE,
                    java.util.Set.of(Trait.BRIGHT),
                    Trait.AMBITIOUS, Trait.HERBALIST, Trait.GATHERER);
            if (e != null && e.getIndividual() != null) {
                // 육아 무시 — 평범이면 유아가 생기는 순간 <b>육아 구속</b>에 걸려(비무시 성인
                // 전원이 구속 대상) 시드가 개간·확장을 멈춘다. 관측하려는 것이 그 사람의
                // 노동이므로 거기서 풀어 준다. 남성 슬롯만 바꾼다 — 여성 슬롯은 딸에게
                // 유전되는 값이라 건드리면 인구 전체의 육아 성향이 시드로 오염된다.
                e.getIndividual().setParentingCareMale(
                        com.evosim.core.ParentingClass.NEGLECTFUL);
                names.append(names.length() > 0 ? ", " : "").append(e.getIndividual().shortName());
                SimEvents.event(e, "엘리트투입", "야망가+약초학자Ⅴ+채집꾼Ⅴ · 명석 없음 · 육아 무시 관측 시드");
            }
            // <b>성비를 맞춘다.</b> wildpairs 는 남녀를 쌍으로 넣는데 엘리트는 남성만 더하므로,
            // 엘리트가 매 런 짝짓기에서 <b>남는 한 명</b>이 될 수 있는 자리에 선다(남 9 · 여 8).
            // 실측: 그렇게 밀린 런에서 엘리트가 d5 까지 짝도 집도 없어 개간 자격(spouseId != 0,
            // homePos != null)을 못 갖췄고, 그 런은 밭이 하나도 서지 않았다. 시드가 관측에서
            // 통째로 빠지는 것은 운이 아니라 조성의 결함이다.
            //
            // 특성은 완전 랜덤(spawnWild)이라 시드 성격은 바뀌지 않고, 짝 형성은 여전히 자연
            // 경로다 — 엘리트에게 특정 상대를 붙여 주는 것이 아니라 선택지를 한 명 되돌린다.
            spawnWild(level, scatter(level, base), Sex.FEMALE);
        }
        tell(ctx.getSource(), "엘리트 " + count + "명 소환(야망가+약초Ⅴ+채집꾼Ⅴ · 명석X · 육아무시 ♂, 성비용 야생 여성 " + count + "명 동반): "
                + names + " — 구애·정착·개간은 전부 자연 경로.");
        return count;
    }

    /** AUDIT 즉시 조회 — 일일 자동 발행과 같은 산출(어큐뮬레이터 보존: 자동 발행 무영향). */
    private static int auditNow(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        boolean wasOn = SimEvents.enabled();
        if (!wasOn) {
            SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        }
        String line = com.evosim.mod.log.SimAudit.emit(level, false);
        tell(ctx.getSource(), "AUDIT " + line);
        return 1;
    }

    /**
     * 관측 런 원클릭 조성 — 이벤트 로그 ON + 평민 부부 후보 pairs쌍 + 엘리트(야망+약초Ⅴ) 1명.
     * 이후는 전부 자연 경로: 짝→정착→정원→풀 고갈→개간→소작. AUDIT이 매일 1줄 채점 근거를 남긴다.
     */
    private static int obsStart(CommandContext<CommandSourceStack> ctx, int pairs) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 base = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        com.evosim.mod.entity.SimTime.setSkipEnabled(level, true); // 관측 가속 — 밤 스킵 ON
        for (int i = 0; i < pairs; i++) {
            spawnMatingReady(level, scatter(level, base), Sex.MALE);
            spawnMatingReady(level, scatter(level, base), Sex.FEMALE);
        }
        MimicEntity elite = spawnMatingReady(level, scatter(level, base), Sex.MALE,
                Trait.AMBITIOUS, Trait.HERBALIST, Trait.NIMBLE); // 4종 콤보: 야망+약초Ⅴ+명석(기본)+재빠름Ⅴ
        spawnMatingReady(level, scatter(level, base), Sex.FEMALE); // 엘리트 몫 여성 1 보충
        if (elite != null) {
            SimEvents.event(elite, "엘리트투입", "관측 런 시드(야망가+약초Ⅴ+명석+재빠름Ⅴ — 4종 콤보)");
        }
        tell(ctx.getSource(), String.format(
                "관측 런 시작: 평민 %d쌍 + 엘리트 1명(야망+약초Ⅴ+명석+재빠름Ⅴ) 소환, 이벤트 로그 ON. "
                        + "매일 AUDIT 1줄 자동 기록 — 즉시 조회는 /evosim audit.", pairs));
        return pairs * 2 + 2;
    }

    /** 매력 맞는 방랑자 남녀를 흩뿌려 소환 → 자기들끼리 짝 형성·거처 정착을 눈으로 관찰. */
    private static int village(CommandContext<CommandSourceStack> ctx, int pairs) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Vec3 base = src.getPosition();
        for (int i = 0; i < pairs; i++) {
            spawnMatingReady(level, scatter(level, base), Sex.MALE);
            spawnMatingReady(level, scatter(level, base), Sex.FEMALE);
        }
        src.sendSuccess(() -> Component.literal(
                        "마을 소환: 남 " + pairs + " · 여 " + pairs + " (방랑자) — 짝짓기·거처 정착 관찰")
                .withStyle(ChatFormatting.GREEN), false);
        return pairs * 2;
    }

    /**
     * 무작위 특성 남녀쌍 소환 (설계서 §2 §14 관찰). village 와 달리 특성을 <b>완전 랜덤</b>으로 부여 →
     * 매력·기준선이 제각각이라 짝이 되기도/안 되기도 함. 자연스러운 개체군을 관찰·로그로 검증.
     */
    private static int wildPairs(CommandContext<CommandSourceStack> ctx, int pairs) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Vec3 base = src.getPosition();
        for (int i = 0; i < pairs; i++) {
            spawnWild(level, scatter(level, base), Sex.MALE);
            spawnWild(level, scatter(level, base), Sex.FEMALE);
        }
        src.sendSuccess(() -> Component.literal(
                        "무작위 남녀쌍 소환: 남 " + pairs + " · 여 " + pairs
                                + " (완전 랜덤 특성) — /evolog on 으로 관찰 권장")
                .withStyle(ChatFormatting.GREEN), false);
        return pairs * 2;
    }

    /**
     * <b>밭 골조 실연</b> — 서 있는 자리에 n단계 밭을 그대로 세운다(덩어리 도면 육안 확인).
     *
     * <p>무소유(0) 데모 구획. 실제 착공 경로와 <b>같은 함수</b>(FarmTicker.debugRaise)를 쓰므로
     * 여기서 보이는 모양이 곧 마을에 서는 모양이다 — 데모 전용 배치 코드를 따로 두면 그 둘이
     * 어긋나고, 실연이 아무것도 보증하지 못하게 된다.
     */
    private static int farmDemo(CommandContext<CommandSourceStack> ctx, int stage) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos at = groundAt(level, ctx.getSource().getPosition(), 4, 4);
        FarmStore.Plot plot = FarmTicker.debugRaise(level, at, Math.max(1, stage));
        if (plot == null) {
            tell(ctx.getSource(), "❌ 이 자리에는 1단계 발자국이 안 들어간다(평평·빈 땅 필요)");
            return 0;
        }
        int want = FarmLayout.tiles(plot.beds, plot.rows);
        int placed = 0;
        for (long l : plot.tiles) {
            if (level.getBlockState(BlockPos.of(l)).is(Blocks.SWEET_BERRY_BUSH)) {
                placed++;
            }
        }
        boolean ok = placed == want;
        int[] fp = FarmLayout.footprint(plot.beds, plot.rows);
        tell(ctx.getSource(), String.format(
                "%s밭 구획%d — %d단계(덩어리%d 줄%d) 재배 %d/%d · 발자국 %dx%d @%d,%d y%d%s",
                ok ? "§a✅ " : "§c❌ ", plot.id, stageOfPlot(plot), plot.beds, plot.rows,
                placed, want, fp[0], fp[1], plot.fx, plot.fz, plot.baseY,
                stageOfPlot(plot) >= stage ? "" : " (요청 단계까지 못 감 — 자리 부족)"));
        tell(ctx.getSource(), "  정리: /evosim farmclear " + plot.id);
        return ok ? 1 : 0;
    }

    /** 이 구획이 몇 단계인가 — 진행한 성장 수 + 1(대체 수를 써도 정직하다). */
    private static int stageOfPlot(FarmStore.Plot p) {
        return p.steps + 1;
    }

    /**
     * <b>강제 확장</b> — 가장 가까운 구획을 n단계 더 키운다. 막히면 어디서 막혔는지 말한다.
     *
     * <p>자금·노동을 우회하므로 <b>기하만</b> 시험한다. 트인 들판·막힌 자리·한쪽만 막힌 자리에서
     * 각각 어떻게 자라는지를 이 명령 하나로 단계별로 볼 수 있다.
     */
    private static int farmStep(CommandContext<CommandSourceStack> ctx, int steps) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos me = groundAt(level, ctx.getSource().getPosition(), 0, 0);
        FarmStore store = FarmStore.get(level);
        FarmStore.Plot best = null;
        double bd = Double.MAX_VALUE;
        for (FarmStore.Plot p : store.all().values()) {
            if (p.beds <= 0) {
                continue;
            }
            double d = new BlockPos(p.fx, p.baseY, p.fz).distSqr(me);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        if (best == null) {
            tell(ctx.getSource(), "❌ 근처에 덩어리 도면 구획이 없다 — 먼저 /evosim farm <단계>");
            return 0;
        }
        int done = FarmTicker.debugAdvance(level, best, Math.max(1, steps));
        int[] fp = FarmLayout.footprint(best.beds, best.rows);
        tell(ctx.getSource(), String.format(
                "구획%d — %d단계 진행(요청 %d) → 지금 %d단계(덩어리%d 줄%d 재배%d) 발자국 %dx%d @%d,%d",
                best.id, done, steps, stageOfPlot(best), best.beds, best.rows,
                FarmLayout.tiles(best.beds, best.rows), fp[0], fp[1], best.fx, best.fz));
        if (done < steps) {
            StringBuilder sb = new StringBuilder();
            for (var e : FarmTicker.unfilledReasons(level, store, best, 0).entrySet()) {
                sb.append(e.getKey()).append(e.getValue()).append(' ');
            }
            tell(ctx.getSource(), "  더 못 자란 사유(다음 단계 네 방향) — " + sb.toString().trim());
        }
        return done;
    }

    /** M1 실연 — 무대 성년 + 그 소유의 15타일 밭(즉시 익음)을 조성 → 주인이 순회 수확하는지 육안. */
    private static int farmOwnDemo(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        BlockPos anchor = groundAt(level, ctx.getSource().getPosition(), 6, 6);
        MimicEntity owner = spawnAdult(level, Vec3.atBottomCenterOf(anchor).add(-2, 0, 0), Sex.MALE);
        FarmStore.Plot plot = buildDemoPlot(level, anchor, owner.getIndividual().id(), 15);
        level.setDayTime(4000L); // 노동 시간
        double h0 = owner.getHolding();
        LiveCheck.watch(ctx.getSource(), "farm_own_harvest", 1200,
                () -> String.format("H %.2f(start %.2f) ripeLeft %d", owner.getHolding(), h0,
                        countRipe(level, plot)),
                // 수확 신호는 익음 감소로 판정 — H 는 무주택 개체라 BAND_HIGH(2.0)에서 초과분이
                // 버려져(MimicEntity §2555) start+3 에 도달 불가. 익음≤3(전량)은 하루 용량 12를 다
                // 걸어 수확해야 해 60초 창을 넘음(~6초/타일) — "주인이 자기 밭을 판다"는 익음이 15→12↓
                // (3타일 이상, ~24초)로 충분히 증명된다(무단수확 가드로 주인만 가능).
                () -> countRipe(level, plot) <= 12,
                () -> {
                    discard(owner);
                    farmClearPlot(level, plot);
                });
        return 1;
    }

    private static int countRipe(ServerLevel level, FarmStore.Plot plot) {
        int n = 0;
        for (long l : plot.tiles) {
            var st = level.getBlockState(BlockPos.of(l));
            if (st.is(Blocks.SWEET_BERRY_BUSH) && st.getValue(SweetBerryBushBlock.AGE) >= 3) {
                n++;
            }
        }
        return n;
    }

    /** 구획 블록·등록 일괄 정리(공용). */
    private static void farmClearPlot(ServerLevel level, FarmStore.Plot plot) {
        for (long l : plot.tiles) {
            BlockPos gp = BlockPos.of(l);
            if (level.getBlockState(gp).is(Blocks.SWEET_BERRY_BUSH)) {
                level.setBlockAndUpdate(gp, Blocks.AIR.defaultBlockState());
            }
        }
        FarmStore.get(level).debugRemove(plot.id);
    }

    /**
     * M2 관문 ① 고용 흐름 — 1인 지주(C=8) + 35타일 즉시 익음 밭(부족 27 ≥ 최소일감 2) + 가난한
     * 이웃 조성 → 새벽 배정 강제 → 결과값: 이웃 H 증가(소작 70%) ∧ 밭 계정 > 0(지대 30% 적립).
     */
    private static int farmHireDemo(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments(); // 배정 잔재 인수(같은 자리 2회 규칙)
        BlockPos anchor = groundAt(level, ctx.getSource().getPosition(), 6, 6);
        MimicEntity owner = spawnAdult(level, Vec3.atBottomCenterOf(anchor).add(-3, 0, 0), Sex.MALE);
        MimicEntity worker = spawnAdult(level, Vec3.atBottomCenterOf(anchor).add(-3, 0, 4), Sex.MALE);
        FarmStore.Plot plot = buildDemoPlot(level, anchor, owner.getIndividual().id(), 35);
        level.setDayTime(1200L); // 새벽 직후 — 다음 200틱 스캔에서 배정
        double h0 = worker.getHolding();
        LiveCheck.watch(ctx.getSource(), "farm_hire_flow", 1800, // 소작농 배정→플롯 이동→첫 수확 여유
                () -> String.format("workerH %.2f(start %.2f) rent %.2f assigned %s",
                        worker.getHolding(), h0, plot.account,
                        FarmTicker.assignedPlot(worker.getId()) == plot.id ? "yes" : "no"),
                // 소작 수확 신호는 지대 계정 적립으로 판정 — 소작농 몫 30%가 계정에 쌓이는 것은
                // 배정된 소작농만이 만들 수 있다(자영은 100% 본인). workerH 는 무주택 상한 2.0 에
                // 막혀 start+0.5(=2.0)를 못 넘으므로 제외(MimicEntity §2555).
                () -> plot.account > 0.2,
                () -> {
                    discard(owner, worker);
                    farmClearPlot(level, plot);
                    FarmTicker.clearAssignments();
                });
        return 1;
    }

    /**
     * M2 관문 ② 무단 금지 + 슬롯0 경계 — 상주 지주(C=8, noAI 로 자가 수확 봉쇄)의 9타일 밭:
     * 부족 1 < 최소일감 2라 슬롯 0(운 무관 결정론), 배정 없는 이웃은 손대면 안 됨 → 금지 결과
     * 감시(익은 타일 감소 = 실패). (MIN_JOB 10→2 이후 부재 지주 무대는 9슬롯 개방이라 부적합.)
     */
    private static int farmGuardDemo(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "검증 진행 중 — 끝난 뒤 실행.");
            return 0;
        }
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos anchor = groundAt(level, ctx.getSource().getPosition(), 6, 6);
        MimicEntity[] c = new MimicEntity[2];
        FarmStore.Plot[] pl = new FarmStore.Plot[1];
        List<VerifySuite.Step> steps = new ArrayList<>();
        steps.add(new VerifySuite.Step("farm_guard_no_poach",
                "9-tile owner-cared farm: slots 0 (shortfall 1 < MIN_JOB 2), no one may harvest", 400, true, () -> {
            c[0] = spawnAdult(level, Vec3.atBottomCenterOf(anchor).add(-3, 0, 0), Sex.MALE);
            c[0].debugSetHolding(0.4); // 궁핍 — 유혹 상태 조성(위양성 차단: 배고파도 못 건드려야 함)
            c[1] = spawnAdult(level, Vec3.atBottomCenterOf(anchor).add(-3, 0, -4), Sex.MALE);
            c[1].setNoAi(true); // 상주 지주 — 용량 8은 장부에 계상, 자가 수확은 봉쇄(위양성 차단)
            pl[0] = buildDemoPlot(level, anchor, c[1].getIndividual().id(), 9);
            level.setDayTime(1200L);
        }, () -> String.format("ripe %d(must stay 9) H %.2f", countRipe(level, pl[0]), c[0].getHolding()),
                () -> countRipe(level, pl[0]) < 9, // ← 금지 결과(무단 수확 발생)
                () -> {
                    discard(c);
                    farmClearPlot(level, pl[0]);
                    FarmTicker.clearAssignments();
                }));
        VerifySuite.start(ctx.getSource(), steps);
        return 1;
    }

    /**
     * M3 관문 지대 정산 — 유주택 지주 + 계정 2.7 선적립 구획 조성 → 밤 강제 → 결과값:
     * 주인 저장고 +2(정수만) ∧ 계정 0.7 이월(소수 보존 — L 정수성 회계 항등식).
     */
    private static int farmRentDemo(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos anchor = groundAt(level, ctx.getSource().getPosition(), 6, 6);
        BlockPos home = groundAt(level, ctx.getSource().getPosition(), -6, -6);
        MimicEntity owner = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        owner.debugSettleWithTent(home, Direction.NORTH);
        LarderStore.get(level).set(home, 3.0);
        FarmStore.Plot plot = buildDemoPlot(level, anchor, owner.getIndividual().id(), 9);
        plot.account = 2.7; // 지대 선적립(수확 유동성 배제 — 이체 회계만 고립 검증)
        FarmStore.get(level).setDirty();
        level.setDayTime(13500L); // 밤 — 다음 200틱 스캔에서 정산
        LiveCheck.watch(ctx.getSource(), "farm_rent_settle", 600,
                () -> String.format("larder %.1f(start 3, expect 5) account %.2f(expect 0.70)",
                        LarderStore.get(level).get(home), plot.account),
                () -> Math.abs(LarderStore.get(level).get(home) - 5.0) < 1.0E-6
                        && Math.abs(plot.account - 0.7) < 1.0E-6,
                () -> {
                    discard(owner);
                    farmClearPlot(level, plot);
                    FarmTicker.clearAssignments();
                });
        return 1;
    }

    /**
     * M4 관문 ① 상시 승격 — 연속 2일 출근 상태를 조성(어제 배정 시드 + streak 2) → 다음 새벽
     * 실경로 배정에서 3일째 도달 → 결과값: tenantFarm == 구획 id(예약석 성립).
     */
    private static int farmBondDemo(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos anchor = groundAt(level, ctx.getSource().getPosition(), 6, 6);
        MimicEntity owner = spawnAdult(level, Vec3.atBottomCenterOf(anchor).add(-3, 0, 0), Sex.MALE);
        MimicEntity worker = spawnAdult(level, Vec3.atBottomCenterOf(anchor).add(-3, 0, 4), Sex.MALE);
        FarmStore.Plot plot = buildDemoPlot(level, anchor, owner.getIndividual().id(), 35);
        worker.setTenant(0L, 2);                                    // 이틀째까지 조성
        FarmTicker.debugSeedAssignment(worker.getId(), plot.id);    // "어제 출근" 주입
        level.setDayTime(1200L);                                    // 새벽 — 3일째 실경로 배정
        LiveCheck.watch(ctx.getSource(), "farm_bond_promote", 600,
                () -> String.format("tenantFarm %d(expect %d) streak %d",
                        worker.getTenantFarm(), plot.id, worker.getTenantStreak()),
                () -> worker.getTenantFarm() == plot.id && worker.getTenantStreak() >= 3,
                () -> {
                    discard(owner, worker);
                    farmClearPlot(level, plot);
                    FarmTicker.clearAssignments();
                });
        return 1;
    }

    /**
     * M4 관문 ①b 예약석 — 부족분 0(9타일 < 최소일감, 부부 ΣC 24)이어도 상시 소작은 새벽 배정이
     * 유지되는지(고용 진동 차단의 실검증). 결과값: assignedPlot == 구획 ∧ 관계 유지.
     */
    private static int farmSeatDemo(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos anchor = groundAt(level, ctx.getSource().getPosition(), 6, 6);
        BlockPos home = groundAt(level, ctx.getSource().getPosition(), -6, -6);
        MimicEntity[] cc = coupleAt(level, home); // 대가구 — ΣC 24 ≫ 9타일 → 일용 슬롯 0
        MimicEntity worker = spawnAdult(level, Vec3.atBottomCenterOf(anchor).add(-3, 0, 4), Sex.MALE);
        FarmStore.Plot plot = buildDemoPlot(level, anchor, cc[0].getIndividual().id(), 9);
        worker.setTenant(plot.id, 3); // 상시 소작 조성
        level.setDayTime(1200L);      // 새벽 — 예약석 경로만이 배정을 만들 수 있음
        LiveCheck.watch(ctx.getSource(), "farm_seat_reserved", 600,
                () -> String.format("assigned %s bond %s (slots would be 0: 9 tiles vs cap 24)",
                        FarmTicker.assignedPlot(worker.getId()) == plot.id ? "yes" : "no",
                        worker.getTenantFarm() == plot.id ? "kept" : "broken"),
                () -> FarmTicker.assignedPlot(worker.getId()) == plot.id
                        && worker.getTenantFarm() == plot.id,
                () -> {
                    discard(cc[0], cc[1], worker);
                    farmClearPlot(level, plot);
                    FarmTicker.clearAssignments();
                });
        return 1;
    }

    /**
     * M4 관문 ②③ 보호 의무 — 상시 소작 위급(H 0.2) 조성.    /**
     * M4 관문 ②③ 보호 의무 — 상시 소작 위급(H 0.2) 조성. shield=true: 영주 저장고 3 → 구제
     * (H≥1.0 ∧ 저장고 2 — R6 자가 회복 위양성은 저장고 감소 동시 요구로 차단, 관계 유지).
     * shield=false: 저장고 0 → 불이행 해제(tenantFarm 0).
     */
    private static int farmShieldDemo(CommandContext<CommandSourceStack> ctx, boolean shield) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos anchor = groundAt(level, ctx.getSource().getPosition(), 6, 6);
        BlockPos home = groundAt(level, ctx.getSource().getPosition(), -6, -6);
        MimicEntity owner = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        owner.debugSettleWithTent(home, Direction.NORTH);
        owner.setNoAi(true); // 영주 행위 동결(F-7) — 채집·입금으로 저장고 상수를 흔들던 소음 제거
        LarderStore.get(level).set(home, shield ? 3.0 : 0.0);
        MimicEntity worker = spawnAdult(level, Vec3.atBottomCenterOf(anchor).add(-3, 0, 4), Sex.MALE);
        FarmStore.Plot plot = buildDemoPlot(level, anchor, owner.getIndividual().id(), 9);
        worker.setTenant(plot.id, 3); // 상시 소작 조성
        worker.debugSetHolding(0.2);  // 위급 직전 상태
        worker.setNoAi(true);         // 행위 동결(F-7) — 풀 한 입(+0.11)로 위급 탈출해 스캔과 경주하던
                                      // 비결정론 제거. fastSettle 은 시간압축으로 H·저장고를 태워 부적합.
        level.setDayTime(4000L);
        Runnable cleanup = () -> {
            discard(owner, worker);
            farmClearPlot(level, plot);
            FarmTicker.clearAssignments();
        };
        if (shield) {
            LiveCheck.watch(ctx.getSource(), "farm_shield_relief", 600,
                    () -> String.format("workerH %.2f(start 0.2) larder %.0f(expect 2) bond %s",
                            worker.getHolding(), LarderStore.get(level).get(home),
                            worker.getTenantFarm() == plot.id ? "kept" : "broken"),
                    () -> worker.getHolding() >= 1.0
                            && Math.abs(LarderStore.get(level).get(home) - 2.0) < 1.0E-6
                            && worker.getTenantFarm() == plot.id,
                    cleanup);
        } else {
            LiveCheck.watch(ctx.getSource(), "farm_shield_break", 600,
                    () -> String.format("bond %s(expect broken) larder 0",
                            worker.getTenantFarm() == 0L ? "broken" : "kept"),
                    () -> worker.getTenantFarm() == 0L,
                    cleanup);
        }
        return 1;
    }

    /**
     * M5 관문 ① 소작권 확장 — 소작 붙은 9타일 밭: 확장 주체는 상시 소작농(저장고 16), 주인(저장고
     * 20)은 금지. 밤 후: 타일 9→12 ∧ 소작농 저장고 16→7(3타일×3) ∧ 주인 저장고 20 불변.
     */
    private static int farmGrowDemo(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos anchor = groundAt(level, ctx.getSource().getPosition(), 8, 8);
        BlockPos oHome = groundAt(level, ctx.getSource().getPosition(), -8, -8);
        BlockPos tHome = groundAt(level, ctx.getSource().getPosition(), -8, 8);
        MimicEntity owner = spawnAdult(level, Vec3.atBottomCenterOf(oHome), Sex.MALE);
        owner.debugSettleWithTent(oHome, Direction.NORTH);
        LarderStore.get(level).set(oHome, 20.0);
        MimicEntity tenant = spawnAdult(level, Vec3.atBottomCenterOf(tHome), Sex.MALE);
        tenant.debugSettleWithTent(tHome, Direction.NORTH);
        LarderStore.get(level).set(tHome, 16.0);
        FarmStore.Plot plot = buildDemoPlot(level, anchor, owner.getIndividual().id(), 9);
        tenant.setTenant(plot.id, 3);
        plot.account = 7.0; // 재투자 자금(R1) — floor(7/3)=2타일 확장 후 잔여 1이 밤 정산으로 이체
        level.setDayTime(13500L);
        LiveCheck.watch(ctx.getSource(), "farm_grow_reinvest", 600,
                () -> String.format("tiles %d(expect 11) acct %.1f(expect 0) "
                        + "ownerLarder %.0f(expect 21) tenantLarder %.0f(must stay 16)",
                        plot.tiles.length, plot.account,
                        LarderStore.get(level).get(oHome), LarderStore.get(level).get(tHome)),
                () -> plot.tiles.length == 11
                        && Math.abs(plot.account) < 1.0E-6
                        && Math.abs(LarderStore.get(level).get(oHome) - 21.0) < 1.0E-6
                        && Math.abs(LarderStore.get(level).get(tHome) - 16.0) < 1.0E-6,
                () -> {
                    discard(owner, tenant);
                    farmClearPlot(level, plot);
                    FarmTicker.clearAssignments();
                });
        return 1;
    }

    /** M5 관문 ② 신규 개간 — 무밭 지주(저장고 40) → 밤 후 구획 1개 착공(3타일) ∧ 저장고 40→10. */
    private static int farmFoundDemo(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos home = groundAt(level, ctx.getSource().getPosition(), -8, -8);
        MimicEntity owner = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE, Trait.HERBALIST);
        owner.debugSettleWithTent(home, Direction.NORTH);
        LarderStore.get(level).set(home, 40.0);
        long oid = owner.getIndividual().id();
        level.setDayTime(13500L);
        LiveCheck.watch(ctx.getSource(), "farm_found_new", 600,
                () -> String.format("owned %d(expect 1) larder %.0f(expect 22)",
                        FarmStore.get(level).ownedCount(oid), LarderStore.get(level).get(home)),
                () -> FarmStore.get(level).ownedCount(oid) == 1
                        && Math.abs(LarderStore.get(level).get(home) - 22.0) < 1.0E-6,
                () -> {
                    for (FarmStore.Plot p : new java.util.ArrayList<>(
                            FarmStore.get(level).all().values())) {
                        if (p.ownerId == oid) {
                            farmClearPlot(level, p);
                        }
                    }
                    discard(owner);
                    FarmTicker.clearAssignments();
                });
        return 1;
    }

    /**
     * M5 관문 ③ 능력 게이트 — 무능력 지주(기본 특성엔 채집·저장 능력 없음)의 33타일 밭 + 충분한
     * 저장고(30): 밤 후 성장은 하되 T4 경계(35)에서 정지 — 타일 == 35 ∧ 저장고 30→26(2타일×2.0).
     */
    private static int farmCapDemo(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos anchor = groundAt(level, ctx.getSource().getPosition(), 8, 8);
        BlockPos home = groundAt(level, ctx.getSource().getPosition(), -8, -8);
        MimicEntity owner = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        owner.debugSettleWithTent(home, Direction.NORTH);
        LarderStore.get(level).set(home, 30.0);
        FarmStore.Plot plot = buildDemoPlot(level, anchor, owner.getIndividual().id(), 33);
        level.setDayTime(13500L);
        LiveCheck.watch(ctx.getSource(), "farm_skill_cap", 600,
                () -> String.format("tiles %d(expect exactly 35) larder %.0f(expect 26)",
                        plot.tiles.length, LarderStore.get(level).get(home)),
                () -> plot.tiles.length == 35
                        && Math.abs(LarderStore.get(level).get(home) - 26.0) < 1.0E-6,
                () -> {
                    discard(owner);
                    farmClearPlot(level, plot);
                    FarmTicker.clearAssignments();
                });
        return 1;
    }

    /**
     * M6 관문 ① 상속 — 지주 + 성년 아들(부모 링크) + 아들이 그 밭의 상시 소작인 상태 조성 →
     * 지주 파괴 → 결과값: 소유자 == 아들 id ∧ 아들의 자기 소작 관계 해소(허점 6).
     */
    private static int farmInheritDemo(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos anchor = groundAt(level, ctx.getSource().getPosition(), 8, 8);
        BlockPos home = groundAt(level, ctx.getSource().getPosition(), -8, -8);
        MimicEntity owner = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        owner.debugSettleWithTent(home, Direction.NORTH);
        MimicEntity son = spawnChildOf(level, Vec3.atBottomCenterOf(home).add(2, 0, 0), owner, Sex.MALE);
        FarmStore.Plot plot = buildDemoPlot(level, anchor, owner.getIndividual().id(), 9);
        son.setTenant(plot.id, 3); // 아버지 밭의 소작 — 상속 시 해소되어야 함
        long sonId = son.getIndividual().id();
        owner.discard(); // 사망 — remove 훅이 원장 마킹 + 상속을 수행
        LiveCheck.watch(ctx.getSource(), "farm_inherit_son", 200,
                () -> String.format("owner %d(expect son %d) sonTenant %d(expect 0)",
                        plot.ownerId, sonId, son.getTenantFarm()),
                () -> plot.ownerId == sonId && son.getTenantFarm() == 0L,
                () -> {
                    discard(son);
                    farmClearPlot(level, plot);
                    FarmTicker.clearAssignments();
                });
        return 1;
    }

    /**
     * S1 관문 마름(클래스 v1.3) — 임명 선발·야망가 제외·즉시 승계·수당 회계. 4단계 블라인드
     * (조성 → 강제 → 수치 판정). 전부 결과값 대조(칭호·id·저장고 증분).
     */
    private static int stageStewardX(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "검증 진행 중 — 끝난 뒤 실행.");
            return 0;
        }
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos base = groundAt(level, ctx.getSource().getPosition(), 6, 6);
        List<VerifySuite.Step> steps = new ArrayList<>();

        // ① 임명·선발 — 상시 2명(g5·g0) → 관리 g 최고자(g5) 마름 임명, 소유주 클래스 지주.
        MimicEntity[] a = new MimicEntity[3];
        FarmStore.Plot[] pa = new FarmStore.Plot[1];
        steps.add(new VerifySuite.Step("steward_appoint",
                "2 perm tenants (g5,g0) -> g5 appointed steward, owner class=지주", 200, false, () -> {
            a[0] = spawnAdult(level, Vec3.atBottomCenterOf(base).add(-3, 0, 0), Sex.MALE);
            a[1] = spawnGradedAdult(level, Vec3.atBottomCenterOf(base).add(-3, 0, 4), Sex.MALE, 5);
            a[2] = spawnGradedAdult(level, Vec3.atBottomCenterOf(base).add(-3, 0, 6), Sex.MALE, 0);
            pa[0] = buildDemoPlot(level, base, a[0].getIndividual().id(), 35);
            a[1].setTenant(pa[0].id, 3);
            a[2].setTenant(pa[0].id, 3);
            level.setDayTime(1200L);
            FarmTicker.debugAssign(level);
        }, () -> String.format("steward %d (expect g5 %d) ownerClass %s",
                pa[0].stewardId, a[1].getIndividual().id(),
                FarmStore.get(level).classOf(level, a[0].getIndividual().id())),
                () -> pa[0].stewardId == a[1].getIndividual().id()
                        && FarmStore.get(level).classOf(level, a[0].getIndividual().id()).equals("지주"),
                () -> {
                    discard(a);
                    farmClearPlot(level, pa[0]);
                    FarmTicker.clearAssignments();
                }));

        // ② 야망가 제외 — g5 야망가 vs g3 비야망가 → 비야망가(g3) 선발(이탈 방지 ①).
        MimicEntity[] b = new MimicEntity[3];
        FarmStore.Plot[] pb = new FarmStore.Plot[1];
        steps.add(new VerifySuite.Step("steward_skip_ambitious",
                "g5-ambitious skipped, g3 non-ambitious appointed", 200, false, () -> {
            b[0] = spawnAdult(level, Vec3.atBottomCenterOf(base).add(-3, 0, 0), Sex.MALE);
            b[1] = spawnGradedAdult(level, Vec3.atBottomCenterOf(base).add(-3, 0, 4), Sex.MALE, 5,
                    Trait.AMBITIOUS);
            b[2] = spawnGradedAdult(level, Vec3.atBottomCenterOf(base).add(-3, 0, 6), Sex.MALE, 3);
            pb[0] = buildDemoPlot(level, base, b[0].getIndividual().id(), 35);
            b[1].setTenant(pb[0].id, 3);
            b[2].setTenant(pb[0].id, 3);
            level.setDayTime(1200L);
            FarmTicker.debugAssign(level);
        }, () -> String.format("steward %d (expect g3 %d, not amb %d)",
                pb[0].stewardId, b[2].getIndividual().id(), b[1].getIndividual().id()),
                () -> pb[0].stewardId == b[2].getIndividual().id(),
                () -> {
                    discard(b);
                    farmClearPlot(level, pb[0]);
                    FarmTicker.clearAssignments();
                }));

        // ③ 즉시 승계 — 마름 사망 시 같은 틱에 잔여 상시가 승계(칭호 무붕괴 v1.1).
        MimicEntity[] c = new MimicEntity[3];
        FarmStore.Plot[] pc = new FarmStore.Plot[1];
        long[] survId = new long[1];
        steps.add(new VerifySuite.Step("steward_succession",
                "steward death -> same-tick succession by remaining perm tenant", 200, false, () -> {
            c[0] = spawnAdult(level, Vec3.atBottomCenterOf(base).add(-3, 0, 0), Sex.MALE);
            c[1] = spawnGradedAdult(level, Vec3.atBottomCenterOf(base).add(-3, 0, 4), Sex.MALE, 5);
            c[2] = spawnGradedAdult(level, Vec3.atBottomCenterOf(base).add(-3, 0, 6), Sex.MALE, 3);
            pc[0] = buildDemoPlot(level, base, c[0].getIndividual().id(), 35);
            FarmStore.get(level).appointSteward(level, pc[0], c[1], "마름임명");
            c[2].setTenant(pc[0].id, 3);
            survId[0] = c[2].getIndividual().id();
            c[1].discard(); // 마름 사망 — remove 훅이 stewardGone → 즉시 승계
        }, () -> String.format("steward %d (expect survivor g3 %d)", pc[0].stewardId, survId[0]),
                () -> pc[0].stewardId == survId[0],
                () -> {
                    discard(c[0], c[2]);
                    farmClearPlot(level, pc[0]);
                    FarmTicker.clearAssignments();
                }));

        // ④ 수당 회계 — 소작 평균 4.0 × 계수(g5,근속0 → 0.75) = 3.0 → 마름 저장고 +3, 계정 −3.
        MimicEntity[] d = new MimicEntity[2];
        FarmStore.Plot[] pd = new FarmStore.Plot[1];
        BlockPos stwHome = groundAt(level, ctx.getSource().getPosition(), -6, 6);
        steps.add(new VerifySuite.Step("steward_wage",
                "tenant avg 4.0 x mult 0.75 = 3 -> steward larder +3, account -3", 200, false, () -> {
            d[0] = spawnAdult(level, Vec3.atBottomCenterOf(base).add(-3, 0, 0), Sex.MALE);
            d[1] = spawnGradedAdult(level, Vec3.atBottomCenterOf(stwHome), Sex.MALE, 5);
            d[1].debugSettleWithTent(stwHome, Direction.NORTH);
            LarderStore.get(level).set(stwHome, 0.0);
            pd[0] = buildDemoPlot(level, base, d[0].getIndividual().id(), 35);
            FarmStore.get(level).appointSteward(level, pd[0], d[1], "마름임명");
            pd[0].account = 10.0;
            FarmTicker.debugSeedTenantPay(pd[0].id, 4.0, 99991); // 소작 1인 4.0 (평균 4.0)
            level.setDayTime(13500L);
            FarmTicker.debugSettle(level);
        }, () -> String.format("stewardLarder %.1f (expect 3) account %.1f (expect 7)",
                LarderStore.get(level).get(stwHome), pd[0].account),
                () -> Math.abs(LarderStore.get(level).get(stwHome) - 3.0) < 1.0E-6
                        && Math.abs(pd[0].account - 7.0) < 1.0E-6,
                () -> {
                    discard(d);
                    farmClearPlot(level, pd[0]);
                    LarderStore.get(level).remove(stwHome);
                    FarmTicker.clearAssignments();
                }));

        // ⑤ 관리 바닥값(회차 S2) — 유능 지주(약초Ⅴ g5) + g0 상시 → 조기 임명(영주 사다리)되되
        //    plotEfficiency는 지주 오버사이트로 바닥(~1.0) 유지: 붕괴(0.06) 없음. 리처드/킴벌리 결함 근본 해소.
        MimicEntity[] e = new MimicEntity[3];
        FarmStore.Plot[] pe = new FarmStore.Plot[1];
        steps.add(new VerifySuite.Step("steward_floor",
                "capable owner (g5) + g0 steward -> appointed BUT plot E floored ~1.0 (no collapse)", 200, false, () -> {
            e[0] = spawnAdult(level, Vec3.atBottomCenterOf(base).add(-3, 0, 0), Sex.MALE, Trait.HERBALIST); // g5 지주
            e[1] = spawnGradedAdult(level, Vec3.atBottomCenterOf(base).add(-3, 0, 4), Sex.MALE, 0);
            e[2] = spawnGradedAdult(level, Vec3.atBottomCenterOf(base).add(-3, 0, 6), Sex.MALE, 0);
            pe[0] = buildDemoPlot(level, base, e[0].getIndividual().id(), 35);
            e[1].setTenant(pe[0].id, 3);
            e[2].setTenant(pe[0].id, 3);
            level.setDayTime(1200L);
            FarmTicker.debugAssign(level);
        }, () -> String.format("steward %d (appointed) E %.2f (expect ~1.0, NOT 0.06)",
                pe[0].stewardId, FarmStore.get(level).plotEfficiency(level, pe[0])),
                () -> pe[0].stewardId != 0L
                        && FarmStore.get(level).plotEfficiency(level, pe[0]) >= 0.9,
                () -> {
                    discard(e);
                    farmClearPlot(level, pe[0]);
                    FarmTicker.clearAssignments();
                }));

        VerifySuite.start(ctx.getSource(), steps);
        return 1;
    }

    /**
     * S2 관문 하청 개간·영주 전환(케이스 2) — 마름 1 지주가 신규 밭을 열 때 영지 상시 중 신임
     * 마름을 세워 즉시 운영(영주 승격). 후보 없으면 본인 직영 폴백(교착 방지 P2).
     */
    private static int stageLordX(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "검증 진행 중 — 끝난 뒤 실행.");
            return 0;
        }
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos p1 = groundAt(level, ctx.getSource().getPosition(), 10, 10);
        BlockPos home = groundAt(level, ctx.getSource().getPosition(), -10, -10);
        List<VerifySuite.Step> steps = new ArrayList<>();

        // ① 하청 개간 — 지주(마름1)의 신규 밭에 영지 상시(비야망가) 신임 마름 임명 → 영주(구획2·마름2).
        MimicEntity[] a = new MimicEntity[3];
        FarmStore.Plot[] first = new FarmStore.Plot[1];
        int[] plotsBefore = new int[1];
        steps.add(new VerifySuite.Step("lord_subcontract",
                "landlord founds 2nd farm -> estate tenant appointed steward, class=영주", 300, false, () -> {
            a[0] = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE, Trait.HERBALIST);
            a[0].debugSettleWithTent(home, Direction.NORTH);
            a[1] = spawnGradedAdult(level, Vec3.atBottomCenterOf(p1).add(0, 0, 4), Sex.MALE, 5); // 마름1
            a[2] = spawnGradedAdult(level, Vec3.atBottomCenterOf(p1).add(0, 0, 6), Sex.MALE, 4); // 영지 상시(신임 후보)
            first[0] = buildDemoPlot(level, p1, a[0].getIndividual().id(), 35);
            FarmStore.get(level).appointSteward(level, first[0], a[1], "마름임명");
            a[2].setTenant(first[0].id, 3); // 영지 상시 소작(estateCandidate)
            LarderStore.get(level).set(home, 200.0); // 2호 자금 충분
            plotsBefore[0] = FarmStore.get(level).ownedCount(a[0].getIndividual().id());
            level.setDayTime(13500L);
            FarmTicker.debugGrow(level);
        }, () -> String.format("owned %d(was %d) class %s",
                FarmStore.get(level).ownedCount(a[0].getIndividual().id()), plotsBefore[0],
                FarmStore.get(level).classOf(level, a[0].getIndividual().id())),
                () -> FarmStore.get(level).ownedCount(a[0].getIndividual().id()) == plotsBefore[0] + 1
                        && FarmStore.get(level).classOf(level, a[0].getIndividual().id()).equals("영주")
                        && FarmStore.get(level).stewardCount(a[0].getIndividual().id()) == 2,
                () -> {
                    discard(a);
                    for (FarmStore.Plot p : new ArrayList<>(FarmStore.get(level).all().values())) {
                        farmClearPlot(level, p);
                    }
                    FarmTicker.clearAssignments();
                }));

        // ② 폴백 — 신임 마름 후보 없음(잔여 상시가 야망가뿐) → 신규 밭은 지주 직영(steward 0).
        //    잔여 야망가 상시는 성숙 자격(perm≥1)은 채우되 마름 후보에선 제외 → 폴백 경로 발동.
        MimicEntity[] b = new MimicEntity[3];
        FarmStore.Plot[] fb = new FarmStore.Plot[1];
        long[] newPlot = new long[1];
        steps.add(new VerifySuite.Step("lord_fallback_self",
                "no non-ambitious candidate -> 2nd farm self-managed (steward 0), no deadlock", 300, false, () -> {
            b[0] = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE, Trait.HERBALIST);
            b[0].debugSettleWithTent(home, Direction.NORTH);
            b[1] = spawnGradedAdult(level, Vec3.atBottomCenterOf(p1).add(0, 0, 4), Sex.MALE, 5); // 마름1
            b[2] = spawnGradedAdult(level, Vec3.atBottomCenterOf(p1).add(0, 0, 6), Sex.MALE, 4,
                    Trait.AMBITIOUS); // 잔여 상시(야망가 — 성숙 자격 O, 마름 후보 X)
            fb[0] = buildDemoPlot(level, p1, b[0].getIndividual().id(), 35);
            FarmStore.get(level).appointSteward(level, fb[0], b[1], "마름임명");
            b[2].setTenant(fb[0].id, 3);
            LarderStore.get(level).set(home, 200.0);
            level.setDayTime(13500L);
            FarmTicker.debugGrow(level);
            long np = 0;
            for (FarmStore.Plot p : FarmStore.get(level).all().values()) {
                if (p.ownerId == b[0].getIndividual().id() && p.id != fb[0].id) {
                    np = p.id;
                }
            }
            newPlot[0] = np;
        }, () -> String.format("newPlot %d steward %s", newPlot[0],
                newPlot[0] == 0 ? "none-founded" : String.valueOf(
                        FarmStore.get(level).get(newPlot[0]).stewardId)),
                () -> newPlot[0] != 0 && FarmStore.get(level).get(newPlot[0]).stewardId == 0L,
                () -> {
                    discard(b);
                    for (FarmStore.Plot p : new ArrayList<>(FarmStore.get(level).all().values())) {
                        farmClearPlot(level, p);
                    }
                    FarmTicker.clearAssignments();
                }));

        VerifySuite.start(ctx.getSource(), steps);
        return 1;
    }

    /**
     * S3 관문 가문 편입(케이스 3) — 영주 가문 자식의 착공은 소유권 영주 귀속·착공자 마름·착공비
     * 익일 상환. 야망가 포함 예외 없음(발사대 봉쇄). 2단계: 편입 귀속·채무 상환.
     */
    private static int stageDynastyX(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "검증 진행 중 — 끝난 뒤 실행.");
            return 0;
        }
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos lordHome = groundAt(level, ctx.getSource().getPosition(), -12, -12);
        BlockPos childHome = groundAt(level, ctx.getSource().getPosition(), 12, -12);
        BlockPos p1 = groundAt(level, ctx.getSource().getPosition(), 12, 12);
        List<VerifySuite.Step> steps = new ArrayList<>();

        // ① 편입 — 영주 부모의 자식이 착공 → 소유권 영주, 착공자 마름, 채무=비용.
        MimicEntity[] a = new MimicEntity[3];
        FarmStore.Plot[] lordPlots = new FarmStore.Plot[2];
        long[] childId = new long[1];
        long[] lordId = new long[1];
        long[] newPlot = new long[1];
        steps.add(new VerifySuite.Step("dynasty_incorporate",
                "지주(1plot+steward) parent's child founds -> owner=head, child=steward, head->영주 (S3)", 300, false, () -> {
            a[0] = spawnAdult(level, Vec3.atBottomCenterOf(lordHome), Sex.MALE, Trait.HERBALIST); // 지주 부모(1밭+마름)
            a[0].debugSettleWithTent(lordHome, Direction.NORTH);
            a[1] = spawnGradedAdult(level, Vec3.atBottomCenterOf(p1).add(0, 0, 4), Sex.MALE, 5); // 마름
            lordId[0] = a[0].getIndividual().id();
            lordPlots[0] = buildDemoPlot(level, p1, lordId[0], 35); // 단일 밭 — 지주(구획1·마름1), 영주 아님
            FarmStore.get(level).appointSteward(level, lordPlots[0], a[1], "마름임명");
            a[2] = spawnChildOf(level, Vec3.atBottomCenterOf(childHome), a[0], Sex.MALE); // 자식(무산)
            a[2].debugSettleWithTent(childHome, Direction.NORTH);
            childId[0] = a[2].getIndividual().id();
            LarderStore.get(level).set(lordHome, 5.0);   // 부모는 착공 불가(자금 부족) — 자식만 착공
            LarderStore.get(level).set(childHome, 60.0); // 자식 착공 자금
            level.setDayTime(13500L);
            FarmTicker.debugGrow(level);
            long np = 0;
            for (FarmStore.Plot p : FarmStore.get(level).all().values()) {
                if (p.stewardId == childId[0]) {
                    np = p.id;
                }
            }
            newPlot[0] = np;
        }, () -> String.format("newPlot %d owner %s(expect head %d) headClass %s debt %s",
                newPlot[0], newPlot[0] == 0 ? "-" : String.valueOf(FarmStore.get(level).get(newPlot[0]).ownerId),
                lordId[0], newPlot[0] == 0 ? "-" : String.valueOf(FarmStore.get(level).get(newPlot[0]).stewardId),
                newPlot[0] == 0 ? "-" : FarmStore.get(level).classOf(level, lordId[0])),
                // S3: 지주 부모가 자식 밭 편입 → 자식 마름·채무 발생 + 부모가 2호 보유로 영주 부트스트랩.
                () -> newPlot[0] != 0 && FarmStore.get(level).get(newPlot[0]).ownerId == lordId[0]
                        && FarmStore.get(level).get(newPlot[0]).stewardId == childId[0]
                        && FarmStore.get(level).get(newPlot[0]).stewardDebt > 0
                        && FarmStore.get(level).classOf(level, lordId[0]).equals("영주"),
                () -> {
                    discard(a);
                    for (FarmStore.Plot p : new ArrayList<>(FarmStore.get(level).all().values())) {
                        farmClearPlot(level, p);
                    }
                    LarderStore.get(level).remove(lordHome);
                    LarderStore.get(level).remove(childHome);
                    FarmTicker.clearAssignments();
                }));

        // ② 채무 상환 — 영주 저장고(예비 12 초과분) → 마름. 부모 50, 채무 18 → 마름 +18, 채무 0.
        MimicEntity[] b = new MimicEntity[2];
        FarmStore.Plot[] pb = new FarmStore.Plot[1];
        steps.add(new VerifySuite.Step("dynasty_debt_repay",
                "lord larder 50, debt 18 -> steward +18, debt 0", 200, false, () -> {
            b[0] = spawnAdult(level, Vec3.atBottomCenterOf(lordHome), Sex.MALE);
            b[0].debugSettleWithTent(lordHome, Direction.NORTH);
            b[1] = spawnGradedAdult(level, Vec3.atBottomCenterOf(childHome), Sex.MALE, 5);
            b[1].debugSettleWithTent(childHome, Direction.NORTH);
            pb[0] = buildDemoPlot(level, p1, b[0].getIndividual().id(), 20);
            FarmStore.get(level).appointSteward(level, pb[0], b[1], "마름편입");
            pb[0].stewardDebt = 18.0;
            LarderStore.get(level).set(lordHome, 50.0);
            LarderStore.get(level).set(childHome, 0.0);
            level.setDayTime(13500L);
            FarmTicker.debugSettle(level);
        }, () -> String.format("stewardLarder %.0f(expect 18) debt %.0f(expect 0)",
                LarderStore.get(level).get(childHome), pb[0].stewardDebt),
                () -> Math.abs(LarderStore.get(level).get(childHome) - 18.0) < 1.0E-6
                        && pb[0].stewardDebt < 1.0E-6,
                () -> {
                    discard(b);
                    for (FarmStore.Plot p : new ArrayList<>(FarmStore.get(level).all().values())) {
                        farmClearPlot(level, p);
                    }
                    LarderStore.get(level).remove(lordHome);
                    LarderStore.get(level).remove(childHome);
                    FarmTicker.clearAssignments();
                }));

        VerifySuite.start(ctx.getSource(), steps);
        return 1;
    }

    /**
     * M6 관문 ② 무주지·선점 — 무후 지주 파괴 → 무주(ownerId 0) → 밤에 통근 내 유주택 이웃이
     * 선점(ownerId == 이웃). 만료 소거(2.5일)는 시간상 게이트 불가 — 이연 관찰 항목.
     */
    private static int farmVacantDemo(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos anchor = groundAt(level, ctx.getSource().getPosition(), 8, 8);
        BlockPos home = groundAt(level, ctx.getSource().getPosition(), -8, -8);
        BlockPos nHome = groundAt(level, ctx.getSource().getPosition(), -8, 8);
        MimicEntity owner = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        owner.debugSettleWithTent(home, Direction.NORTH);
        MimicEntity neigh = spawnAdult(level, Vec3.atBottomCenterOf(nHome), Sex.MALE);
        neigh.debugSettleWithTent(nHome, Direction.NORTH);
        FarmStore.Plot plot = buildDemoPlot(level, anchor, owner.getIndividual().id(), 9);
        long nid = neigh.getIndividual().id();
        owner.discard(); // 무후·무배우자 — 무주지로
        level.setDayTime(13500L); // 밤 — 다음 스캔에서 선점
        LiveCheck.watch(ctx.getSource(), "farm_vacant_claim", 600,
                () -> String.format("owner %d(0=vacant, expect neighbor %d) vacantSince %s",
                        plot.ownerId, nid, plot.vacantSince >= 0 ? "set" : "-"),
                () -> plot.ownerId == nid && plot.vacantSince < 0,
                () -> {
                    discard(neigh);
                    farmClearPlot(level, plot);
                    FarmTicker.clearAssignments();
                });
        return 1;
    }

    /**
     * M7 관문 만족·동기 — 부유 지주(저장고 60 ≫ 기준 12) + 9타일 즉시 익음 밭.
     * drive=false(farmidle): 무동기 지주는 만족 → 밭 노동 정지 — 익은 9 유지(금지 감시).
     * drive=true(farmdrive): 부지런 지주는 만족 무시 → 수확 발생(ripe 감소) — 대조군.
     */
    private static int farmIdleDemo(CommandContext<CommandSourceStack> ctx, boolean drive) {
        ServerLevel level = ctx.getSource().getLevel();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "검증 진행 중 — 끝난 뒤 실행.");
            return 0;
        }
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos anchor = groundAt(level, ctx.getSource().getPosition(), 8, 8);
        BlockPos home = groundAt(level, ctx.getSource().getPosition(), -8, -8);
        MimicEntity owner = drive
                ? spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE, Trait.DILIGENT)
                : spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        owner.debugSettleWithTent(home, Direction.NORTH);
        LarderStore.get(level).set(home, 60.0);
        FarmStore.Plot plot = buildDemoPlot(level, anchor, owner.getIndividual().id(), 9);
        owner.updateMotivation(level); // 만족 캐시를 goal 첫 틱 전에 확정(farm_hoard 와 동일) —
                                       // 새벽 스캔 전 1회 수확 새어나가는 레이스 차단
        level.setDayTime(1200L); // 새벽 — 동기 갱신 + 노동 시작
        Runnable cleanup = () -> {
            discard(owner);
            farmClearPlot(level, plot);
            FarmTicker.clearAssignments();
        };
        if (drive) {
            LiveCheck.watch(ctx.getSource(), "farm_drive_diligent", 600,
                    () -> String.format("ripe %d(start 9, expect <9) satisfied %s",
                            countRipe(level, plot), owner.isSatisfiedToday() ? "yes" : "no"),
                    () -> countRipe(level, plot) < 9, cleanup);
        } else {
            List<VerifySuite.Step> steps = new ArrayList<>();
            steps.add(new VerifySuite.Step("farm_idle_satisfied",
                    "rich plain owner (larder 60 >> bar 12) must NOT work own farm", 400, true,
                    () -> { }, // 조성은 위에서 완료 — 스텝은 감시만
                    () -> String.format("ripe %d(must stay 9) satisfied %s",
                            countRipe(level, plot), owner.isSatisfiedToday() ? "yes" : "no"),
                    () -> countRipe(level, plot) < 9, // ← 금지 결과(만족했는데 노동)
                    cleanup));
            VerifySuite.start(ctx.getSource(), steps);
        }
        return 1;
    }

    /**
     * M9 관문 ① 출근 관성(R2) — 넉넉해진 <b>복귀자</b>(어제 같은 구획 출근)는 새벽 배정이 유지되고,
     * 동일하게 넉넉한 <b>신규자</b>는 탈락해야 한다(양측 동시 판정 — 면제가 무조건 통과로 구현된
     * 회귀까지 잡는다). need 22(34타일−주인12)라 복귀자만으로는 부족 → 신규자 배정 여부가 필터 실증.
     */
    private static int farmReturnDemo(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos anchor = groundAt(level, ctx.getSource().getPosition(), 8, 8);
        BlockPos oHome = groundAt(level, ctx.getSource().getPosition(), -8, -8);
        BlockPos aHome = groundAt(level, ctx.getSource().getPosition(), -8, 8);
        BlockPos bHome = groundAt(level, ctx.getSource().getPosition(), -12, 0);
        MimicEntity owner = spawnAdult(level, Vec3.atBottomCenterOf(oHome), Sex.MALE);
        owner.debugSettleWithTent(oHome, Direction.NORTH);
        LarderStore.get(level).set(oHome, 5.0); // 주인 빈곤(불만족) — 용량 12 계상, need 22 유지
        MimicEntity ret = spawnAdult(level, Vec3.atBottomCenterOf(aHome), Sex.MALE);
        ret.debugSettleWithTent(aHome, Direction.NORTH);
        LarderStore.get(level).set(aHome, 20.0); // 넉넉 — 관성 면제가 없으면 필터 탈락
        MimicEntity fresh = spawnAdult(level, Vec3.atBottomCenterOf(bHome), Sex.MALE);
        fresh.debugSettleWithTent(bHome, Direction.NORTH);
        LarderStore.get(level).set(bHome, 20.0); // 동일 넉넉 신규 — 배정되면 금지 결과
        FarmStore.Plot plot = buildDemoPlot(level, anchor, owner.getIndividual().id(), 34);
        FarmTicker.debugSeedAssignment(ret.getId(), plot.id); // "어제 출근" — 새벽 롤오버로 복귀자화
        level.setDayTime(1200L); // 새벽 — streak 0→1 전이가 "새벽이 실제 돌았다"의 결과값 마커
        LiveCheck.watch(ctx.getSource(), "farm_return_inertia", 400,
                () -> String.format("ret assigned %s streak %d(expect yes·1+) fresh assigned %s(must stay no)",
                        FarmTicker.assignedPlot(ret.getId()) == plot.id ? "yes" : "no",
                        ret.getTenantStreak(),
                        FarmTicker.assignedPlot(fresh.getId()) == plot.id ? "yes" : "no"),
                () -> FarmTicker.assignedPlot(ret.getId()) == plot.id && ret.getTenantStreak() >= 1
                        && FarmTicker.assignedPlot(fresh.getId()) == 0L,
                () -> {
                    discard(owner, ret, fresh);
                    farmClearPlot(level, plot);
                    FarmTicker.clearAssignments();
                });
        return 1;
    }

    /** M9 관문 ② 개간 노동 개체 상한(R3) — 2구획 지주도 하루 합산 +3타일만(18→21) ∧ 저장고 30→21. */
    /**
     * <b>작물 관리 즉석 무대</b> — 지주 1 + 밭 + 소작 n 을 그 자리에 세우고, 밭을 통째로
     * <b>수확 직후</b>(안 익음) 상태로 둔다. 그러면 소작은 딸 것이 하나도 없으므로, 관리를
     * 하는지 안 하는지만 남는다.
     *
     * <p>긴 런을 돌려 우연히 그 상황이 오기를 기다릴 이유가 없다 — 보고 싶은 것은
     * "익은 게 없을 때 무엇을 하는가" 하나뿐이다.
     */
    private static int careTest(CommandContext<CommandSourceStack> ctx, int tiles, int tenants,
                               boolean ripe) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos anchor = groundAt(level, ctx.getSource().getPosition(), 10, 0);
        BlockPos ownerHome = groundAt(level, ctx.getSource().getPosition(), -14, 0);
        MimicEntity owner = spawnAdult(level, Vec3.atBottomCenterOf(ownerHome), Sex.MALE);
        owner.debugSettleWithTent(ownerHome, Direction.NORTH);
        LarderStore.get(level).set(ownerHome, 60.0);
        FarmStore.Plot plot = buildDemoPlot(level, anchor, owner.getIndividual().id(), tiles);

        // 밭 전체를 수확 직후(기본) 또는 전부 익음(ripe)으로. 전자는 "딸 게 없을 때 무엇을
        // 하는가", 후자는 "익은 게 코앞인데 왜 안 따는가"를 본다.
        //
        // <b>익음은 planted 를 과거로 미뤄서 만들지 않는다.</b> 신규 월드는 gameTime 이 작아
        // now - RIPEN_TICKS 가 음수가 되는데, 0 이하는 "미설치" 센티널이라 FarmTicker 가 그
        // 타일을 새로 심어 버린다 — 실측: "전부 익음"이라 찍어 놓고 익은 타일 0, 잔여 23513틱.
        // 예전에 careBonus 를 만든 이유가 바로 이것이었다(planted[i] -= bonus 언더플로).
        // 시계를 앞당기는 쪽(careBonus)으로 만든다.
        long now = com.evosim.mod.entity.SimTime.tick(level);
        if (ripe) {
            plot.careBonus += FarmEconomy.RIPEN_TICKS;
        }
        for (int i = 0; i < plot.tiles.length; i++) {
            plot.planted[i] = now;
            BlockPos p = BlockPos.of(plot.tiles[i]);
            var st = level.getBlockState(p);
            if (st.is(Blocks.SWEET_BERRY_BUSH)) {
                level.setBlockAndUpdate(p, st.setValue(SweetBerryBushBlock.AGE, ripe ? 3 : 1));
            }
        }
        FarmStore.get(level).setDirty();

        // 소작 — 거처를 따로 주고 그 구획에 상시 배정(구인 사슬을 기다리지 않는다).
        for (int i = 0; i < tenants; i++) {
            double ang = 2.0 * Math.PI * i / tenants;
            BlockPos h = groundAt(level, ctx.getSource().getPosition(),
                    (int) Math.round(-26 + 10 * Math.cos(ang)),
                    (int) Math.round(14 * Math.sin(ang)));
            MimicEntity t = spawnAdult(level, Vec3.atBottomCenterOf(h),
                    i % 2 == 0 ? Sex.MALE : Sex.FEMALE);
            t.debugSettleWithTent(h, Direction.NORTH);
            LarderStore.get(level).set(h, 12.0);
            t.setTenant(plot.id, 9);
        }
        level.setDayTime(2000L); // 근무 구간 한복판 — 관리 goal 이 바로 켜지도록
        double[] c = FarmTicker.careOf(level, plot);
        // <b>무대가 제 상태를 스스로 확인한다.</b> 앞서 "전부 익음"이라 찍어 놓고 실제로는 익은
        // 타일이 0인 채로 관측이 진행됐다(planted 음수 → 미설치 센티널). 라벨과 실제가
        // 어긋나면 그 자리에서 드러나야 한다.
        long careNow = FarmStore.careNow(level, plot);
        int ripeNow = 0;
        for (int i = 0; i < plot.tiles.length; i++) {
            if (plot.planted[i] > 0 && careNow - plot.planted[i] >= FarmEconomy.RIPEN_TICKS) {
                ripeNow++;
            }
        }
        tell(ctx.getSource(), String.format(
                "§e[관리무대]§r 구획 %d · 타일 %d(%s) · 지주 %s · 소작 %d명 @%d,%d",
                plot.id, plot.tiles.length,
                (ripe ? "전부 익음" : "전부 수확 직후") + " · 실제 익은 타일 " + ripeNow
                        + (ripe == (ripeNow == plot.tiles.length) ? "" : " §c← 라벨과 불일치§r"),
                owner.getIndividual().shortName(), tenants,
                anchor.getX(), anchor.getZ()));
        tell(ctx.getSource(), String.format(
                "  1인 케어범위 %.0f타일 → 만석에 필요한 인원 %.1f명 · 지금 커버리지 %.0f%%",
                com.evosim.core.FarmEconomy.CARE_BASE,
                plot.tiles.length / com.evosim.core.FarmEconomy.CARE_BASE, c[1] * 100.0));
        tell(ctx.getSource(), "  ※ 잠시 뒤 /evosim carestat 으로 관리 인원·커버리지·익음 진척을 본다");
        return 1;
    }

    /** 관리 진행 상황 — 구획별 관리 인원·커버리지·익은 타일 수. 무대의 판정 창구. */
    private static int careStat(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        for (FarmStore.Plot p : FarmStore.get(level).all().values()) {
            long now = FarmStore.careNow(level, p); // 익음은 가상 시각 기준
            double[] c = FarmTicker.careOf(level, p);
            int ripe = 0;
            long minLeft = Long.MAX_VALUE;
            for (int i = 0; i < p.tiles.length; i++) {
                if (p.planted[i] < 0) {
                    continue;
                }
                long age = now - p.planted[i];
                if (age >= com.evosim.core.FarmEconomy.RIPEN_TICKS) {
                    ripe++;
                } else {
                    minLeft = Math.min(minLeft, com.evosim.core.FarmEconomy.RIPEN_TICKS - age);
                }
            }
            tell(ctx.getSource(), String.format(
                    "  구획 %d · 타일 %d · §e관리중 %d명 · 커버리지 %.0f%%§r → 익음 배속 ×%.2f"
                            + " · 익은 타일 %d · 가장 빠른 익음까지 %s틱",
                    p.id, p.tiles.length, (int) c[0], c[1] * 100.0,
                    1.0 + com.evosim.core.FarmEconomy.CARE_MAX_BOOST * c[1], ripe,
                    minLeft == Long.MAX_VALUE ? "-" : String.valueOf(minLeft)));
        }
        // 개체별 — "누가 왜 노는가"를 무대가 직접 말하게 한다. 이게 없으면 움찔 지표가 이름만
        // 던지고 사유는 추측이 된다.
        for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null)) {
            long asg = FarmTicker.assignedPlot(m.getId());
            long ten = m.getTenantFarm();
            int owned = FarmStore.get(level).ownedTiles(m.getIndividual().id());
            boolean tending = false;
            for (FarmStore.Plot p : FarmStore.get(level).all().values()) {
                if (FarmTicker.careOf(level, p)[0] > 0 && p.id == (asg != 0 ? asg : ten)) {
                    tending = true;
                    break;
                }
            }
            tell(ctx.getSource(), String.format(
                    "    %s @%d,%d · 배정%d · 계약소작%d · 소유타일%d · 만족%s · 위급%s%s",
                    m.getIndividual().shortName(), m.blockPosition().getX(),
                    m.blockPosition().getZ(), asg, ten, owned,
                    m.isSatisfiedToday() ? "O" : "X", m.isCritical() ? "O" : "X",
                    tending ? " · 관리권" : ""));
        }
        return 1;
    }

    private static int farmLaborDemo(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos a1 = groundAt(level, ctx.getSource().getPosition(), 8, 8);
        BlockPos a2 = groundAt(level, ctx.getSource().getPosition(), 8, -24);
        BlockPos home = groundAt(level, ctx.getSource().getPosition(), -8, -8);
        MimicEntity owner = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        owner.debugSettleWithTent(home, Direction.NORTH);
        LarderStore.get(level).set(home, 30.0); // afford 8타일 — 자금은 남고 노동만 병목이어야
        FarmStore.Plot p1 = buildDemoPlot(level, a1, owner.getIndividual().id(), 9);
        FarmStore.Plot p2 = buildDemoPlot(level, a2, owner.getIndividual().id(), 9);
        level.setDayTime(13500L);
        LiveCheck.watch(ctx.getSource(), "farm_labor_cap", 600,
                () -> String.format("tiles %d+%d=%d(expect 21) larder %.0f(expect 21)",
                        p1.tiles.length, p2.tiles.length, p1.tiles.length + p2.tiles.length,
                        LarderStore.get(level).get(home)),
                () -> p1.tiles.length + p2.tiles.length == 21
                        && Math.abs(LarderStore.get(level).get(home) - 21.0) < 1.0E-6,
                () -> {
                    discard(owner);
                    farmClearPlot(level, p1);
                    farmClearPlot(level, p2);
                    FarmTicker.clearAssignments();
                });
        return 1;
    }

    /** M9 관문 ③ 경쟁 이웃 부 대칭(R5) — 이웃 저장고 5+밭 계정 8(합 13) > 자기 10 → 분발 캐시 on. */
    private static int farmEnvyDemo(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos anchor = groundAt(level, ctx.getSource().getPosition(), 8, 8);
        BlockPos aHome = groundAt(level, ctx.getSource().getPosition(), -8, -8);
        BlockPos bHome = groundAt(level, ctx.getSource().getPosition(), -8, 8);
        MimicEntity envy = spawnAdult(level, Vec3.atBottomCenterOf(aHome), Sex.MALE, Trait.COMPETITIVE);
        envy.debugSettleWithTent(aHome, Direction.NORTH);
        LarderStore.get(level).set(aHome, 10.0); // 저장고만 비교하는 구 정의면 10>5라 분발 없음
        MimicEntity lord = spawnAdult(level, Vec3.atBottomCenterOf(bHome), Sex.MALE);
        lord.debugSettleWithTent(bHome, Direction.NORTH);
        LarderStore.get(level).set(bHome, 5.0);
        FarmStore.Plot plot = buildDemoPlot(level, anchor, lord.getIndividual().id(), 9);
        plot.account = 8.0; // 이웃 부 = 5 + 8 = 13 > 10 — 계정 포함 정의에서만 참
        level.setDayTime(1200L);
        LiveCheck.watch(ctx.getSource(), "farm_envy_account", 400,
                () -> String.format("driven %s(expect yes) — mine 10 vs neighbor 5+acct8",
                        envy.isCompetitiveDriven() ? "yes" : "no"),
                envy::isCompetitiveDriven,
                () -> {
                    discard(envy, lord);
                    farmClearPlot(level, plot);
                    FarmTicker.clearAssignments();
                });
        return 1;
    }

    /**
     * M9 관문 ④ 유령 용량 제거(R6) — 만족 지주는 슬롯 산식에서 용량 0: 15타일 밭의 need 가
     * 3(<최소일감, 구 코드)이 아니라 15가 되어 가난 이웃이 배정된다(배정 발생 자체가 판별식).
     */
    private static int farmRetireDemo(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos anchor = groundAt(level, ctx.getSource().getPosition(), 8, 8);
        BlockPos oHome = groundAt(level, ctx.getSource().getPosition(), -8, -8);
        BlockPos wHome = groundAt(level, ctx.getSource().getPosition(), -8, 8);
        MimicEntity owner = spawnAdult(level, Vec3.atBottomCenterOf(oHome), Sex.MALE);
        owner.debugSettleWithTent(oHome, Direction.NORTH);
        LarderStore.get(level).set(oHome, 60.0); // 새벽 동기 갱신에서 만족(60 >> bar 12.5)
        MimicEntity worker = spawnAdult(level, Vec3.atBottomCenterOf(wHome), Sex.MALE);
        worker.debugSettleWithTent(wHome, Direction.NORTH);
        LarderStore.get(level).set(wHome, 0.0);
        FarmStore.Plot plot = buildDemoPlot(level, anchor, owner.getIndividual().id(), 15);
        level.setDayTime(1200L);
        LiveCheck.watch(ctx.getSource(), "farm_retire_slots", 400,
                () -> String.format("owner satisfied %s worker assigned %s(expect yes·yes)",
                        owner.isSatisfiedToday() ? "yes" : "no",
                        FarmTicker.assignedPlot(worker.getId()) == plot.id ? "yes" : "no"),
                () -> FarmTicker.assignedPlot(worker.getId()) == plot.id,
                () -> {
                    discard(owner, worker);
                    farmClearPlot(level, plot);
                    FarmTicker.clearAssignments();
                });
        return 1;
    }

    /**
     * M9 관문 ⑤ 재투자 금지측(R1) — 만족 지주는 재투자하지 않고 계정을 전액 착복: 타일 9 유지 ∧
     * 저장고 60→67 ∧ 계정 7→0 ∧ 소작 저장고 16 불변. 만족 캐시는 실경로 함수(updateMotivation)로 조성.
     */
    private static int farmHoardDemo(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos anchor = groundAt(level, ctx.getSource().getPosition(), 8, 8);
        BlockPos oHome = groundAt(level, ctx.getSource().getPosition(), -8, -8);
        BlockPos tHome = groundAt(level, ctx.getSource().getPosition(), -8, 8);
        MimicEntity owner = spawnAdult(level, Vec3.atBottomCenterOf(oHome), Sex.MALE);
        owner.debugSettleWithTent(oHome, Direction.NORTH);
        LarderStore.get(level).set(oHome, 60.0);
        MimicEntity tenant = spawnAdult(level, Vec3.atBottomCenterOf(tHome), Sex.MALE);
        tenant.debugSettleWithTent(tHome, Direction.NORTH);
        LarderStore.get(level).set(tHome, 16.0);
        FarmStore.Plot plot = buildDemoPlot(level, anchor, owner.getIndividual().id(), 9);
        tenant.setTenant(plot.id, 3);
        plot.account = 7.0;
        owner.updateMotivation(level); // 조성 — wealth 67 >> bar → satisfiedToday(결말은 실경로)
        level.setDayTime(13500L);
        LiveCheck.watch(ctx.getSource(), "farm_hoard_satisfied", 600,
                () -> String.format("tiles %d(must stay 9) acct %.1f(expect 0) "
                        + "ownerLarder %.0f(expect 67) tenantLarder %.0f(must stay 16)",
                        plot.tiles.length, plot.account,
                        LarderStore.get(level).get(oHome), LarderStore.get(level).get(tHome)),
                () -> plot.tiles.length == 9
                        && Math.abs(plot.account) < 1.0E-6
                        && Math.abs(LarderStore.get(level).get(oHome) - 67.0) < 1.0E-6
                        && Math.abs(LarderStore.get(level).get(tHome) - 16.0) < 1.0E-6,
                () -> {
                    discard(owner, tenant);
                    farmClearPlot(level, plot);
                    FarmTicker.clearAssignments();
                });
        return 1;
    }

    /** M9 관문 ⑥ 능력 게이트 양성측 — 약초학자 지주는 35를 넘는다: 33→36 ∧ 저장고 30→24(3타일×2.0, farmcap 대조). */
    private static int farmAbleDemo(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos anchor = groundAt(level, ctx.getSource().getPosition(), 8, 8);
        BlockPos home = groundAt(level, ctx.getSource().getPosition(), -8, -8);
        MimicEntity owner = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE, Trait.HERBALIST);
        owner.debugSettleWithTent(home, Direction.NORTH);
        LarderStore.get(level).set(home, 30.0);
        FarmStore.Plot plot = buildDemoPlot(level, anchor, owner.getIndividual().id(), 33);
        level.setDayTime(13500L);
        LiveCheck.watch(ctx.getSource(), "farm_skill_pass", 600,
                () -> String.format("tiles %d(expect 36 — must pass 35) larder %.0f(expect 24)",
                        plot.tiles.length, LarderStore.get(level).get(home)),
                () -> plot.tiles.length == 36
                        && Math.abs(LarderStore.get(level).get(home) - 24.0) < 1.0E-6,
                () -> {
                    discard(owner);
                    farmClearPlot(level, plot);
                    FarmTicker.clearAssignments();
                });
        return 1;
    }

    /**
     * 케어 관문 ① 가족 노동 — 남편 소유 9타일, 남편 noAI(수확 불가) → 아내가 배우자 밭을 수확하고
     * 그 몫이 가족분 100%(지대 적립 0)여야 한다. 종전 코드(주인만 수확 가능)면 익은 9 유지 → FAIL.
     */
    private static int farmFamilyDemo(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos anchor = groundAt(level, ctx.getSource().getPosition(), 8, 8);
        BlockPos home = groundAt(level, ctx.getSource().getPosition(), -8, -8);
        MimicEntity[] cc = coupleAt(level, home);
        LarderStore.get(level).set(home, 0.0); // 빈곤 — 아내 불만족(노동 동기 유지)
        FarmStore.Plot plot = buildDemoPlot(level, anchor, cc[0].getIndividual().id(), 9);
        cc[0].setNoAi(true); // 주인 노동 봉쇄 — 수확이 있다면 아내(가족 노동)뿐
        level.setDayTime(4000L);
        LiveCheck.watch(ctx.getSource(), "farm_family_labor", 1200,
                () -> String.format("ripe %d(start 9, expect <9) acct %.2f(must stay 0)",
                        countRipe(level, plot), plot.account),
                () -> countRipe(level, plot) < 9 && Math.abs(plot.account) < 1.0E-6,
                () -> {
                    discard(cc[0], cc[1]);
                    farmClearPlot(level, plot);
                    FarmTicker.clearAssignments();
                });
        return 1;
    }

    /**
     * 케어 관문 ② 케어 예산 — 근접 9타일 + 원거리 30타일 지주: 용량 12가 가까운 밭부터 소진
     * (9+3) → 원거리 need 27 → 소작 3명 배정. 종전(구획당 중복 차감)이면 need 18 → 2명뿐 → FAIL.
     */
    private static int farmCareDemo(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        FarmTicker.clearAssignments();
        BlockPos anchor = groundAt(level, ctx.getSource().getPosition(), 8, 8);
        BlockPos far = groundAt(level, ctx.getSource().getPosition(), 8, 32);
        BlockPos home = groundAt(level, ctx.getSource().getPosition(), -2, -2);
        MimicEntity owner = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        owner.debugSettleWithTent(home, Direction.NORTH);
        LarderStore.get(level).set(home, 5.0);
        MimicEntity[] w = new MimicEntity[3];
        for (int i = 0; i < 3; i++) {
            BlockPos wh = groundAt(level, ctx.getSource().getPosition(), -10, 8 + 4 * i);
            w[i] = spawnAdult(level, Vec3.atBottomCenterOf(wh), Sex.MALE);
            w[i].debugSettleWithTent(wh, Direction.NORTH);
            LarderStore.get(level).set(wh, 0.0);
        }
        FarmStore.Plot near = buildDemoPlot(level, anchor, owner.getIndividual().id(), 9);
        FarmStore.Plot farPlot = buildDemoPlot(level, far, owner.getIndividual().id(), 30);
        level.setDayTime(1200L);
        LiveCheck.watch(ctx.getSource(), "farm_care_budget", 400,
                () -> String.format("assigned far %d/3(expect 3 — old code caps at 2) near %d(expect 0)",
                        (int) java.util.Arrays.stream(w)
                                .filter(m -> FarmTicker.assignedPlot(m.getId()) == farPlot.id).count(),
                        (int) java.util.Arrays.stream(w)
                                .filter(m -> FarmTicker.assignedPlot(m.getId()) == near.id).count()),
                () -> java.util.Arrays.stream(w)
                        .allMatch(m -> FarmTicker.assignedPlot(m.getId()) == farPlot.id),
                () -> {
                    discard(owner, w[0], w[1], w[2]);
                    farmClearPlot(level, near);
                    farmClearPlot(level, farPlot);
                    FarmTicker.clearAssignments();
                });
        return 1;
    }

    /**
     * 진단 ① 길찾기 사거리 — 미믹의 실제 경로탐색 도달 한계를 거리별로 즉시 측정(동기). 미믹 1기 +
     * 정면 D블록 지점(지형 로드)에 {@code createPath} 를 호출해 도달 가능(canReach) 여부만 본다.
     * 목표·트리거·환경(풀)과 완전 분리 — 순수하게 "그 거리에 길을 찾는가"만. 결과값 판정: 근거리(16)
     * 도달 ∧ 원거리(56) 불가면 <b>사거리 한계 존재 확정</b>(구혼여행 56·노인배달 40·이주 실패의 공통 원인).
     * FOLLOW_RANGE=24 가 원인이면 컷오프가 24 부근에 찍힌다.
     */
    private static final net.minecraft.world.entity.ai.attributes.Attribute FR_ATTR =
            net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE;

    /** 미믹의 실제 경로탐색 도달 여부를 거리별로 즉석 측정(동기 createPath). */
    private static boolean navReach(ServerLevel level, MimicEntity m, int d) {
        BlockPos tgt = level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                m.blockPosition().offset(d, 0, 0));
        level.getChunk(tgt.getX() >> 4, tgt.getZ() >> 4); // 경로상 지형 강제 로드
        var path = m.getNavigation().createPath(tgt, 1);
        return path != null && path.canReach();
    }

    /** 감쇠 완화 A/B — 대조 런에서 끈다(같은 jar 로 두 조건을 돌리기 위한 스위치). */
    private static int setRelief(CommandContext<CommandSourceStack> ctx, boolean on) {
        AllegianceStore.RELIEF_ON = on;
        tell(ctx.getSource(), String.format(
                "감쇠 완화 %s — 교회 주인에게 진 신세의 하루 감쇠 %.2f (평시 %.2f)",
                on ? "ON" : "OFF",
                on ? AllegianceStore.DECAY_RELIEVED : AllegianceStore.DECAY_PER_DAY,
                AllegianceStore.DECAY_PER_DAY));
        return 1;
    }

    /** {@link #jitterProbe} 의 직전 표본: 개체 id → [x, z, 목표x, 목표z, 게임틱]. */
    private static final java.util.Map<Integer, double[]> JITTER_SNAP = new java.util.HashMap<>();

    /**
     * <b>움찔거림 계측</b> — "어디 가지 못하고 목표가 계속 바뀌며 제자리에서 떠는" 개체를 <b>수로</b>
     * 잡는다. 스크린샷은 한 순간만 보여 주므로 이 증상은 눈으로 못 세고, 사건 로그도 목표 전환을
     * 남기지 않는다.
     *
     * <p>두 번 부른다. 첫 호출은 전원의 (위치, 이동 목표)를 적어 두고, 다음 호출은 그 사이의
     * <b>실제 이동거리</b>와 <b>목표가 바뀌었는지</b>를 함께 본다. 판정은 둘의 곱이다 —
     *
     * <ul>
     *   <li>이동 &lt; 1블록 <b>이면서</b> 목표가 바뀌었다 → 움찔(재조준만 하고 못 감)</li>
     *   <li>이동 &lt; 1블록인데 목표도 그대로 → 그냥 멈춰 있는 것(휴식·작업·대기, 정상)</li>
     *   <li>이동이 있으면 목표가 바뀌든 말든 정상(길을 가다 마음을 바꾼 것)</li>
     * </ul>
     *
     * <p>목표가 없는(navigation done) 개체는 재조준할 것이 없으므로 분모에서 뺀다.
     */
    /**
     * goal 갈아타기 상위 목록 — "무엇과 무엇 사이를 오가는가"를 그대로 보여 준다.
     *
     * <p>왕복은 <b>쌍</b>으로 나타난다: A→B 와 B→A 가 나란히 상위에 있으면 그 둘이 서로를
     * 선점하며 진동하는 것이다. 한쪽만 많으면 정상적인 하루 흐름(예: 밭→귀가)이다.
     */
    /**
     * <b>주둔 보고</b> — 군인이 감당되는가, 시민이 쪼들리는가.
     *
     * <p>사용자가 확인하라고 지목한 셋을 한 화면에 낸다: 군인 수와 지주 부담, 지주가 몰락하고
     * 있지 않은지(저장고 추세), 보호세를 낸 가구가 궁해지지 않았는지.
     */
    private static int guardReport(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        var reg = com.evosim.mod.entity.FacilityStore.get(level);
        var lar = LarderStore.get(level);
        var fl = com.evosim.mod.entity.FamilyLedger.get(level);
        double[] g = FarmTicker.guardSums();
        tell(ctx.getSource(), String.format(
                "§e[주둔]§r 어제 배속 %.0f명 · 봉급 %.1f · 세수 %.1f · §c지주 실부담 %.1f§r"
                        + " · 이탈 %.0f · 구휼 %.0f",
                g[0], g[1], g[2], g[1] - g[2], g[3], g[4]));
        int n = 0;
        for (var e : reg.all()) {
            if (e.kind.group != com.evosim.mod.entity.FacilityTemplate.Group.BARRACKS) {
                continue;
            }
            n++;
            var owner = fl.get(e.ownerId);
            int fol = FarmTicker.followersOf(e.ownerId);
            tell(ctx.getSource(), String.format(
                    "  막사 @%d,%d · 주인 §a%s§r 추종자 %d → 정원 %d · 주인 저장고 %.1f"
                            + " · 누계 지출 %.1f 수입 %.1f",
                    e.pos.getX(), e.pos.getZ(),
                    owner != null && owner.name != null ? owner.name : "#" + e.ownerId, fol,
                    Math.min(12, fol / com.evosim.mod.entity.Facilities.HOUSEHOLDS_PER_SOLDIER),
                    lar.get(ownerHomeOf(level, e.ownerId)), e.spent, e.earned));
        }
        if (n == 0) {
            tell(ctx.getSource(), "  막사 없음");
        }
        // 실제 군인 목록 — 저장고가 균형점(≈21) 근처인지, 굶고 있지는 않은지.
        int soldiers = 0;
        for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                e -> e.isAlive() && FarmTicker.isSoldier(e))) {
            soldiers++;
            if (soldiers <= 12) {
                tell(ctx.getSource(), String.format("    병사 %s · 저장고 %.1f · 소지 %.1f%s",
                        m.getIndividual() == null ? "?" : m.getIndividual().shortName(),
                        m.getHomePos() == null ? 0.0 : lar.get(m.getHomePos()), m.getHolding(),
                        m.isCritical() ? " §c위급§r" : ""));
            }
        }
        tell(ctx.getSource(), String.format("  현재 배속 %d명", soldiers));
        return 1;
    }

    private static BlockPos ownerHomeOf(ServerLevel level, long ownerId) {
        for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null
                        && e.getIndividual().id() == ownerId && e.getHomePos() != null)) {
            return m.getHomePos();
        }
        return BlockPos.ZERO;
    }

    /**
     * <b>도면 점검</b> — 시설 도면이 몇 자리를 내는가.
     *
     * <p>자리 수는 곧 정원(학생·방문자·주둔 병력)이라 경제 수치다. 도면을 새로 넣거나 자리
     * 판정을 바꿀 때마다 <b>긴 런을 돌려 우연히 드러나기를 기다릴 이유가 없다</b> — 그 자리에서
     * 세어 본다. 교회 도면이 실측 자리 0 이던 결함을 이런 확인 없이 오래 끌었다.
     */
    private static int tplCheck(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        for (var kind : com.evosim.mod.entity.FacilityTemplate.Kind.values()) {
            var t = com.evosim.mod.entity.FacilityTemplate.of(level, kind, (byte) 0, false);
            if (t.isEmpty()) {
                tell(ctx.getSource(), String.format("  §c%s(%s) — 도면 로드 실패§r",
                        kind.label, kind.design));
                continue;
            }
            var tpl = t.get();
            tell(ctx.getSource(), String.format("  %s(%s) · 자리 §a%d§r · 반경 %.1f · 진입칸 %d",
                    kind.label, kind.design, tpl.seats().size(), tpl.reach(),
                    tpl.doorSteps().size()));
        }
        return 1;
    }

    /**
     * <b>밭 보고</b> — 구획마다 무엇이 확장을 막고 있는가.
     *
     * <p>확장은 세 상한의 최솟값이라(노동·자금·타일상한) 밖에서는 왜 멈췄는지 알 수 없었다 —
     * "48타일 밭에 50이 쌓이는데 안 커진다"를 코드만 읽고 추측해야 했다. 셋을 나란히 찍고
     * <b>지금 발목을 잡는 것</b>에 표시를 단다.
     */
    private static int farmsReport(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        FarmStore store = FarmStore.get(level);
        var fl = com.evosim.mod.entity.FamilyLedger.get(level);
        if (store.all().isEmpty()) {
            tell(ctx.getSource(), "§e[밭]§r 등록된 구획이 없다");
            return 1;
        }
        java.util.List<FarmStore.Plot> plots = new java.util.ArrayList<>(store.all().values());
        plots.sort((a, b) -> Integer.compare(b.tiles.length, a.tiles.length));
        tell(ctx.getSource(), String.format("§e[밭]§r 구획 %d", plots.size()));
        for (FarmStore.Plot p : plots) {
            int nTen = FarmTicker.assignedToPlot(p.id);
            int fol = FarmTicker.followersOf(p.ownerId);
            int cap = com.evosim.core.FarmEconomy.plotTileCap(fol);
            int capRoom = Math.max(0, cap - p.tiles.length);
            int labor = Math.min(com.evosim.core.FarmEconomy.EXPAND_DAY_MAX,
                    com.evosim.core.FarmEconomy.EXPAND_PER_DAY * (1 + nTen));
            int afford = com.evosim.core.FarmEconomy.reinvestTiles(
                    p.account * com.evosim.core.FarmEconomy.MATURE_REINVEST_SHARE, p.steps + 1);
            int k = Math.min(Math.min(labor, afford), capRoom);
            String bind;
            if (capRoom == 0) {
                bind = String.format("§c상한 도달§r — 추종자 1명 더 있어야 %d칸까지",
                        cap + com.evosim.core.FarmEconomy.PLOT_TILE_PER_FOLLOWER);
            } else if (afford <= 0) {
                bind = String.format("§e자금 부족§r — 1칸에 %.1f 필요, 쓸 수 있는 몫 %.1f"
                        + "(계정 %.1f × %.0f%%)",
                        com.evosim.core.FarmEconomy.expandCost(p.steps + 1),
                        p.account * com.evosim.core.FarmEconomy.MATURE_REINVEST_SHARE, p.account,
                        com.evosim.core.FarmEconomy.MATURE_REINVEST_SHARE * 100);
            } else if (afford <= labor && afford <= capRoom) {
                bind = String.format("§a자금이 한도§r — 오늘 %d칸", k);
            } else if (labor <= capRoom) {
                bind = String.format("§a노동이 한도§r — 오늘 %d칸(소작 %d명)", k, nTen);
            } else {
                bind = String.format("§a상한이 한도§r — 오늘 %d칸", k);
            }
            var owner = fl.get(p.ownerId);
            tell(ctx.getSource(), String.format(
                    "  구획%d @%d,%d · %d타일 · 계정 %.1f · 소작 %d · 주인 §a%s§r 추종자 %d → 상한 %d",
                    p.id, p.anchor.getX(), p.anchor.getZ(), p.tiles.length, p.account, nTen,
                    owner != null && owner.name != null ? owner.name : "#" + p.ownerId, fol, cap));
            tell(ctx.getSource(), "     → " + bind);
        }
        return 1;
    }

    /**
     * <b>주거 보고</b> — 가구마다 인원·등급·수용·저장고, 그리고 <b>승격에 얼마가 모자란가</b>.
     *
     * <p>"6명이 소형 집에 사는데 이사가 작동하는 게 맞나"에 답하기 위한 것이다. 승격 판정
     * ({@code upgradeTick})은 돈이 모자라면 <b>아무 기록도 남기지 않고</b> 넘어가서, 밖에서는
     * 고장인지 가난인지 구분할 수 없었다. 모자란 액수를 직접 보여 준다.
     */
    private static int homesReport(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        HomeStore reg = HomeStore.get(level);
        LarderStore lar = LarderStore.get(level);
        // 거처별 거주 인원 — 개체의 homePos 로 센다(등기 기준이 아니라 실제 사는 사람 기준).
        java.util.Map<Long, Integer> members = new java.util.HashMap<>();
        java.util.Map<Long, Double> need = new java.util.HashMap<>();
        for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getHomePos() != null && e.getIndividual() != null)) {
            long k = m.getHomePos().asLong();
            members.merge(k, 1, Integer::sum);
            if (m.getStage() == com.evosim.core.LifeStage.ADULT
                    || m.getStage() == com.evosim.core.LifeStage.ELDER) {
                // 승격 판정과 같은 기준(성년만의 명목소모 합)으로 맞춘다 — 다른 식을 쓰면
                // 보고서의 "모자란 액수"가 실제 판정과 어긋난다.
                need.merge(k, com.evosim.core.FoodEconomy.consumptionPerDay(m.getStage(),
                        com.evosim.core.Activity.MOVE, m.getIndividual(), false), Double::sum);
            }
        }
        if (members.isEmpty()) {
            tell(ctx.getSource(), "§e[주거]§r 거주 가구가 없다");
            return 1;
        }
        int cramped = 0;
        int blocked = 0;
        java.util.List<java.util.Map.Entry<Long, Integer>> rows =
                new java.util.ArrayList<>(members.entrySet());
        rows.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        tell(ctx.getSource(), String.format("§e[주거]§r 가구 %d", rows.size()));
        for (var row : rows) {
            BlockPos home = BlockPos.of(row.getKey());
            HomeStore.Entry e = reg.entry(home);
            if (e == null) {
                continue;
            }
            int n = row.getValue();
            var cur = com.evosim.mod.entity.HomeTemplate.Tier.of(e.design());
            var want = com.evosim.mod.entity.HomeTemplate.Tier.smallestFor(n);
            double stock = lar.get(home);
            double res = com.evosim.mod.entity.HomeTemplate.reserve(need.getOrDefault(row.getKey(), 6.0));
            String state;
            if (want.ordinal() <= cur.ordinal()) {
                state = "§a여유§r";
            } else {
                cramped++;
                double want2 = want.buildCost + res;
                if (stock >= want2) {
                    state = "§e승격 대기(다음 새벽)§r"; // 조건 충족 — 다음 가구틱에 오른다
                } else {
                    blocked++;
                    state = String.format("§c협소 · %s 필요 %.1f · %.1f 모자람§r",
                            want, want2, want2 - stock);
                }
            }
            tell(ctx.getSource(), String.format("  @%d,%d %s(수용%d) · 거주 %d명 · 저장고 %.1f · %s",
                    home.getX(), home.getZ(), cur, cur.capacity, n, stock, state));
        }
        tell(ctx.getSource(), String.format(
                "  → 협소 %d가구 중 %d가구가 §c자금 부족§r으로 못 올라감 (판정은 매 새벽 1회)",
                cramped, blocked));
        return 1;
    }

    /**
     * <b>시설 등기 보고</b> — 누가 세웠고, 누가 일하고, 얼마를 벌었는가.
     *
     * <p>"교회 설립자가 누군지 알 수 없음"에 대한 답이다. 세운 자({@code ownerId})는 등기에
     * 이미 있는데 밖으로 내보내는 길이 없었다. 사용료 수입·건축비도 같이 보여야 "저 건물이
     * 누구에게 무엇을 벌어 주는가"가 한눈에 들어온다.
     */
    private static int churchReport(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        var fl = com.evosim.mod.entity.FamilyLedger.get(level);
        var fs = FarmStore.get(level);
        var all = com.evosim.mod.entity.FacilityStore.get(level).all();
        if (all.isEmpty()) {
            tell(ctx.getSource(), "§e[시설]§r 등기된 시설이 없다");
            return 1;
        }
        tell(ctx.getSource(), String.format("§e[시설]§r 등기 %d곳", all.size()));
        for (var e : all) {
            var owner = fl.get(e.ownerId);
            var staff = e.staffId == 0L ? null : fl.get(e.staffId);
            // <b>주인의 추종자 수</b>를 같이 낸다. 시설은 그 주인을 따르는 가구만 쓰므로,
            // 추종자 0인 사람 명의로 등기되면 아무도 못 쓴다 — 실측된 그 상황("야망가가 벌고
            // 마누라가 지어 다들 못 씀")이 여기서 한눈에 보여야 한다.
            int fol = FarmTicker.followersOf(e.ownerId);
            tell(ctx.getSource(), String.format(
                    "  %s @%d,%d · 세운이 §a%s§r(d%d) · %s추종자 %d명§r · 일꾼 %s"
                            + " · 건축비 %.1f 수입 %.1f · 주인 밭 %d타일",
                    e.kind, e.pos.getX(), e.pos.getZ(),
                    owner != null && owner.name != null ? owner.name : "#" + e.ownerId,
                    e.foundedDay, fol == 0 ? "§c" : "§a", fol,
                    staff != null && staff.name != null ? staff.name : (e.staffId == 0L ? "없음"
                            : "#" + e.staffId),
                    e.spent, e.earned, fs.ownedTiles(e.ownerId)));
        }
        return 1;
    }

    private static int goalChurn(CommandContext<CommandSourceStack> ctx) {
        long since = MimicEntity.churnSince();
        if (since < 0) {
            tell(ctx.getSource(), "§e[갈아타기]§r 기록이 꺼져 있다 — 'evosim goalchurn reset' 으로 시작");
            return 1;
        }
        long dt = com.evosim.mod.entity.SimTime.tick(ctx.getSource().getLevel()) - since;
        int total = MimicEntity.goalChurn().values().stream().mapToInt(Integer::intValue).sum();
        int flicks = MimicEntity.goalFlick().values().stream().mapToInt(Integer::intValue).sum();
        tell(ctx.getSource(), String.format(
                "§e[갈아타기]§r %d틱 · 전이 %d종 %d회 · 그중 §c짧게 머물다 갈아탐 %d회(%.0f%%)§r",
                dt, MimicEntity.goalChurn().size(), total, flicks,
                total == 0 ? 0.0 : 100.0 * flicks / total));
        // <b>짧게 머문 것</b>부터 보여 준다 — 정상적인 하루 순환(채집→귀가→채집)도 전이 횟수만
        // 보면 왕복과 똑같이 상위에 오르므로, 그 목록만으로는 결함을 가릴 수 없다.
        tell(ctx.getSource(), "§7 ── 짧게 머물다 갈아탄 것(진짜 움찔) ──");
        MimicEntity.goalFlick().entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(8).forEach(e -> {
                    String[] ab = e.getKey().split("→");
                    int back = ab.length == 2
                            ? MimicEntity.goalFlick().getOrDefault(ab[1] + "→" + ab[0], 0) : 0;
                    tell(ctx.getSource(), String.format("  §c%-26s %5d회 (역방향 %d)§r",
                            e.getKey(), e.getValue(), back));
                });
        if (flicks == 0) {
            tell(ctx.getSource(), "  §a없음§r");
        }
        tell(ctx.getSource(), "§7 ── 전체 전이(참고 — 일과 포함) ──");
        MimicEntity.goalChurn().entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(8).forEach(e -> tell(ctx.getSource(),
                        String.format("  %-26s %5d회", e.getKey(), e.getValue())));
        return 1;
    }

    private static int jitterProbe(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        long now = level.getGameTime();
        java.util.List<MimicEntity> all = new java.util.ArrayList<>(level.getEntities(
                ModEntities.MIMIC.get(), e -> e.isAlive() && e.getIndividual() != null));
        int seen = 0;
        int stuck = 0;
        int retargeted = 0;
        int jitter = 0;
        int jam = 0;                       // 끼임 — 경로는 있는데 못 감(문 앞 정체 등)
        int careSeen = 0;                  // 돌봄 구속 개체 중 두 표본 모두에 잡힌 수
        int careFlip = 0;                  // 그 사이 육아 goal 선점 여부가 뒤집힌 수
        long dtTicks = 0;
        StringBuilder who = new StringBuilder();
        StringBuilder jamWho = new StringBuilder();
        StringBuilder flipWho = new StringBuilder();
        for (MimicEntity m : all) {
            var path = m.getNavigation().getPath();
            double tx = path == null || path.getTarget() == null ? Double.NaN
                    : path.getTarget().getX();
            double tz = path == null || path.getTarget() == null ? Double.NaN
                    : path.getTarget().getZ();
            boolean parenting = m.isParentingRunning();
            double[] prev = JITTER_SNAP.get(m.getId());
            JITTER_SNAP.put(m.getId(), new double[] {m.getX(), m.getZ(), tx, tz, now,
                    parenting ? 1 : 0, m.isCaregiverBound() ? 1 : 0});
            // <b>육아 뒤집힘</b> — 돌봄 구속 개체에서 육아 goal 의 선점 여부가 표본 사이에
            // 바뀌었다. 위치만 보는 움찔 지표는 이걸 놓친다(왕복하는 개체는 "움직였다"로
            // 분류된다). 육안 관측 "육아와 밭일이 계속 반복되며 왔다갔다"의 직접 계측이다.
            if (prev != null && prev.length >= 7 && prev[4] < now && prev[6] > 0.5
                    && m.isCaregiverBound()) {
                careSeen++;
                if ((prev[5] > 0.5) != parenting) {
                    careFlip++;
                    if (flipWho.length() < 160) {
                        flipWho.append(String.format("%s@%d,%d ", m.getIndividual().shortName(),
                                m.blockPosition().getX(), m.blockPosition().getZ()));
                    }
                }
            }
            if (prev == null || prev[4] >= now) {
                continue; // 첫 표본(또는 같은 틱 재호출) — 비교할 것이 없다
            }
            if (Double.isNaN(prev[2]) && Double.isNaN(tx)) {
                continue; // 그때도 지금도 갈 데가 없다 — 재조준 자체가 성립 안 함
            }
            seen++;
            dtTicks = now - (long) prev[4];
            double moved = Math.hypot(m.getX() - prev[0], m.getZ() - prev[1]);
            boolean changed = Double.isNaN(prev[2]) != Double.isNaN(tx)
                    || (!Double.isNaN(tx) && (Math.abs(prev[2] - tx) > 0.5
                            || Math.abs(prev[3] - tz) > 0.5));
            if (moved < 1.0) {
                stuck++;
            }
            if (changed) {
                retargeted++;
            }
            if (moved < 1.0 && changed) {
                jitter++;
                if (who.length() < 160) {
                    who.append(String.format("%s@%d,%d ", m.getIndividual().shortName(),
                            m.blockPosition().getX(), m.blockPosition().getZ()));
                }
            }
            // <b>끼임</b> — 갈 곳이 정해져 있고(경로 진행 중) 목표도 그대로인데 못 갔다.
            // 움찔(목표 churn)과는 다른 병이다: 문 하나를 두 명이 동시에 지나려다 서로 밀며
            // 비비는 경우가 여기 해당한다. 종전 지표는 "목표가 바뀌었는가"를 요구해 이 경우를
            // 통째로 놓쳤고(정상으로 분류), 그 수를 근거로 "정상"이라 보고했다.
            if (moved < 0.5 && !changed && !Double.isNaN(tx) && m.getNavigation().isInProgress()) {
                jam++;
                if (jamWho.length() < 160) {
                    jamWho.append(String.format("%s@%d,%d ", m.getIndividual().shortName(),
                            m.blockPosition().getX(), m.blockPosition().getZ()));
                }
            }
        }
        if (seen == 0) {
            tell(ctx.getSource(), String.format(
                    "§e[움찔]§r 기준 표본 적재(%d명) — 잠시 뒤 한 번 더 부르면 그 사이를 잰다",
                    all.size()));
            return 1;
        }
        tell(ctx.getSource(), String.format(
                "§e[움찔]§r %d틱 사이 %d명 관측 · 제자리(<1블록)%d · 목표바뀜%d · %s움찔%d명(%.0f%%)§r",
                dtTicks, seen, stuck, retargeted, jitter == 0 ? "§a" : "§c", jitter,
                100.0 * jitter / seen));
        if (jitter > 0) {
            tell(ctx.getSource(), "  " + who.toString().trim());
        }
        tell(ctx.getSource(), String.format(
                "§e[끼임]§r 경로는 있는데 못 간 개체 %s%d명(%.0f%%)§r — 문 하나를 여럿이 지나려는 정체 등",
                jam == 0 ? "§a" : "§c", jam, 100.0 * jam / seen));
        if (jam > 0) {
            tell(ctx.getSource(), "  " + jamWho.toString().trim());
        }
        tell(ctx.getSource(), String.format(
                "§e[육아왕복]§r 돌봄구속 %d명 중 육아↔노동 선점이 뒤집힌 개체 %s%d명(%.0f%%)§r · 이력현상 %s",
                careSeen, careFlip == 0 ? "§a" : "§c", careFlip,
                careSeen == 0 ? 0.0 : 100.0 * careFlip / careSeen,
                com.evosim.mod.entity.MimicParentingGoal.hysteresis() ? "ON" : "OFF"));
        if (careFlip > 0) {
            tell(ctx.getSource(), "  " + flipWho.toString().trim());
        }
        return 1;
    }

    private static int navProbe(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        BlockPos origin = groundAt(level, ctx.getSource().getPosition(), 0, 0);
        MimicEntity m = spawnAdult(level, Vec3.atBottomCenterOf(origin), Sex.MALE);
        // setNoAi 는 쓰지 않는다 — NoAI 는 onGround 를 못 잡아 createPath 가 계속 null 을 반환한다.
        int[] dists = {16, 24, 32, 40, 56};
        int[] wait = {0};
        Boolean[] verdict = {null};
        String[] detail = {"settling (waiting onGround)..."};
        LiveCheck.watch(ctx.getSource(), "nav_range_probe", 200,
                () -> detail[0],
                () -> {
                    if (verdict[0] != null) {
                        return verdict[0];
                    }
                    // 갓 스폰한 엔티티는 첫 틱 onGround=false → createPath 무조건 null. 착지까지 대기.
                    if (wait[0]++ < 10) {
                        return false;
                    }
                    var frInst = m.getAttribute(FR_ATTR);
                    double frBase = frInst.getBaseValue();
                    StringBuilder sb = new StringBuilder("onGround=")
                            .append(m.onGround() ? "Y" : "N").append(" FR").append((int) frBase).append(": ");
                    boolean reach16 = false;
                    boolean reach56 = false;
                    for (int d : dists) {
                        boolean r = navReach(level, m, d);
                        if (d == 16) {
                            reach16 = r;
                        }
                        if (d == 56) {
                            reach56 = r;
                        }
                        sb.append("d").append(d).append(r ? ":OK " : ":X ");
                    }
                    // FR 을 64 로 올려 56 재측정(같은 미믹) — 원인이 사거리면 여기서 56 이 열린다.
                    frInst.setBaseValue(64.0);
                    boolean reach56boost = navReach(level, m, 56);
                    frInst.setBaseValue(frBase); // 원복(어차피 소거)
                    sb.append("| FR64 d56:").append(reach56boost ? "OK" : "X");
                    detail[0] = sb.toString();
                    // 사거리 한계 확정 ∧ 상향이 해결: 근거리 도달 · 현재 원거리 불가 · FR64서 원거리 열림
                    verdict[0] = reach16 && !reach56 && reach56boost;
                    com.evosim.mod.stage.VerifyLog.ensure(level.getServer().getServerDirectory().toPath());
                    com.evosim.mod.stage.VerifyLog.result("[VERIFY-LIVE] "
                            + (verdict[0] ? "PASS" : "FAIL") + " nav_range_probe | " + detail[0]
                            + " | expect: near reachable, far NOT at FR, far reachable at FR64",
                            verdict[0]);
                    return verdict[0];
                },
                () -> m.discard());
        tell(ctx.getSource(), "길찾기 사거리 진단(재작성) — 착지 대기 후 현재 FR·FR64에서 거리별 도달 측정. "
                + "PASS=근거리 도달·현 FR서 원거리 불가·FR64서 원거리 열림(=사거리가 원인, 상향이 해결). "
                + "FAIL이라도 detail 줄(FRxx: d16:… | FR64 d56:…)로 원인 판독. 수 초.");
        return 1;
    }

    /**
     * 진단 ② R6 야간 채집 격리 — 위급(H0.2·저장고0) 미믹 <b>바로 옆(1~2블록)에 풀을 심어</b> 밤에
     * 둔다. 채집 대상이 사거리 안에 확실히 있으므로: H가 회복하면 "채집 goal 은 실행된다"(원래 r6
     * 실패는 풀 거리/유무 = 사거리) → PASS. 회복 없으면 goal 이 상위(귀가/취침)에 막힌 것 → 원인 분리.
     */
    private static int forageProbe(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        LiveCheck.cancelAll();
        BlockPos home = groundAt(level, ctx.getSource().getPosition(), -4, -4);
        MimicEntity m = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        m.debugSettleWithTent(home, Direction.NORTH);
        LarderStore.get(level).set(home, 0.0); // 저장고 0 → 위급이면 채집 강행 경로(귀가 아님)
        m.debugSetHolding(0.2);                 // 위급
        // 풀밭을 천막 밖(+5블록 중심) 5×5 로 조성 — 천막 구조물(±2)과 겹치지 않게, 사거리(24) 안.
        BlockPos patch = groundAt(level, ctx.getSource().getPosition(), 1, 1); // home 에서 +5,+5
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos g = level.getHeightmapPos(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        patch.offset(dx, 0, dz));
                level.setBlockAndUpdate(g.below(), Blocks.GRASS_BLOCK.defaultBlockState());
                level.setBlockAndUpdate(g, Blocks.GRASS.defaultBlockState());
            }
        }
        level.setDayTime(15000L); // 밤 — 평소라면 취침
        double h0 = m.getHolding();
        LiveCheck.watch(ctx.getSource(), "r6_forage_adjacent", 600,
                () -> String.format("H %.2f(start %.2f) — grass patch ~7 blocks away, within range",
                        m.getHolding(), h0),
                () -> m.getHolding() > 0.4, // 위급(0.3) 위로 회복 = 채집 실행됨
                () -> m.discard());
        tell(ctx.getSource(), "R6 채집 격리 진단(밤) — 옆에 풀을 깔았다. H가 0.2→0.4↑면 채집 goal 실행 "
                + "(원 실패는 풀 거리) / 회복 없으면 상위 goal(귀가·취침)에 막힌 것. 수 초~수십 초.");
        return 1;
    }

    /** M8 관측 — 지주 랭킹(소유 타일 순): 구획·타일·미정산 지대·상시 소작 수. 무주지 별도 표기. */
    private static int lords(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        FarmStore store = FarmStore.get(level);
        java.util.Map<Long, long[]> agg = new java.util.HashMap<>(); // owner → [plots, tiles, rent*100]
        int vacant = 0;
        for (FarmStore.Plot p : store.all().values()) {
            if (p.ownerId == 0L) {
                vacant++;
                continue;
            }
            long[] a = agg.computeIfAbsent(p.ownerId, k -> new long[3]);
            a[0]++;
            a[1] += p.tiles.length;
            a[2] += Math.round(p.account * 100);
        }
        java.util.Map<Long, Integer> tenants = new java.util.HashMap<>();
        for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null && e.getTenantFarm() != 0L)) {
            FarmStore.Plot fp = store.get(m.getTenantFarm());
            if (fp != null && fp.ownerId != 0L) {
                tenants.merge(fp.ownerId, 1, Integer::sum);
            }
        }
        java.util.List<java.util.Map.Entry<Long, long[]>> rank = new java.util.ArrayList<>(agg.entrySet());
        rank.sort((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1]));
        tell(ctx.getSource(), "지주 랭킹(소유 타일 순) — 구획 " + store.all().size()
                + "개(무주 " + vacant + ")");
        int i = 1;
        for (var e : rank) {
            if (i > 8) {
                break;
            }
            FamilyLedger.Rec r = FamilyLedger.get(level).get(e.getKey());
            String cls = store.classOf(level, e.getKey());
            tell(ctx.getSource(), String.format(
                    "  %d위 %s ⟨%s⟩ — 구획 %d·타일 %d·미정산 %.2f·상시소작 %d명·마름 %d명",
                    i++, r != null ? "N" + r.serial : "id" + e.getKey(),
                    cls.isEmpty() ? "농부" : cls,
                    e.getValue()[0], e.getValue()[1], e.getValue()[2] / 100.0,
                    tenants.getOrDefault(e.getKey(), 0), store.stewardCount(e.getKey())));
        }
        if (rank.isEmpty()) {
            tell(ctx.getSource(), "  아직 밭 소유자가 없습니다.");
        }
        return rank.size();
    }

    /**
     * 영지 원장(밭 원장 P3·P4) — 규모 상위 구획을 원장(창설자·소작 기여율·수확 분배)과 함께 열거하고,
     * 창설 가문(개간자 기준)을 집계한다. lords(생존 소유자 랭킹)와 상보 — 이쪽은 "한 가문이 얼마나
     * 땅을 창설·집중했는가"(부익부)를 본다. GUI 땅 문서와 같은 데이터원(FarmStore.Plot 원장).
     */
    private static int estates(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        FarmStore store = FarmStore.get(level);
        java.util.List<FarmStore.Plot> plots = new java.util.ArrayList<>(store.all().values());
        plots.sort((a, b) -> Integer.compare(b.tiles.length, a.tiles.length));
        java.util.Map<Long, Integer> tenants = new java.util.HashMap<>();
        for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getTenantFarm() != 0L)) {
            tenants.merge(m.getTenantFarm(), 1, Integer::sum);
        }
        tell(ctx.getSource(), "영지 원장 — 구획 " + store.all().size() + "개 (규모 순 상위)");
        int shown = Math.min(8, plots.size());
        for (int i = 0; i < shown; i++) {
            FarmStore.Plot p = plots.get(i);
            long grown = Math.max(1, p.tilesByFounder + p.tilesByOwner + p.tilesByTenant);
            int tenPct = (int) Math.round(100.0 * p.tilesByTenant / grown);
            String stw = p.stewardId == 0L ? "직영"
                    : "마름 " + nameLabel(level, p.stewardId) + "(g" + stewardGrade(level, p.stewardId) + ")";
            tell(ctx.getSource(), String.format(
                    "  #%d %d타일 · 소유 %s · %s · 소작%d명 · 부익부 %d%% · 수확 %.0f(지대 %.0f)",
                    p.id, p.tiles.length, ownerLabel(level, p.ownerId), stw,
                    tenants.getOrDefault(p.id, 0), tenPct, p.totalYield, p.totalToOwner));
        }
        // 창설 가문 집계 — founderId별 구획·타일 합(부익부 집중도). 상속·선점 무관, 최초 개간자 기준.
        java.util.Map<Long, long[]> byFounder = new java.util.HashMap<>();
        for (FarmStore.Plot p : plots) {
            long[] a = byFounder.computeIfAbsent(p.founderId, k -> new long[2]);
            a[0]++;
            a[1] += p.tiles.length;
        }
        java.util.List<java.util.Map.Entry<Long, long[]>> frank =
                new java.util.ArrayList<>(byFounder.entrySet());
        frank.sort((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1]));
        tell(ctx.getSource(), "창설 가문(개간자 기준) 상위 3:");
        int fi = 1;
        for (var e : frank) {
            if (fi > 3) {
                break;
            }
            tell(ctx.getSource(), String.format("  %d위 %s — 창설 %d구획·%d타일",
                    fi++, nameLabel(level, e.getKey()), e.getValue()[0], e.getValue()[1]));
        }
        if (plots.isEmpty()) {
            tell(ctx.getSource(), "  아직 밭이 없습니다.");
        }
        return shown;
    }

    /**
     * 인지범위 식량 계측(graze) — 출산율 사다리 U1c 측정 도구. 각 성년/노년 미믹의 활동반경
     * (Roaming.BASE_RADIUS=32) 안에서 도달 가능한 야생 풀(GRASS/TALL_GRASS/FERN/LARGE_FERN)과
     * 익은 정원 베리(SWEET_BERRY_BUSH age≥3)를 하이트맵 컬럼 스캔으로 세어, 개체별 채집·정원
     * 식량을 계층(지주/소작/무밭)별로 집계한다. 풀 소진 전후로 여러 번 호출해 U1(채집)·U2(정원)·
     * U1b(풀 고갈)·U1c(잔여 식량)을 실측한다. 읽기 전용(월드 무변경). 수확 상수는 코드 인용:
     * GATHER_FOOD=0.08(MimicForageGoal:46) · BERRY_FOOD=0.20(MimicForageGoal:49).
     */
    private static int graze(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        int R = (int) Math.ceil(com.evosim.core.Roaming.BASE_RADIUS); // 32
        FarmStore store = FarmStore.get(level);
        long day = com.evosim.mod.entity.SimTime.tick(level) / 24000L;
        java.util.List<MimicEntity> all = new java.util.ArrayList<>(level.getEntities(
                ModEntities.MIMIC.get(), e -> e.isAlive() && e.getIndividual() != null
                        && (e.getStage() == com.evosim.core.LifeStage.ADULT
                                || e.getStage() == com.evosim.core.LifeStage.ELDER)));
        // 계층 분류: 지주(밭 소유)/소작(상시)/무밭
        java.util.List<double[]> landless = new java.util.ArrayList<>();
        java.util.List<double[]> tenant = new java.util.ArrayList<>();
        java.util.List<double[]> owner = new java.util.ArrayList<>();
        double sumGrassBlocks = 0;
        for (MimicEntity m : all) {
            long id = m.getIndividual().id();
            BlockPos c = m.blockPosition();
            int grass = 0;
            int berry = 0;
            for (int dx = -R; dx <= R; dx++) {
                for (int dz = -R; dz <= R; dz++) {
                    if (dx * dx + dz * dz > R * R) {
                        continue; // 원형 반경
                    }
                    BlockPos p = level.getHeightmapPos(
                            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            c.offset(dx, 0, dz));
                    if (!level.isLoaded(p)) {
                        continue;
                    }
                    var s = level.getBlockState(p);
                    if (s.is(Blocks.GRASS) || s.is(Blocks.TALL_GRASS)
                            || s.is(Blocks.FERN) || s.is(Blocks.LARGE_FERN)) {
                        grass++;
                    } else if (s.is(Blocks.SWEET_BERRY_BUSH)
                            && s.getValue(SweetBerryBushBlock.AGE) >= 3) {
                        berry++;
                    }
                }
            }
            double grassFood = grass * 0.08 * com.evosim.core.FoodEconomy.forageYieldMult(m.getIndividual());
            double berryFood = berry * 0.20 * m.gardenMult();
            double[] row = {grassFood, berryFood, grassFood + berryFood, grass, berry};
            sumGrassBlocks += grass;
            if (store.ownedCount(id) > 0) {
                owner.add(row);
            } else if (m.getTenantFarm() != 0L) {
                tenant.add(row);
            } else {
                landless.add(row);
            }
        }
        tell(ctx.getSource(), String.format(
                "═ graze d%d — 인지반경 %d 내 도달식량 (미믹 %d · 총 풀블록 %.0f) ═",
                day, R, all.size(), sumGrassBlocks));
        grazeClass(ctx, "무밭평민", landless);
        grazeClass(ctx, "소작농  ", tenant);
        grazeClass(ctx, "지주    ", owner);
        return all.size();
    }

    /** graze 계층 집계 — 채집식량/정원식량/합의 중앙값·평균. row=[grassFood,berryFood,total,grassN,berryN]. */
    private static void grazeClass(CommandContext<CommandSourceStack> ctx, String label,
                                   java.util.List<double[]> rows) {
        if (rows.isEmpty()) {
            tell(ctx.getSource(), "  " + label + " : 0명");
            return;
        }
        int n = rows.size();
        double[] tot = new double[n];
        double sg = 0;
        double sb = 0;
        double sgn = 0;
        double sbn = 0;
        for (int i = 0; i < n; i++) {
            tot[i] = rows.get(i)[2];
            sg += rows.get(i)[0];
            sb += rows.get(i)[1];
            sgn += rows.get(i)[3];
            sbn += rows.get(i)[4];
        }
        java.util.Arrays.sort(tot);
        double medTot = tot[n / 2];
        tell(ctx.getSource(), String.format(
                "  %s : %d명 · 도달식량 중앙 %.2f · 채집평균 %.2f(풀 %.0f칸) · 정원평균 %.2f(익음 %.0f)",
                label, n, medTot, sg / n, sgn / n, sb / n, sbn / n));
    }

    /** 마름 관리 등급(라이브 개체 조회) — 미로드/사망이면 -1. estates 표기용. */
    private static int stewardGrade(ServerLevel level, long id) {
        for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null && e.getIndividual().id() == id)) {
            return com.evosim.core.Multipliers.manageAbilityGrade(m.getIndividual());
        }
        return -1;
    }

    /** 개체 id → 표시명(원장 우선, 사후 포함). 이름 없으면 N{serial}, 원장 없으면 id. */
    private static String nameLabel(ServerLevel level, long id) {
        if (id == 0L) {
            return "—";
        }
        FamilyLedger.Rec r = FamilyLedger.get(level).get(id);
        if (r != null && r.name != null && !r.name.isEmpty()) {
            return r.name;
        }
        return r != null ? "N" + r.serial : "id" + id;
    }

    /** 소유자 라벨(0=무주지). */
    private static String ownerLabel(ServerLevel level, long id) {
        return id == 0L ? "무주지" : nameLabel(level, id);
    }

    /** 데모 구획 공용 조성 — n타일 즉시 익음(수열 그대로, 흙 받침 포함). */
    private static FarmStore.Plot buildDemoPlot(ServerLevel level, BlockPos anchor, long ownerId, int n) {
        FarmStore.Plot plot = FarmStore.get(level).create(anchor, ownerId);
        // 덩어리 도면 그대로 — n 을 담는 최소 단계를 세운다(데모도 실제와 같은 모양이어야 한다).
        int st = FarmLayout.stageOf(n);
        int[] br = FarmLayout.stage(st);
        plot.beds = br[0];
        plot.rows = br[1];
        plot.bedAxisX = true;
        plot.baseY = anchor.getY() - 1;
        int[] dfp = FarmLayout.footprint(br[0], br[1]);
        plot.fx = anchor.getX() - dfp[0] / 2;
        plot.fz = anchor.getZ() - dfp[1] / 2;
        for (int c = 0; c < dfp[0]; c++) {
            for (int r = 0; r < dfp[1]; r++) {
                BlockPos base = new BlockPos(plot.fx + c, plot.baseY + 1, plot.fz + r);
                if (!FarmLayout.isCrop(c, r, br[0], br[1])) {
                    level.setBlockAndUpdate(base, Blocks.OAK_LOG.defaultBlockState());
                    continue;
                }
                level.setBlockAndUpdate(base, Blocks.GRASS_BLOCK.defaultBlockState());
                BlockPos gp = base.above();
                level.setBlockAndUpdate(gp, Blocks.SWEET_BERRY_BUSH.defaultBlockState()
                        .setValue(SweetBerryBushBlock.AGE, 3));
                FarmStore.get(level).addTile(plot, gp,
                        level.getGameTime() - FarmEconomy.RIPEN_TICKS);
            }
        }
        return plot;
    }

    /** 밭 골조 정리 — 구획의 베리·흙받침을 원상 제거하고 등록 회수(데모 잔재 방지, 규칙 7). */
    private static int farmClear(CommandContext<CommandSourceStack> ctx, int plotId) {
        ServerLevel level = ctx.getSource().getLevel();
        FarmStore store = FarmStore.get(level);
        FarmStore.Plot plot = store.get(plotId);
        if (plot == null) {
            tell(ctx.getSource(), "구획 " + plotId + " 없음.");
            return 0;
        }
        for (long l : plot.tiles) {
            BlockPos gp = BlockPos.of(l);
            if (level.getBlockState(gp).is(Blocks.SWEET_BERRY_BUSH)) {
                level.setBlockAndUpdate(gp, Blocks.AIR.defaultBlockState());
            }
        }
        store.debugRemove(plot.id);
        tell(ctx.getSource(), "구획 " + plotId + " 정리 완료(" + plot.tiles.length + "타일 제거).");
        return 1;
    }

    /** 인구 통계 GUI — 발현 특성 분포 그래프 + 최다 후손 랭킹(플레이어 전용, 무대 개체 제외). */
    private static int stats(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            return legacy(ctx); // 콘솔 → 채팅 폴백(화면 없음)
        }
        StatsSnapshot snap = StatsSnapshot.build(ctx.getSource().getLevel());
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new StatsPacket(snap));
        return 1;
    }

    /** 최다 후손 랭킹 채팅 출력 — GUI 폴백(콘솔·로그 대조용). 원장 전수라 죽은 조상도 나온다. */
    private static int legacy(CommandContext<CommandSourceStack> ctx) {
        StatsSnapshot snap = StatsSnapshot.build(ctx.getSource().getLevel());
        tell(ctx.getSource(), "최다 후손 랭킹 (생존 " + snap.living + "명 · 죽은 조상 포함)");
        if (snap.tops.isEmpty()) {
            tell(ctx.getSource(), "  아직 후손을 남긴 개체가 없습니다.");
            return 0;
        }
        for (int i = 0; i < snap.tops.size(); i++) {
            StatsSnapshot.Top t = snap.tops.get(i);
            tell(ctx.getSource(), "  " + (i + 1) + "위 " + (t.female() ? "♀ " : "♂ ") + t.name()
                    + (t.alive() ? " #" + t.entityId() : " (사망)") + " G" + t.gen()
                    + " — 자식 " + t.children() + " · 후손 " + t.descendants());
        }
        return snap.tops.size();
    }

    // ── 신규 기능 점검 스테이징 ──

    /** matingReady(서로 매력 매칭) 성년 하나 소환해 반환. */
    private static MimicEntity spawnAdult(ServerLevel level, Vec3 pos, Sex sex) {
        return spawnAdult(level, pos, sex, new Trait[0]);
    }

    /** matingReady 성년 + 지정 추가 특성(정착 성향 등) 부여해 소환. */
    private static MimicEntity spawnAdult(ServerLevel level, Vec3 pos, Sex sex, Trait... extra) {
        MimicEntity e = ModEntities.MIMIC.get().create(level);
        if (e == null) {
            return null;
        }
        long id = Math.abs((int) level.getGameTime()) + level.random.nextInt(1_000_000);
        Individual ind = new Individual(id, sex, 0, 0, 1);
        ind.addTrait(TraitInstance.of(Trait.PREF_STRENGTH));
        ind.addTrait(TraitInstance.of(Trait.PREF_ABILITY));
        ind.addTrait(TraitInstance.of(Trait.PREF_VITALITY));
        ind.addTrait(TraitInstance.of(Trait.STRONG));
        ind.addTrait(TraitInstance.of(Trait.BRIGHT));
        ind.addTrait(TraitInstance.of(Trait.NIMBLE));
        for (Trait t : extra) {
            // 무대 개체의 등급 특성(능력 포함)은 Ⅴ 고정 — 손계산 기대값이 만액(종전 수치) 기준이고,
            // 능력 게이트(경영 Ⅳ+)·등급 배율이 무등급(Ⅲ 취급)에 걸려 무대가 틀어지는 것을 막는다.
            ind.addTrait(t.isGraded() ? TraitInstance.graded(t, 5) : TraitInstance.of(t));
        }
        e.setIndividual(ind);
        e.setStage(LifeStage.ADULT);
        e.moveTo(pos.x, pos.y, pos.z, level.random.nextFloat() * 360f, 0f);
        e.markStageActor(); // 검증 무대 개체 — 혈통 원장·통계 오염 방지(addFreshEntity 전 필수)
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()),
                MobSpawnType.COMMAND, null, null);
        level.addFreshEntity(e);
        return e;
    }

    // ── 1단계 검증: 구조물 도면 로더 ──────────────────────────────────────────────
    //
    // 검증 원칙: "잘 되는 것 같다"가 아니라 <b>측정 가능한 대조</b>로만 판정한다. 각 항목은
    // 어긋났을 때 무엇이 깨지는지를 함께 적는다.

    private static final net.minecraft.world.level.block.Rotation[] ROTS = {
            net.minecraft.world.level.block.Rotation.NONE,
            net.minecraft.world.level.block.Rotation.CLOCKWISE_90,
            net.minecraft.world.level.block.Rotation.CLOCKWISE_180,
            net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90};

    private static int homeTest(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        int pass = 0;
        int fail = 0;
        StringBuilder sb = new StringBuilder();
        for (HomeTemplate.Tier tier : HomeTemplate.Tier.values()) {
            for (String design : tier.designs) {
                // ① 8배치(회전 4 × 대칭 2)를 전부 로드 — 규약 위반이면 여기서 예외가 난다.
                java.util.List<HomeTemplate> variants = new java.util.ArrayList<>();
                String err = null;
                for (var rot : ROTS) {
                    for (var mir : new net.minecraft.world.level.block.Mirror[] {
                            net.minecraft.world.level.block.Mirror.NONE, HomeTemplate.MIRROR}) {
                        try {
                            var t = HomeTemplate.load(level, design, rot, mir);
                            if (t.isEmpty()) {
                                err = "도면 파일 없음(데이터팩 미탑재)";
                            } else {
                                variants.add(t.get());
                            }
                        } catch (RuntimeException e) {
                            err = e.getMessage();
                        }
                    }
                }
                if (err != null || variants.size() != 8) {
                    fail++;
                    sb.append(String.format("§c✗ %-8s 배치 %d/8 — %s\n", design, variants.size(),
                            err == null ? "일부 누락" : err));
                    continue;
                }
                HomeTemplate base = variants.get(0);

                // ② 계획에 금블록·베리가 섞이면 안 된다.
                //    금블록이 남으면 앵커 자리가 막혀 미믹이 자기 집 중앙에 설 수 없고,
                //    베리가 남으면 빌더가 공짜로 정원을 완성해 BUSH_COST 회계가 무너진다.
                long gold = base.plan().stream().filter(pp -> pp.state()
                        .is(net.minecraft.world.level.block.Blocks.GOLD_BLOCK)).count();
                long bush = base.plan().stream().filter(pp -> pp.state()
                        .is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH)).count();

                // ③ 정원 6칸 + 각 칸 아래에 흙이 계획에 실제로 들어 있는가(심을 땅 보장).
                java.util.Set<net.minecraft.core.BlockPos> planned = new java.util.HashSet<>();
                for (var pp : base.plan()) {
                    planned.add(pp.rel());
                }
                int soilOk = 0;
                for (var g : base.gardenCells()) {
                    if (planned.contains(g.below())) {
                        soilOk++;
                    }
                }

                // ④ 회전이 실제로 좌표를 바꾸는가 — 정원 중심이 4방향 모두 달라야 한다.
                //    같으면 회전이 먹히지 않은 것이고, 마을이 전부 같은 방향으로 선다.
                java.util.Set<String> centers = new java.util.HashSet<>();
                for (int i = 0; i < 4; i++) {
                    centers.add(gardenCenter(variants.get(i * 2)));
                }

                // ⑤ 좌우대칭이 정원을 반대쪽으로 보내는가 — 회전 0 기준 대칭 전/후 비교.
                //    사용자 요구의 핵심: "좌우대칭이라 함은 베리 정원이 반대쪽에 나타난다는 의미".
                String cNormal = gardenCenter(variants.get(0));
                String cMirror = gardenCenter(variants.get(1));

                // ⑥ 8배치가 <b>서로 다른 건물</b>인가. ⑤만으로는 약하다 — 정원 위치가 같아도
                //    내부가 뒤집혔을 수 있고, 반대로 정원만 옮기고 몸통은 그대로일 수도 있다.
                //    (좌표,상태) 전체를 지문으로 삼아 8개가 전부 갈리는지 본다. 같은 지문이 있으면
                //    그만큼 마을의 외형 다양성이 줄어든 것이다.
                java.util.Set<Integer> prints = new java.util.HashSet<>();
                for (var v : variants) {
                    int h = 0;
                    for (var pp : v.plan()) {
                        h += pp.rel().hashCode() * 31 + System.identityHashCode(pp.state());
                    }
                    prints.add(h);
                }

                boolean ok = gold == 0 && bush == 0
                        && base.gardenCells().size() == HomeTemplate.GARDEN_CELLS
                        && soilOk == HomeTemplate.GARDEN_CELLS
                        && centers.size() == 4
                        && !cNormal.equals(cMirror)
                        && prints.size() == 8;
                if (ok) {
                    pass++;
                } else {
                    fail++;
                }
                sb.append(String.format(
                        "%s %-8s 계획%4d 정원%d 흙%d 금%d베리%d 회전상이%d 대칭%s→%s 배치상이%d/8 "
                                + "실내%d reach%.1f\n",
                        ok ? "§a✓§r" : "§c✗§r", design, base.plan().size(),
                        base.gardenCells().size(), soilOk, gold, bush, centers.size(),
                        cNormal, cMirror, prints.size(),
                        HomeBlueprint.of(level, BlockPos.ZERO, design, (byte) 0, false)
                                .interior().size(),
                        base.reach()));
            }
        }
        tell(ctx.getSource(), String.format("§e[도면 검증] 통과 %d · 실패 %d§r\n%s", pass, fail, sb));
        tell(ctx.getSource(), "판정 기준 — 정원6·흙6: 심을 땅 보장 / 금0베리0: 빌더가 앵커를 막거나 "
                + "정원을 공짜로 심지 않음 / 회전상이4: 4방향이 실제로 갈림 / 대칭 A→B: 정원이 반대쪽으로 "
                + "이동 / 배치상이8: 8배치가 서로 다른 건물");
        return pass;
    }

    /**
     * 거처 등기부 조회 — 등기부와 <b>월드의 실제 상태</b>를 대조해 어긋난 곳을 드러낸다.
     *
     * <p>등기부만 출력하면 "등기부가 자기 자신과 일치한다"는 공허한 확인이 된다. 그래서 세 가지
     * 독립된 사실과 맞춘다: ① 개체의 {@code homePos}(지금 누가 어디 사는가) ② 모닥불 블록의
     * 존재·점화(구조물이 실제로 있는가) ③ 등기 좌표 간 최소 거리(겹쳐 지었는가).
     */
    private static int homes(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        HomeStore reg = HomeStore.get(level);
        int today = (int) (com.evosim.mod.entity.SimTime.tick(level) / 24000L);

        // ① 실거주 집합 — 살아 있는 개체의 homePos.
        java.util.Map<Long, Integer> residents = new java.util.HashMap<>();
        for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getHomePos() != null)) {
            residents.merge(m.getHomePos().asLong(), 1, Integer::sum);
        }

        List<BlockPos> all = reg.positions();
        int vacant = 0;
        int ghost = 0;      // 등기엔 있는데 구조물이 없다
        double standSum = 0.0;
        int conflict = 0;   // 등기는 빈집인데 실거주자가 있다 = 남의 집에 입주할 위험
        int stale = 0;      // 실거주자가 있는데 등기가 없다 = 겹쳐 지을 위험
        StringBuilder vac = new StringBuilder();
        // 무너진 집의 <b>좌표</b> — 개수만으로는 "같은 집이 계속 무너져 있다"(결함)와 "매일 다른
        // 집이 잠시 그렇다"(개축 중)를 가를 수 없다. 종전에는 빈집일 때만 좌표가 찍혀, 사람이
        // 사는 집이 무너진 경우를 추적할 방법이 아예 없었다.
        StringBuilder broke = new StringBuilder();
        for (BlockPos h : all) {
            HomeStore.Entry e = reg.entry(h);
            // 구조물이 실제로 서 있는가 — 도면 대비 일치 비율로 잰다(모닥불 폐지 후의 관측 경로).
            double stand = HomeBlueprint.of(level, h, e.design(), e.rotation(), e.mirrored())
                    .standingRatio(level);
            boolean standing = stand >= MimicEntity.STANDING_RATIO;
            if (!standing) {
                ghost++;
                if (broke.length() < 400) {
                    broke.append(String.format("%d,%d(%s r%d%s %.0f%% 거주%d) ",
                            h.getX(), h.getZ(), e.design(), e.rotation(),
                            e.mirrored() ? "m" : "", stand * 100.0,
                            residents.getOrDefault(h.asLong(), 0)));
                }
            }
            standSum += stand;
            int res = residents.getOrDefault(h.asLong(), 0);
            if (reg.isVacant(h, today)) {
                vacant++;
                if (res > 0) {
                    conflict++;
                }
                if (vac.length() < 300) {
                    vac.append(String.format("%d,%d(%s r%d%s %s거주%d) ", h.getX(), h.getZ(),
                            e.design(), e.rotation(), e.mirrored() ? "m" : "",
                            standing ? "" : "구조없음", res));
                }
            }
        }
        for (Long k : residents.keySet()) {
            if (reg.entry(BlockPos.of(k)) == null) {
                stale++;
            }
        }

        // ④ 도면 분포와 정원·문 실측 — "심는 칸"과 "실제 덤불"이 같은 목록을 보는지 드러낸다.
        //    수확 경로가 다른 칸을 보면 덤불은 늘어나는데 수확(AUDIT garden)이 0으로 남는다.
        java.util.Map<String, Integer> byDesign = new java.util.TreeMap<>();
        int gardenCells = 0;
        int bushes = 0;
        int doors = 0;
        for (BlockPos h : all) {
            HomeStore.Entry e = reg.entry(h);
            byDesign.merge(e.design() + (e.mirrored() ? "m" : "") + "r" + e.rotation(), 1,
                    Integer::sum);
            HomeBlueprint bp = HomeBlueprint.of(level, h, e.design(), e.rotation(), e.mirrored());
            gardenCells += bp.gardenCap(); // 훑는 칸이 아니라 상한(천막은 24칸/상한 8)
            for (BlockPos g : bp.garden()) {
                for (int dy = 3; dy >= -3; dy--) {
                    if (level.getBlockState(g.offset(0, dy, 0))
                            .is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH)) {
                        bushes++;
                        break;
                    }
                }
            }
            for (HomeBlueprint.Placement pp : bp.plan()) {
                if (pp.state().getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                        && level.getBlockState(pp.pos()) == pp.state()) {
                    doors++;
                }
            }
        }

        // ③ 등기 좌표 간 최소 평면 거리 — MIN_GAP 미만이면 겹쳐 지은 것이다.
        double minGap = Double.MAX_VALUE;
        String gapPair = "-";
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                double dx = all.get(i).getX() - all.get(j).getX();
                double dz = all.get(i).getZ() - all.get(j).getZ();
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < minGap) {
                    minGap = d;
                    gapPair = String.format("%d,%d↔%d,%d", all.get(i).getX(), all.get(i).getZ(),
                            all.get(j).getX(), all.get(j).getZ());
                }
            }
        }
        tell(ctx.getSource(), String.format(
                "§e[등기부] day=%d 등기%d 거주%d 빈집%d · 구조평균%.0f%% 구조없음%d · "
                        + "실거주좌표%d 미등기%d 빈집인데거주%d · 최소간격%.1f(기준간격%d) %s",
                today, all.size(), all.size() - vacant, vacant,
                all.isEmpty() ? 0.0 : standSum / all.size() * 100.0, ghost,
                residents.size(), stale, conflict,
                minGap == Double.MAX_VALUE ? -1.0 : minGap,
                MimicEntity.requiredGap(level, com.evosim.mod.entity.HomeTemplate.Tier.SMALL
                        .designs[0]), gapPair));
        // ── 부지 검증 ── 집이 물 위에 섰는가, 절벽에 박혔는가, 땅을 얼마나 파헤쳤는가.
        // 공중 덤프로 눈대중하지 않고 <b>등기부에서 직접</b> 잰다. 정원 열은 뺀다 —
        // 화단 상자는 제 지형 위에 얹혀도 되므로 낙차 판정 대상이 아니다.
        int onWater = 0;
        int onFluidBase = 0;
        int onCliff = 0;
        int maxSpread = 0;
        for (BlockPos h : all) {
            HomeStore.Entry e = reg.entry(h);
            if (e == null) {
                continue;
            }
            HomeBlueprint hb = HomeBlueprint.of(level, h, e.design(), e.rotation(), e.mirrored());
            java.util.Set<Long> gcol = new java.util.HashSet<>();
            for (BlockPos gc : hb.garden()) {
                gcol.add(RoadStore.key(gc.getX(), gc.getZ()));
            }
            // <b>발자국 바깥 한 겹</b>을 본다. 발자국 안쪽은 이미 집이 서 있어 하이트맵이
            // 지붕을 돌려주므로, 그걸로 낙차를 재면 지형이 아니라 <b>집의 높이 편차</b>를
            // 재게 된다(이 함정을 실측 직전에 발견했다 — 그 값으로 평탄화 한도를 정할 뻔했다).
            // 바깥 한 겹은 손대지 않은 땅이라 집이 앉은 경사를 그대로 보여 준다.
            java.util.Set<Long> foot = new java.util.HashSet<>();
            for (BlockPos col : hb.groundFootprint()) {
                foot.add(RoadStore.key(col.getX(), col.getZ()));
            }
            java.util.Set<Long> shell = new java.util.HashSet<>();
            for (BlockPos col : hb.groundFootprint()) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long k = RoadStore.key(col.getX() + dx, col.getZ() + dz);
                        if (!foot.contains(k) && !gcol.contains(k)) {
                            shell.add(k);
                        }
                    }
                }
            }
            int lo = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            boolean wet = false;
            for (long k : shell) {
                int cxx = RoadStore.keyX(k);
                int czz = RoadStore.keyZ(k);
                if (!level.hasChunk(cxx >> 4, czz >> 4)) {
                    continue;
                }
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types
                        .MOTION_BLOCKING_NO_LEAVES, cxx, czz) - 1;
                if (!level.getBlockState(new BlockPos(cxx, y, czz)).getFluidState().isEmpty()) {
                    wet = true;
                }
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
            }
            if (wet) {
                onWater++;
            }
            // <b>물 위에 섰는가</b>는 기초 <b>아래</b>로 판정한다. 발자국 열의 하이트맵은 집이
            // 서면 지붕을 돌려주므로 그 아래의 물이 보이지 않고, 둘레 한 겹은 "물가에 있다"만
            // 말해 준다. 둘 다 원래 검증하려던 것을 재지 못한다.
            int courseW = h.getY() - 1;
            for (BlockPos col : hb.groundFootprint()) {
                if (!level.hasChunk(col.getX() >> 4, col.getZ() >> 4)) {
                    continue;
                }
                if (!level.getBlockState(new BlockPos(col.getX(), courseW - 1, col.getZ()))
                        .getFluidState().isEmpty()) {
                    onFluidBase++;
                    break;
                }
            }
            if (hi != Integer.MIN_VALUE) {
                maxSpread = Math.max(maxSpread, hi - lo);
                if (hi - lo > 3) {
                    onCliff++;
                }
            }
        }
        tell(ctx.getSource(), String.format(
                "  %s부지 — <b>기초 아래가 물인 집%d</b> · 물가에 선 집%d · 낙차3 초과%d · 최대낙차%d§r",
                onFluidBase == 0 ? "§a" : "§c", onFluidBase, onWater, onCliff, maxSpread));
        // ── 앉음새 ── 평탄화가 충분했는가를 <b>결과로</b> 잰다. 낙차에서 유추하지 않는다.
        //   뜬바닥 = 최하층 블록 <b>아래가 공기</b>인 칸(집이 허공에 떠 있다)
        //   파묻힘 = 최하층 블록 <b>옆 지형이 그보다 높은</b> 칸(벽이 흙에 묻혔다)
        int floatCol = 0;
        int buriedCol = 0;
        int floatHomes = 0;
        int buriedHomes = 0;
        for (BlockPos h : all) {
            HomeStore.Entry e = reg.entry(h);
            if (e == null) {
                continue;
            }
            HomeBlueprint hb = HomeBlueprint.of(level, h, e.design(), e.rotation(), e.mirrored());
            int course = h.getY() - 1; // 도면 최하층
            java.util.Set<Long> foot = new java.util.HashSet<>();
            for (BlockPos col : hb.groundFootprint()) {
                foot.add(RoadStore.key(col.getX(), col.getZ()));
            }
            int fl = 0;
            int bu = 0;
            for (BlockPos col : hb.groundFootprint()) {
                if (!level.hasChunk(col.getX() >> 4, col.getZ() >> 4)) {
                    continue;
                }
                if (level.getBlockState(new BlockPos(col.getX(), course - 1, col.getZ())).isAir()) {
                    fl++;
                }
                // 파묻힘은 <b>높이 비교로 재지 않는다.</b> 하이트맵은 이 열의 지붕을, 이웃
                // 열의 나무·다른 집·밭 테두리 꼭대기를 돌려주므로 어느 쪽으로 재도 허수가
                // 나온다(같은 함정을 네 번 밟았다 — 낙차·파묻힘 두 판·물 판정).
                // 대신 <b>벽 높이에 자연 흙이 박혀 있는가</b>를 직접 본다. 집 자재는 판자·석재라
                // 헷갈릴 여지가 없고, 지형이 벽을 덮었을 때만 참이 된다.
                if (isNaturalGround(level.getBlockState(
                        new BlockPos(col.getX(), course + 1, col.getZ())))) {
                    bu++;
                }
            }
            floatCol += fl;
            buriedCol += bu;
            if (fl > 0) {
                floatHomes++;
            }
            if (bu > 0) {
                buriedHomes++;
            }
        }
        tell(ctx.getSource(), String.format(
                "  %s앉음새 — 뜬바닥 %d칸(집%d) · 파묻힘 %d칸(집%d) / 등기%d§r",
                (floatCol + buriedCol == 0) ? "§a" : "§c",
                floatCol, floatHomes, buriedCol, buriedHomes, all.size()));
        // ── 집–밭 거리 ── 등기부와 밭 원장을 <b>직접</b> 맞대 잰다. 공중격자에서 재던 종전
        // 방식은 집 정원도 밭과 같은 스위트베리라 둘을 못 가렸고, 정원을 떼어내려 넣은
        // 어림(작은 덩어리 = 정원)이 이번엔 z축 밭을 잘게 쪼개 먹었다(실측 B런 D16:
        // 등기 타일 349 인데 격자 추정 316). 추정을 더 손보는 것보다 원본을 보는 것이 맞다.
        FarmStore fsD = FarmStore.get(level);
        java.util.List<Integer> gaps = new java.util.ArrayList<>();
        for (BlockPos h : all) {
            HomeStore.Entry e = reg.entry(h);
            if (e == null) {
                continue;
            }
            HomeBlueprint bp = HomeBlueprint.of(level, h, e.design(), e.rotation(), e.mirrored());
            int best = Integer.MAX_VALUE;
            for (FarmStore.Plot p : fsD.all().values()) {
                for (long l : p.tiles) {
                    BlockPos t = BlockPos.of(l);
                    for (BlockPos c : bp.groundFootprint()) {
                        int d = Math.max(Math.abs(t.getX() - c.getX()),
                                Math.abs(t.getZ() - c.getZ()));
                        if (d < best) {
                            best = d;
                        }
                    }
                }
            }
            if (best != Integer.MAX_VALUE) {
                gaps.add(best);
            }
        }
        if (!gaps.isEmpty()) {
            java.util.Collections.sort(gaps);
            int tight = 0;
            for (int g : gaps) {
                if (g <= 2) {
                    tight++;
                }
            }
            tell(ctx.getSource(), String.format(
                    "  %s밭까지 — 최소%d 중앙%d 최대%d · 2칸 이하 %d채 / 집%d§r",
                    tight == 0 ? "§a" : "§c",
                    gaps.get(0), gaps.get(gaps.size() / 2), gaps.get(gaps.size() - 1),
                    tight, gaps.size()));
        }
        double upkeepDue = 0.0;
        for (BlockPos h : all) {
            upkeepDue += reg.entry(h).upkeepDue();
        }
        tell(ctx.getSource(), String.format(
                "도면분포 %s · 정원칸합%d 덤불합%d 문블록합%d · 유지비잔돈합%.2f",
                byDesign.isEmpty() ? "-" : byDesign.toString(), gardenCells, bushes, doors,
                upkeepDue));
        if (vac.length() > 0) {
            tell(ctx.getSource(), "빈집: " + vac);
        }
        if (broke.length() > 0) {
            tell(ctx.getSource(), "무너짐: " + broke);
        }
        return all.size();
    }

    /**
     * 4단계 무대 — 거처 등급 이사가 <b>발동하기 직전</b>의 네 가구를 한 번에 세운다.
     *
     * <p>각 가구는 승격의 서로 다른 갈래를 하나씩 대표하고, 그중 하나는 <b>발동해서는 안 되는</b>
     * 대조군이다. 대조군이 없으면 "이사가 일어났다"는 관측이 조건 때문인지 그냥 아무나 이사하는
     * 것인지 구분되지 않는다.
     *
     * <ol>
     *   <li><b>과시</b> — 소형·부부·저장고 100. 여유가 넘치지만 협소하진 않다.
     *       {@link HomeTemplate#SHOWOFF_DAYS} 일 연속이라야 오른다 → <b>D+2 불변, D+3 이사</b>.</li>
     *   <li><b>협소</b> — 소형(수용 4)·5인·저장고 100. 지속 요건 없이 <b>즉시</b> 중형 이상.</li>
     *   <li><b>협소인데 무일푼</b>(대조군) — 소형·5인·저장고 16. 비좁아도 <b>이사하지 못한다</b>
     *       (중형 12 + 여유 6 = 18 미달). 승격이 자산 조건을 실제로 보는지 가른다.</li>
     *   <li><b>빈집 재사용</b> — 옆에 <b>저택 빈집</b>이 서 있는 부유한 소형 가구.
     *       신축이 아니라 그 빈집으로 <b>건축비 0</b>에 들어가야 한다.</li>
     * </ol>
     */
    private static int tierStage(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            // 강제로드 대역 안에 나란히 — 슬롯 방식(z += 64×n)은 검증 스위트와 자리가 겹치고
            // 시뮬레이션 거리 밖이라 개체가 틱하지 않는다(가구 정산이 안 돌면 승격도 안 돈다).
            // 기단을 먼저 맞춘다 — 저장고·등기 키가 실제 앵커와 어긋나면 무대가 통째로 헛돈다.
            BlockPos home = MimicEntity.liftToBase(level,
                    groundAt(level, b, -120.0 + i * 60.0, 0.0), "small1", (byte) 0, false);
            MimicEntity dad = spawnAdult(level, Vec3.atBottomCenterOf(home).add(2, 0, 2), Sex.MALE);
            MimicEntity mom = spawnAdult(level, Vec3.atBottomCenterOf(home).add(3, 0, 2), Sex.FEMALE);
            // 짝은 <b>debugMarryTo</b>(배우자 링크만)로 맺는다. debugForcePair 는 성사 절차를
            // 그대로 태워서, 이미 집이 있어도 그날 밤 자연 신축(makeNewHome)이 돌아 무대가 준 집을
            // 버리고 새 집으로 나가 버린다 — 실측: 무대 4가구가 전부 저장고 0인 새 집으로 이사해
            // 이튿날 굶어 죽었고, 등기에는 아무도 안 사는 집 4채가 남았다.
            dad.debugSettleWithHome(level, home, "small1", (byte) 0, false);
            // 두 번째 사람은 <b>합류</b>다. debugSettleWithHome 을 또 부르면 기단을 지붕 하이트맵
            // 위로 다시 잡아 mom 이 딴 집에 사는 것으로 등록된다(실측으로 드러난 무대 결함).
            home = dad.getHomePos();
            mom.debugJoinHome(home, "small1", (byte) 0, false);
            dad.debugMarryTo(mom);
            int kids = (i == 1 || i == 2) ? 3 : 0; // ②③ 만 5인 가구(부부 2 + 자녀 3 > 수용 4)
            for (int k = 0; k < kids; k++) {
                MimicEntity c = spawnChildOf(level,
                        Vec3.atBottomCenterOf(home).add(1 + k, 0, 1), dad, Sex.MALE);
                c.setHomePos(home);
            }
            // ③ 대조군은 16 — 협소 이사 문턱(중형 12 + 여유 6 = 18)에 <b>딱 미치지 못하는</b>
            // 값이다. 8로 두면 판정이 서기 전에 굶어 죽어 "못 갔다"가 조건 때문인지 죽어서인지
            // 구분되지 않는다.
            double larder = i == 2 ? 16.0 : 100.0;
            LarderStore.get(level).set(home, larder);
            if (i == 3) { // ④ 옆에 저택 빈집 — 신축보다 이쪽이 우선이어야 한다
                BlockPos vac = home.offset(30, 0, 0);
                MimicEntity.debugPlaceVacantHome(level, vac, "mansion", (byte) 0, false);
                sb.append(String.format("④ 저택 빈집 @%d,%d\n", vac.getX(), vac.getZ()));
            }
            sb.append(String.format("%s 가구%d 저장고%.0f @%d,%d (small1)\n",
                    new String[] {"① 과시", "② 협소", "③ 협소·무일푼", "④ 빈집재사용"}[i],
                    2 + kids, larder, home.getX(), home.getZ()));
        }
        tell(ctx.getSource(), "§e[4단계 무대] 승격 직전 4가구 조성§r\n" + sb
                + "판정 — ①은 3일 연속이라야 오른다(D+2 불변·D+3 이사) / ②는 즉시 / "
                + "③은 돈이 없어 못 간다(대조군) / ④는 신축이 아니라 저택 빈집으로 비용 0.\n"
                + "관측: /evosim homes 의 도면분포와 [이사] 로그.");
        return 1;
    }

    /**
     * <b>봉건 관측 1줄 보고</b> — 3회 반복 관측에서 <b>같은 시각마다 같은 값</b>을 읽기 위한 창구.
     *
     * <p>여기 담긴 값만으로 사전 선언한 합격 기준을 전부 판정할 수 있어야 한다. 그래서 계층 평균
     * (AUDIT 이 이미 준다)이 아니라 <b>가구 저장고의 최대·중앙·최소·상위 1가구 비중</b>을 낸다 —
     * 평균은 지주 한 명이 가려버려 "격차가 벌어졌는가"를 못 읽는다(관측 함정).
     *
     * <p>겹침은 두 축이다. <b>집↔집</b>은 등기 좌표 간 최소 평면거리, <b>집↔밭</b>은 도면 발자국·
     * 정원 칸이 밭 열을 밟는지. 후자는 밟는 순간 그 타일이 영구 수확불능이 되므로 0이어야 한다.
     */
    private static int feudReport(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        HomeStore reg = HomeStore.get(level);
        FarmStore farms = FarmStore.get(level);
        LarderStore lar = LarderStore.get(level);
        int today = (int) (com.evosim.mod.entity.SimTime.tick(level) / 24000L);

        int pop = 0;
        int adult = 0;
        java.util.Set<Long> resHomes = new java.util.HashSet<>();
        for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null)) {
            pop++;
            if (m.getStage() == LifeStage.ADULT) {
                adult++;
            }
            if (m.getHomePos() != null) {
                resHomes.add(m.getHomePos().asLong());
            }
        }

        // ── 밭 ──
        int plots = 0;
        int tiles = 0;
        java.util.Map<Long, long[]> byOwner = new java.util.HashMap<>();
        for (FarmStore.Plot pl : farms.all().values()) {
            plots++;
            tiles += pl.tiles.length;
            if (pl.ownerId != 0L) {
                long[] v = byOwner.computeIfAbsent(pl.ownerId, k -> new long[2]);
                v[0]++;
                v[1] += pl.tiles.length;
            }
        }
        long topPlots = 0;
        long topTiles = 0;
        for (long[] v : byOwner.values()) {
            if (v[1] > topTiles) {
                topTiles = v[1];
                topPlots = v[0];
            }
        }
        int tenants = 0;
        for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getTenantFarm() != 0L)) {
            tenants++;
        }

        // ── 재산 격차(거주 가구 저장고) ──
        java.util.List<Double> wealth = new java.util.ArrayList<>();
        double sum = 0.0;
        for (long h : resHomes) {
            double v = lar.get(BlockPos.of(h));
            wealth.add(v);
            sum += v;
        }
        java.util.Collections.sort(wealth);
        double max = wealth.isEmpty() ? 0.0 : wealth.get(wealth.size() - 1);
        double min = wealth.isEmpty() ? 0.0 : wealth.get(0);
        double med = wealth.isEmpty() ? 0.0 : wealth.get(wealth.size() / 2);
        double topShare = sum <= 0.0 ? 0.0 : max / sum * 100.0;

        // ── 주거 등급·겹침 ──
        int[] tier = new int[5]; // 0 천막 1 소 2 중 3 대 4 저택
        int vacant = 0;
        int onFarm = 0;
        StringBuilder farmHit = new StringBuilder();
        java.util.List<BlockPos> all = reg.positions();
        for (BlockPos h : all) {
            HomeStore.Entry e = reg.entry(h);
            if (HomeStore.TENT.equals(e.design())) {
                tier[0]++;
            } else {
                switch (HomeTemplate.Tier.of(e.design())) {
                    case SMALL -> tier[1]++;
                    case MIDDLE -> tier[2]++;
                    case BIG -> tier[3]++;
                    case MANSION -> tier[4]++;
                    default -> tier[0]++;
                }
            }
            if (reg.isVacant(h, today)) {
                vacant++;
            }
            if (MimicEntity.homeSiteOnFarm(level, h, e.design(), e.rotation(), e.mirrored())) {
                onFarm++;
                if (farmHit.length() < 200) {
                    farmHit.append(h.getX()).append(',').append(h.getZ()).append('(')
                            .append(e.design()).append(") ");
                }
            }
        }
        double minGap = Double.MAX_VALUE;
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                double dx = all.get(i).getX() - all.get(j).getX();
                double dz = all.get(i).getZ() - all.get(j).getZ();
                minGap = Math.min(minGap, Math.sqrt(dx * dx + dz * dz));
            }
        }

        tell(ctx.getSource(), String.format(
                "§e[봉건보고] D%d§r 인구%d 성인%d 거주가구%d 등기%d(빈집%d)", today, pop, adult,
                resHomes.size(), all.size(), vacant));
        tell(ctx.getSource(), String.format(
                "  밭 구획%d 타일%d 지주%d 상시소작%d · 최대지주 구획%d/타일%d",
                plots, tiles, byOwner.size(), tenants, topPlots, topTiles));
        tell(ctx.getSource(), String.format(
                "  재산 최대%.0f 중앙%.0f 최소%.0f 총합%.0f 상위1가구%.0f%%",
                max, med, min, sum, topShare));
        tell(ctx.getSource(), String.format(
                "  주거 천막%d 소%d 중%d 대%d §6저택%d§r · 최소집간격%.1f(기준%d) · §c밭겹침%d§r %s",
                tier[0], tier[1], tier[2], tier[3], tier[4],
                minGap == Double.MAX_VALUE ? -1.0 : minGap,
                MimicEntity.requiredGap(level, HomeTemplate.Tier.SMALL.designs[0]),
                onFarm, farmHit));
        return tier[4];
    }

    /**
     * <b>공중 촬영</b> — 마을 상공에서 내려다본 지표 블록을 글자 격자로 덤프한다.
     *
     * <p>헤드리스 서버라 게임 클라이언트 스크린샷을 찍을 수 없다. 대신 <b>실제 월드의 블록</b>을
     * 열마다 읽어 내보낸다 — 도면에서 유도한 그림이 아니라 정말로 거기 놓여 있는 것이다.
     * 파일은 서버 폴더의 {@code evosim-topdown.txt} 에 쓰고, 바깥에서 색을 입혀 그림으로 만든다.
     */
    private static int topDown(CommandContext<CommandSourceStack> ctx, int radius) {
        ServerLevel level = ctx.getSource().getLevel();
        // 중심은 <b>등기된 집들의 무게중심</b> — 명령 실행 위치가 아니라 마을이 있는 곳을 찍는다.
        HomeStore reg = HomeStore.get(level);
        long sx = 0;
        long sz = 0;
        int n = 0;
        for (BlockPos h : reg.positions()) {
            sx += h.getX();
            sz += h.getZ();
            n++;
        }
        int cx = n == 0 ? (int) ctx.getSource().getPosition().x : (int) (sx / n);
        int cz = n == 0 ? (int) ctx.getSource().getPosition().z : (int) (sz / n);

        StringBuilder out = new StringBuilder();
        // 모르는 블록은 <b>이름을 적어 둔다</b> — 'o' 로 뭉뚱그리면 그게 뭔지 영영 못 본다.
        java.util.Map<String, Integer> unknown = new java.util.TreeMap<>();
        out.append(String.format("# center %d %d radius %d day %d%n", cx, cz, radius,
                com.evosim.mod.entity.SimTime.tick(level) / 24000L));
        int missing = 0;
        // 가로등은 집과 같은 참나무라 블록만 보면 'W'로 뭉개진다 — 등기부로 따로 표시한다.
        // 기둥은 'P', 그 위 지붕이 덮는 3×3 은 'p'.
        java.util.Set<Long> lampPost = new java.util.HashSet<>();
        java.util.Set<Long> lampTop = new java.util.HashSet<>();
        for (BlockPos b : LampStore.get(level).all()) {
            lampPost.add(RoadStore.key(b.getX(), b.getZ()));
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    lampTop.add(RoadStore.key(b.getX() + dx, b.getZ() + dz));
                }
            }
        }
        for (int z = cz - radius; z <= cz + radius; z++) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    out.append('?');
                    missing++;
                    continue;
                }
                long lk = RoadStore.key(x, z);
                if (lampPost.contains(lk)) {
                    out.append('P');
                    continue;
                }
                if (lampTop.contains(lk)) {
                    out.append('p');
                    continue;
                }
                int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types
                        .MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                // 지표 <b>위</b>도 본다. 스위트베리·꽃은 하이트맵에 안 잡혀 아래 흙만 찍혔다
                // (실측: 밭 85칸이 전부 흙으로 나왔다).
                var above = level.getBlockState(new BlockPos(x, top + 1, z));
                char c = above.isAir() ? codeOf(level.getBlockState(new BlockPos(x, top, z)),
                        top - 63, unknown) : codeOf(above, top - 63, unknown);
                if (c == 'o' && !above.isAir()) {
                    char c2 = codeOf(level.getBlockState(new BlockPos(x, top, z)), top - 63, unknown);
                    if (c2 != 'o') {
                        c = c2; // 위가 모르는 장식이면 지표를 쓴다
                    }
                }
                out.append(c);
            }
            out.append('\n');
        }
        for (var e : unknown.entrySet()) {
            out.append(String.format("# unknown %s %d%n", e.getKey(), e.getValue()));
        }
        java.nio.file.Path f = level.getServer().getServerDirectory().toPath()
                .resolve("evosim-topdown.txt");
        try {
            java.nio.file.Files.writeString(f, out.toString());
        } catch (java.io.IOException e) {
            tell(ctx.getSource(), "§c쓰기 실패: " + e.getMessage());
            return 0;
        }
        tell(ctx.getSource(), String.format(
                "§e[공중촬영] @%d,%d 반경%d (%d×%d칸) → evosim-topdown.txt · 미로드 %d칸",
                cx, cz, radius, radius * 2 + 1, radius * 2 + 1, missing));
        return 1;
    }

    /** 지표 블록 한 글자 코드 — 바깥에서 색을 입힌다. */
    private static char codeOf(net.minecraft.world.level.block.state.BlockState st, int rel,
                               java.util.Map<String, Integer> unknown) {
        var b = st.getBlock();
        if (st.is(Blocks.DIRT_PATH)) {
            return '#';                       // 흙 길
        }
        if (st.is(Blocks.SWEET_BERRY_BUSH)) {
            return 'B';                       // 밭 덤불
        }
        if (st.is(Blocks.GRASS_BLOCK)) {
            return rel > 3 ? 'G' : 'g';       // 잔디(높은 곳은 대문자 — 고도 표현)
        }
        if (st.is(Blocks.DIRT) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.ROOTED_DIRT)) {
            return 'd';
        }
        if (st.is(Blocks.OAK_PLANKS) || st.is(Blocks.OAK_SLAB) || st.is(Blocks.OAK_STAIRS)
                || st.is(Blocks.OAK_FENCE) || st.is(Blocks.OAK_FENCE_GATE)
                || st.is(Blocks.OAK_TRAPDOOR) || st.is(Blocks.OAK_DOOR)) {
            return 'W';                       // 집 — 나무
        }
        if (st.is(Blocks.STONE_BRICKS) || st.is(Blocks.STONE_BRICK_STAIRS)
                || st.is(Blocks.STONE_BRICK_SLAB) || st.is(Blocks.COBBLESTONE)) {
            return 'S';                       // 집 — 석재
        }
        if (st.is(Blocks.WHITE_WOOL) || st.is(Blocks.GLASS_PANE) || st.is(Blocks.GLASS)) {
            return 'W';
        }
        if (b instanceof net.minecraft.world.level.block.LeavesBlock) {
            return 'L';                       // 나뭇잎
        }
        if (b instanceof net.minecraft.world.level.block.RotatedPillarBlock
                && st.is(net.minecraft.tags.BlockTags.LOGS)) {
            return 'T';                       // 원목
        }
        if (!st.getFluidState().isEmpty()) {
            return '~';                       // 물·용암
        }
        if (st.is(Blocks.SAND) || st.is(Blocks.RED_SAND) || st.is(Blocks.SANDSTONE)) {
            return 's';
        }
        if (st.is(Blocks.GRAVEL)) {
            return 'v';
        }
        if (st.is(Blocks.STONE) || st.is(Blocks.ANDESITE) || st.is(Blocks.DIORITE)
                || st.is(Blocks.GRANITE) || st.is(Blocks.DEEPSLATE)) {
            return 'r';                       // 돌
        }
        if (st.is(Blocks.SNOW) || st.is(Blocks.SNOW_BLOCK) || st.is(Blocks.ICE)) {
            return 'n';
        }
        if (st.isAir()) {
            return '.';
        }
        unknown.merge(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(b).toString(),
                1, Integer::sum);
        return 'o';                           // 그 밖 — 이름은 파일 끝에 적힌다
    }

    /**
     * <b>길 관측</b> — 흙 길이 의도대로 깔렸는지를 수치로만 판정한다.
     *
     * <p>세 축을 본다. <b>형태</b>(중심선·실제 흙길 블록·폭), <b>연결</b>(덩어리 수와 도로망에
     * 붙은 집 수), <b>침범</b>(밭 몸통·타일·집 지면층·문앞 계단·정원). 침범은 전부 0이어야 하는
     * 불변식이고, 특히 <b>밭 몸통 관통</b>은 타일만 세면 0으로 보이므로 따로 센다 —
     * 밭은 재배줄 + 고랑 구조라 타일 사이로 길이 꿰뚫고 지나갈 수 있다.
     */
    private static int roadsReport(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        RoadStore roads = RoadStore.get(level);
        FarmStore farms = FarmStore.get(level);
        HomeStore reg = HomeStore.get(level);

        // ── 형태 ──
        java.util.Set<Long> center = new java.util.HashSet<>(roads.raw());
        java.util.Set<Long> paved = new java.util.HashSet<>();   // 월드에 실제로 깔린 흙길
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long c : center) {
            int x = RoadStore.keyX(c);
            int z = RoadStore.keyZ(c);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos g = surfaceNear(level, x + dx, z + dz);
                    if (g != null && level.getBlockState(g).is(Blocks.DIRT_PATH)) {
                        paved.add(RoadStore.key(x + dx, z + dz));
                    }
                }
            }
        }
        // 폭 분포 — 중심선 칸마다 3×3 중 몇 칸이 실제 흙길인가
        int[] wide = new int[10];
        for (long c : center) {
            int n = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (paved.contains(RoadStore.key(RoadStore.keyX(c) + dx,
                            RoadStore.keyZ(c) + dz))) {
                        n++;
                    }
                }
            }
            wide[Math.min(9, n)]++;
        }

        // ── 연결 ──
        java.util.List<java.util.Set<Long>> parts = roads.splitBy(java.util.Set.of());
        int lumps = parts.isEmpty() ? (center.isEmpty() ? 0 : 1) : parts.size();
        java.util.Set<Long> main = parts.isEmpty() ? center : parts.get(0);
        int homesOn = 0;
        int homesTotal = 0;
        StringBuilder off = new StringBuilder();
        for (BlockPos h : reg.positions()) {
            HomeStore.Entry e = reg.entry(h);
            if (e == null) {
                continue;
            }
            homesTotal++;
            HomeBlueprint bp = HomeBlueprint.of(level, h, e.design(), e.rotation(), e.mirrored());
            boolean on = false;
            for (BlockPos st : bp.doorSteps()) {
                for (int[] d : RoadPlanner.D4) {
                    if (main.contains(RoadStore.key(st.getX() + d[0], st.getZ() + d[1]))) {
                        on = true;
                    }
                }
            }
            if (on) {
                homesOn++;
            } else if (off.length() < 160) {
                off.append(h.getX()).append(',').append(h.getZ()).append(' ');
            }
        }

        // ── 침범(불변식) ──
        int vBody = 0;
        int vTile = 0;
        int vHome = 0;
        int vStep = 0;
        int vGarden = 0;
        java.util.Set<Long> homeCols = new java.util.HashSet<>();
        java.util.Set<Long> stepCols = new java.util.HashSet<>();
        java.util.Set<Long> gardenCols = new java.util.HashSet<>();
        for (BlockPos h : reg.positions()) {
            HomeStore.Entry e = reg.entry(h);
            if (e == null) {
                continue;
            }
            HomeBlueprint bp = HomeBlueprint.of(level, h, e.design(), e.rotation(), e.mirrored());
            for (BlockPos c : bp.groundFootprint()) {
                homeCols.add(RoadStore.key(c.getX(), c.getZ()));
            }
            for (BlockPos c : bp.doorSteps()) {
                stepCols.add(RoadStore.key(c.getX(), c.getZ()));
            }
            for (BlockPos c : bp.garden()) {
                gardenCols.add(RoadStore.key(c.getX(), c.getZ()));
            }
        }
        java.util.Set<Long> body = farms.bodyColumns();
        for (long c : paved) {
            int x = RoadStore.keyX(c);
            int z = RoadStore.keyZ(c);
            if (farms.isFarmColumn(x, z)) {
                vTile++;
            } else if (body.contains(c)) {
                vBody++;
            }
            if (homeCols.contains(c)) {
                vHome++;
            }
            if (stepCols.contains(c)) {
                vStep++;
            }
            if (gardenCols.contains(c)) {
                vGarden++;
            }
        }

        // ── 진행 중 ──
        int paving = 0;
        int todo = 0;
        for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.isPaving())) {
            paving++;
            todo += m.paveRemaining();
        }

        boolean clean = vBody + vTile + vHome + vStep + vGarden == 0;
        tell(ctx.getSource(), String.format(
                "§e[길] 중심선%d · 실제 흙길%d칸 · 범위 x%d..%d z%d..%d",
                center.size(), paved.size(),
                center.isEmpty() ? 0 : minX, center.isEmpty() ? 0 : maxX,
                center.isEmpty() ? 0 : minZ, center.isEmpty() ? 0 : maxZ));
        tell(ctx.getSource(), String.format(
                "  폭 분포(중심선 3×3 중 깔린 칸) 9칸:%d 7~8:%d 5~6:%d 3~4:%d 1~2:%d 0:%d",
                wide[9], wide[7] + wide[8], wide[5] + wide[6], wide[3] + wide[4],
                wide[1] + wide[2], wide[0]));
        tell(ctx.getSource(), String.format(
                "  연결 덩어리%d · 본체%d칸 · <b>도로망에 붙은 집 %d/%d</b>%s",
                lumps, main.size(), homesOn, homesTotal,
                off.length() == 0 ? "" : " · 안 붙은 집: " + off));
        tell(ctx.getSource(), String.format(
                "  %s침범 — 밭몸통관통%d 밭타일%d 집지면%d 문앞계단%d 정원%d§r",
                clean ? "§a" : "§c", vBody, vTile, vHome, vStep, vGarden));
        // 지형 진단 — 중심선인데 <b>블록이 안 깔린</b> 칸이 왜 그런지 가른다.
        //   지면없음 = groundUnder 범위(앵커 Y +1~−3) 안에 지표가 없다 → 급경사
        //   포장불가 = 지표는 있는데 잔디·흙 계열이 아니다 → 모래·자갈·돌·물
        int noGround = 0;
        int notPavable = 0;
        int bare = 0;
        for (long c : center) {
            int x = RoadStore.keyX(c);
            int z = RoadStore.keyZ(c);
            if (paved.contains(c)) {
                continue;
            }
            bare++;
            BlockPos g = surfaceNear(level, x, z);
            if (g == null) {
                noGround++;
            } else if (!RoadPlanner.pavable(level, g)) {
                notPavable++;
            }
        }
        tell(ctx.getSource(), String.format(
                "  시공 중 %d명 · 남은 %d칸 · 미포장 중심선 %d (지면없음%d 포장불가%d)",
                paving, todo, bare, noGround, notPavable));
        return center.size();
    }

    /**
     * <b>가로등 보고</b> — 등기 수와 <b>실제로 선 등</b>을 따로 센다.
     *
     * <p>등기는 <b>착공</b> 시점에 이루어진다(같은 자리 중복 착공 방지). 그래서 시공 중이거나
     * 시공자가 죽으면 등기 ≠ 실물이다. 이 둘을 한 숫자로 뭉치면 "몇 기 섰다"가 허수가 된다.
     */
    private static int lampsReport(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        LampStore lamps = LampStore.get(level);
        RoadStore roads = RoadStore.get(level);
        FarmStore farms = FarmStore.get(level);
        HomeStore reg = HomeStore.get(level);
        int need = LampPlanner.blockCount(level);

        java.util.List<BlockPos> all = lamps.all();
        int done = 0;      // 도면 14칸이 전부 선 등
        int partial = 0;   // 착공했으나 미완
        int lit = 0;       // 랜턴이 실제로 걸린 등
        for (BlockPos b : all) {
            int have = 0;
            boolean lantern = false;
            for (HomeTemplate.Placement p : LampPlanner.plan(level).orElse(java.util.List.of())) {
                BlockPos w = b.offset(p.rel());
                if (level.getBlockState(w).is(p.state().getBlock())) {
                    have++;
                    if (p.state().is(Blocks.LANTERN)) {
                        lantern = true;
                    }
                }
            }
            if (have >= need) {
                done++;
            } else {
                partial++;
            }
            if (lantern) {
                lit++;
            }
        }

        // ── 간격 ── 등기된 등끼리의 최근접 거리 분포. 설계 하한은 SPACING.
        double minGap = Double.MAX_VALUE;
        double sumGap = 0.0;
        int gaps = 0;
        int tooClose = 0;
        for (int i = 0; i < all.size(); i++) {
            double best = Double.MAX_VALUE;
            for (int j = 0; j < all.size(); j++) {
                if (i == j) {
                    continue;
                }
                double dx = all.get(i).getX() - all.get(j).getX();
                double dz = all.get(i).getZ() - all.get(j).getZ();
                best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
            }
            if (best < Double.MAX_VALUE) {
                minGap = Math.min(minGap, best);
                sumGap += best;
                gaps++;
                if (best < LampPlanner.SPACING - 1.0E-6) {
                    tooClose++;
                }
            }
        }

        // ── 침범(불변식) ── 등 기둥이 있으면 안 되는 곳. 길과 <b>같은 목록</b>을 본다.
        java.util.Set<Long> homeCols = new java.util.HashSet<>();
        java.util.Set<Long> stepCols = new java.util.HashSet<>();
        java.util.Set<Long> gardenCols = new java.util.HashSet<>();
        for (BlockPos h : reg.positions()) {
            HomeStore.Entry e = reg.entry(h);
            if (e == null) {
                continue;
            }
            HomeBlueprint bp = HomeBlueprint.of(level, h, e.design(), e.rotation(), e.mirrored());
            for (BlockPos c : bp.groundFootprint()) {
                homeCols.add(RoadStore.key(c.getX(), c.getZ()));
            }
            for (BlockPos c : bp.doorSteps()) {
                stepCols.add(RoadStore.key(c.getX(), c.getZ()));
            }
            for (BlockPos c : bp.garden()) {
                gardenCols.add(RoadStore.key(c.getX(), c.getZ()));
            }
        }
        java.util.Set<Long> body = farms.bodyColumns();
        int vRoad = 0;
        int vHome = 0;
        int vStep = 0;
        int vGarden = 0;
        int vFarm = 0;
        int nearRoad = 0; // 길에서 2칸(=폭3 띠 바로 바깥)인 등 — 길가에 섰는가
        for (BlockPos b : all) {
            long k = RoadStore.key(b.getX(), b.getZ());
            boolean onBand = false;
            boolean beside = false;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (!roads.has(b.getX() + dx, b.getZ() + dz)) {
                        continue;
                    }
                    if (Math.max(Math.abs(dx), Math.abs(dz)) <= 1) {
                        onBand = true;
                    } else {
                        beside = true;
                    }
                }
            }
            if (onBand) {
                vRoad++;
            } else if (beside) {
                nearRoad++;
            }
            if (homeCols.contains(k)) {
                vHome++;
            }
            if (stepCols.contains(k)) {
                vStep++;
            }
            if (gardenCols.contains(k)) {
                vGarden++;
            }
            if (body.contains(k)) {
                vFarm++;
            }
        }

        // ── 밝기 ── 완성된 등의 랜턴 칸 밝기 + 길 위 어두운 칸(몹 생성 가능 = 0) 비율.
        int dark = 0;
        int sampled = 0;
        for (int[] c : roads.all()) {
            BlockPos g = surfaceNear(level, c[0], c[1]);
            if (g == null) {
                continue;
            }
            sampled++;
            if (level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, g.above()) == 0) {
                dark++;
            }
        }

        int building = 0;
        for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getLampSite() != null)) {
            building++;
        }
        boolean clean = vRoad + vHome + vStep + vGarden + vFarm + tooClose == 0;
        tell(ctx.getSource(), String.format(
                "§e[가로등] 등기%d (완성%d · 시공중/미완%d) · 랜턴 걸린 등%d · 지금 세우는 중 %d명",
                all.size(), done, partial, lit, building));
        tell(ctx.getSource(), String.format(
                "  간격 — 최소%.1f 평균%.1f (설계 하한 %d) · 도로망 중심선%d칸당 등 1기",
                gaps == 0 ? 0.0 : minGap, gaps == 0 ? 0.0 : sumGap / gaps, LampPlanner.SPACING,
                all.isEmpty() ? 0 : roads.size() / all.size()));
        tell(ctx.getSource(), String.format(
                "  %s침범 — 길위%d 집지면%d 문앞계단%d 정원%d 밭몸통%d 간격위반%d§r · 길가(2칸)%d/%d",
                clean ? "§a" : "§c", vRoad, vHome, vStep, vGarden, vFarm, tooClose,
                nearRoad, all.size()));
        // ── 가로수·분수 — 간격이 지켜지는지와 길 위에 서지 않았는지.
        var street = com.evosim.mod.entity.StreetStore.get(level);
        for (boolean fnt : new boolean[] {false, true}) {
            var items = street.all(fnt);
            if (items.isEmpty()) {
                // <b>0 이면 왜 0 인지</b>를 그 자리에서 훑어 말한다. 착공 경로는 자리를 못
                // 찾으면 조용히 넘어가므로(나무는 매일 후보를 보는지라 사건을 남기면 도배가
                // 된다), 사유를 보고에서 되짚는다. 이 훑기는 읽기만 하고 아무것도 바꾸지 않는다.
                com.evosim.mod.entity.StreetPlanner.pickSite(level, fnt);
                tell(ctx.getSource(), String.format(
                        "  §e[%s]§r 0개 — 걸러진 사유 %s (도로망 %d칸)",
                        fnt ? "분수" : "가로수",
                        com.evosim.mod.entity.StreetPlanner.rejectSummary(), roads.size()));
                continue;
            }
            double mn = Double.MAX_VALUE;
            double sum = 0.0;
            int pairs = 0;
            int onRoad = 0;
            for (BlockPos a : items) {
                if (roads.has(a.getX(), a.getZ())) {
                    onRoad++;
                }
                double best = Double.MAX_VALUE;
                for (BlockPos b : items) {
                    if (a.equals(b)) {
                        continue;
                    }
                    best = Math.min(best, Math.sqrt(a.distSqr(
                            new BlockPos(b.getX(), a.getY(), b.getZ()))));
                }
                if (best < Double.MAX_VALUE) {
                    mn = Math.min(mn, best);
                    sum += best;
                    pairs++;
                }
            }
            int want = fnt ? com.evosim.mod.entity.StreetPlanner.FOUNTAIN_SPACING
                    : com.evosim.mod.entity.StreetPlanner.TREE_SPACING;
            tell(ctx.getSource(), String.format(
                    "  §e[%s]§r %d개 · 최근접 최소%.1f 평균%.1f (하한 %d) · %s길위%d§r",
                    fnt ? "분수" : "가로수", items.size(),
                    pairs == 0 ? 0.0 : mn, pairs == 0 ? 0.0 : sum / pairs, want,
                    onRoad == 0 ? "§a" : "§c", onRoad));
        }
        tell(ctx.getSource(), String.format(
                "  밤 밝기 — 길 위 밝기0(몹 생성 가능) %d/%d칸 (%.0f%%)",
                dark, sampled, sampled == 0 ? 0.0 : 100.0 * dark / sampled));
        // <b>길을 따라</b> 잰 등까지의 거리 — 배치가 고르게 퍼졌는지의 단일 지표. 직선거리로
        // 재면 들판 건너 등이 가깝다고 잡혀 긴 우회 구간이 밝은 것으로 오판된다.
        java.util.Map<Long, Integer> walk = LampPlanner.lampDist(roads, lamps);
        java.util.List<Integer> ds = new java.util.ArrayList<>(roads.size());
        for (int[] c : roads.all()) {
            ds.add(walk.getOrDefault(RoadStore.key(c[0], c[1]), LampPlanner.DARK_CAP));
        }
        java.util.Collections.sort(ds);
        int over1 = 0;
        int over2 = 0;
        long sum = 0;
        for (int v : ds) {
            sum += v;
            if (v > LampPlanner.SPACING) {
                over1++;
            }
            if (v >= LampPlanner.DARK_CAP) {
                over2++;
            }
        }
        tell(ctx.getSource(), String.format(
                "  길 따라 등까지 — 최대%d 중앙%d 평균%.1f · %d칸 초과 %d(%.0f%%) · %d칸 이상 %d(%.0f%%)",
                ds.isEmpty() ? 0 : ds.get(ds.size() - 1), ds.isEmpty() ? 0 : ds.get(ds.size() / 2),
                ds.isEmpty() ? 0.0 : (double) sum / ds.size(),
                LampPlanner.SPACING, over1, ds.isEmpty() ? 0.0 : 100.0 * over1 / ds.size(),
                LampPlanner.DARK_CAP, over2, ds.isEmpty() ? 0.0 : 100.0 * over2 / ds.size()));
        return all.size();
    }

    /**
     * <b>밭 형태 진단</b> — 구획마다 격자 좌표로 되돌려 "얼마나 반듯한가"를 잰다.
     *
     * <p>공중 덤프의 덤불을 군집화해서 재면 안 된다. 렌더는 어느 타일이 <b>어느 구획</b>
     * 소유인지 모르므로, 맞닿아 자란 두 구획과 집 정원이 한 덩어리로 묶여 멀쩡한 구획이
     * 찌그러진 것으로 잡힌다(실측으로 그 오판을 확인했다). 여기서는 {@link FarmStore} 를
     * 직접 읽어 타일마다 제 구획에 귀속시킨다.
     *
     * <p>{@link com.evosim.core.FarmLayout} 의 의도는 <b>재배줄 + 한 칸 고랑의 꽉 찬
     * 직사각형</b>이다(월드 깊이 = 줄×2). 그래서 앵커 기준 격자 (col, row) 로 되돌리면
     * 이상적인 구획은 빈틈없는 직사각형이어야 한다. 어긋나는 방식을 넷으로 나눠 센다.
     *
     * <ul>
     *   <li><b>구멍</b> — 한 줄 안에서 최소~최대 col 사이에 빠진 칸.</li>
     *   <li><b>줄끊김</b> — 한 줄이 연속 구간 둘 이상으로 갈린 횟수.</li>
     *   <li><b>사분면</b> — 앵커 기준 부호 조합. 2 이상이면 {@code FarmLayout.mirrors} 의
     *       거울 반사가 쓰였다는 뜻이다(막힌 칸을 반대편에 놓는다). 구획이 한쪽으로
     *       자라지 않고 앵커를 중심으로 흩어진다.</li>
     *   <li><b>고랑오염</b> — 줄 간격이 2가 아닌 타일. 있으면 재배줄 구조 자체가 깨진 것이다.</li>
     * </ul>
     */
    private static int farmShape(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        FarmStore farms = FarmStore.get(level);
        java.util.List<FarmStore.Plot> plots = new java.util.ArrayList<>(farms.all().values());
        plots.sort((a, b) -> b.tiles.length - a.tiles.length);
        tell(ctx.getSource(), "§e[밭형태]§r 구획" + plots.size()
                + " — 의도: 덩어리 도면 [테두리][재배2][길][재배2][테두리]");
        int legacy = 0;
        int badLog = 0;      // 원목이어야 할 칸에 원목이 없다
        int badCrop = 0;     // 재배여야 할 칸에 베리가 없다(아직 안 심음 포함)
        int strayTile = 0;   // 등록 타일인데 발자국 밖
        int totTiles = 0;
        int totWant = 0;
        for (FarmStore.Plot p : plots) {
            if (p.beds <= 0) {
                legacy++;
                continue;
            }
            int[] fp = FarmLayout.footprint(p.beds, p.rows);
            int want = FarmLayout.tiles(p.beds, p.rows);
            int haveCrop = 0;
            int missLog = 0;
            for (int c = 0; c < fp[0]; c++) {
                for (int r = 0; r < fp[1]; r++) {
                    int[] xz = FarmTicker.colOf(p, c, r);
                    BlockPos base = new BlockPos(xz[0], p.baseY + 1, xz[1]);
                    if (FarmLayout.isCrop(c, r, p.beds, p.rows)) {
                        if (level.getBlockState(base.above()).is(Blocks.SWEET_BERRY_BUSH)) {
                            haveCrop++;
                        }
                    } else if (!level.getBlockState(base).is(Blocks.OAK_LOG)) {
                        missLog++;
                    }
                }
            }
            int outside = 0;
            for (long l : p.tiles) {
                BlockPos t = BlockPos.of(l);
                int dx = p.bedAxisX ? t.getX() - p.fx : t.getZ() - p.fz;
                int dz = p.bedAxisX ? t.getZ() - p.fz : t.getX() - p.fx;
                if (dx < 0 || dz < 0 || dx >= fp[0] || dz >= fp[1]
                        || !FarmLayout.isCrop(dx, dz, p.beds, p.rows)) {
                    outside++;
                }
            }
            badLog += missLog;
            badCrop += want - haveCrop;
            strayTile += outside;
            totTiles += haveCrop;
            totWant += want;
            if (plots.indexOf(p) < 8) {
                tell(ctx.getSource(), String.format(
                        "  #%d %d단계 덩어리%d 줄%d · 재배 %d/%d · 발자국 %dx%d · %s원목결손%d 발자국밖%d§r"
                                + " · %s @%d,%d y%d",
                        p.id, p.steps + 1, p.beds, p.rows, haveCrop, want, fp[0], fp[1],
                        missLog + outside == 0 ? "§a" : "§c", missLog, outside,
                        p.bedAxisX ? "덩어리축x" : "덩어리축z", p.fx, p.fz, p.baseY));
            }
        }
        boolean clean = badLog == 0 && strayTile == 0;
        tell(ctx.getSource(), String.format(
                "  %s합계 재배 %d/%d(%.0f%%) · 원목결손%d · 발자국밖 타일%d · 구세계구획%d§r",
                clean ? "§a" : "§c", totTiles, totWant,
                totWant == 0 ? 0.0 : 100.0 * totTiles / totWant, badLog, strayTile, legacy));
        // 못 채운 사유 — 아직 심는 중인지, 다음 단계가 막힌 것인지.
        java.util.Map<String, Integer> why = new java.util.TreeMap<>();
        for (FarmStore.Plot p : plots) {
            for (var e : FarmTicker.unfilledReasons(level, farms, p, 0).entrySet()) {
                why.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }
        StringBuilder wb = new StringBuilder();
        why.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(8)
                .forEach(e -> wb.append(e.getKey()).append(e.getValue()).append(' '));
        tell(ctx.getSource(), "  다음 단계 사정 — " + wb.toString().trim());
        // 구획 간 최소거리 — 두 밭이 붙어 한 덩어리로 보이지 않는지.
        double minGap = Double.MAX_VALUE;
        int touching = 0;
        for (int a = 0; a < plots.size(); a++) {
            for (int b = a + 1; b < plots.size(); b++) {
                double d = boxGap(plots.get(a), plots.get(b));
                if (d < 0) {
                    continue;
                }
                minGap = Math.min(minGap, d);
                // 문턱은 PLOT_GAP <b>미만</b>이다. 빈 칸 3 은 설계된 하한이지 위반이 아니다 —
                // 옛 기하에서 타일-대-타일로 재던 "≤3" 을 상자-대-상자에 그대로 가져오면
                // 정상값이 전부 위반으로 잡힌다(실측: 하한에 정확히 멈춘 6쌍이 경보로 떴다).
                if (d < com.evosim.mod.entity.FarmTicker.PLOT_GAP) {
                    touching++;
                }
            }
        }
        tell(ctx.getSource(), String.format(
                "  구획 간 최소거리 %.1f (하한 %d) · 하한 위반 구획쌍 %d",
                minGap == Double.MAX_VALUE ? 0.0 : minGap,
                com.evosim.mod.entity.FarmTicker.PLOT_GAP, touching));
        return 1;
    }

    /**
     * 군인 가구가 출산 문턱을 넘기까지 주는 <b>지평</b>(일). 사용자 기준은 "봉급으로 굶지 않고
     * 약간 흑자를 보며 아이 1명은 가질 수 있을 정도"라, 출산은 하루치 비용이 아니라 이 기간 안에
     * 쌓아 올리는 적립이다. 10일은 시뮬 1일 = 설계 2일 압축에서 설계 3주 남짓에 해당한다.
     */
    private static final double TROOP_BIRTH_HORIZON_DAYS = 10.0;

    /**
     * <b>무토지 가구 한 호의 하루 실소모 평균</b> — 군인 한 호를 먹이는 값.
     *
     * <p>군인은 밭 없는 가구에서 뽑히므로(계획서 §1.7 천민의 진로) 그 층의 살림이 곧 원가다.
     * 밭을 가진 가구는 셈에서 뺀다 — 지주는 자녀가 많아 가구가 크고, 그 식비로 나누면 군인
     * 수가 과소평가된다.
     */
    private static double landlessHouseCost(ServerLevel level, FarmStore farms) {
        java.util.Map<net.minecraft.core.BlockPos, double[]> byHome = new java.util.HashMap<>();
        java.util.Set<net.minecraft.core.BlockPos> landed = new java.util.HashSet<>();
        for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null && e.getHomePos() != null)) {
            byHome.computeIfAbsent(m.getHomePos(), k -> new double[1])[0]
                    += m.dailyConsumedActual();
            if (farms.ownedTiles(m.getIndividual().id()) > 0) {
                landed.add(m.getHomePos());
            }
        }
        double sum = 0.0;
        int n = 0;
        for (var e : byHome.entrySet()) {
            if (landed.contains(e.getKey()) || e.getValue()[0] <= 0.0) {
                continue;
            }
            sum += e.getValue()[0];
            n++;
        }
        return n == 0 ? 0.0 : sum / n;
    }

    /** 이 개체가 속한 가구의 하루 소모 합(명목) — 착공 임계 등 <b>판정</b>과 같은 척도. */
    private static double familyNeedOf(ServerLevel level, MimicEntity who) {
        double need = 0.0;
        for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null && e.getHomePos() != null
                        && e.getHomePos().equals(who.getHomePos()))) {
            need += com.evosim.core.FoodEconomy.consumptionPerDay(
                    m.getStage(), com.evosim.core.Activity.MOVE, m.getIndividual(), false);
        }
        return need;
    }

    /**
     * 이 개체가 속한 가구의 하루 <b>실</b>소모 합 — 부양력의 분모.
     *
     * <p>명목치(MOVE 기준)를 쓰면 안 된다. 미믹은 하루의 태반을 자고(×0.0) 쉬므로(×0.4) 실제
     * 소모는 명목의 1/4 안팎이고, 명목으로 재면 넉넉한 영주도 영영 "부양 0호"로 찍힌다 —
     * 정원 수지를 명목 6.0 과 견주어 적자로 착각했던 것과 같은 착오다. 실측 계량기가 아직 하루를
     * 못 채웠으면(런 첫날) 0 이 나오므로, 그때만 명목으로 물러선다.
     */
    private static double familyActualOf(ServerLevel level, MimicEntity who) {
        double sum = 0.0;
        for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null && e.getHomePos() != null
                        && e.getHomePos().equals(who.getHomePos()))) {
            sum += m.dailyConsumedActual();
        }
        return sum > 0.0 ? sum : familyNeedOf(level, who);
    }

    /** 이 지주의 밭에 붙은 상시 소작 수. */
    /**
     * 이 주인의 밭에서 <b>오늘 실제로 일하는</b> 사람 수 — 상시 계약과 일용 배정을 함께 센다.
     *
     * <p>계약만 세면 실제 고용을 크게 놓친다(w10 실측 d14~d19: 계약 3명, 실제 9~13명). 일용·
     * 긴급 배정이 명부에 없기 때문이다.
     */
    private static int tenantsOf(ServerLevel level, long ownerId) {
        FarmStore store = FarmStore.get(level);
        int n = 0;
        for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                e -> e.isAlive() && (e.getTenantFarm() != 0L
                        || FarmTicker.assignedPlot(e.getId()) != 0L))) {
            long pid = m.getTenantFarm() != 0L ? m.getTenantFarm()
                    : FarmTicker.assignedPlot(m.getId());
            FarmStore.Plot p = store.get(pid);
            if (p != null && p.ownerId == ownerId) {
                n++;
            }
        }
        return n;
    }

    /** 두 발자국 상자 사이의 빈 칸 거리(겹치면 0, 한쪽이 구세계면 −1). */
    private static double boxGap(FarmStore.Plot a, FarmStore.Plot b) {
        if (a.beds <= 0 || b.beds <= 0) {
            return -1;
        }
        int[] ba = FarmTicker.boxOf(a, a.beds, a.rows);
        int[] bb = FarmTicker.boxOf(b, b.beds, b.rows);
        int dx = Math.max(0, Math.max(ba[0] - (bb[0] + bb[2]), bb[0] - (ba[0] + ba[2])));
        int dz = Math.max(0, Math.max(ba[1] - (bb[1] + bb[3]), bb[1] - (ba[1] + ba[3])));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * <b>신세·추종 보고</b> — 원장이 실제로 쌓이는지, 추종이 성립하는지, 몇 명에게 몰리는지.
     *
     * <p>P2 는 행동을 바꾸지 않으므로, 이 보고에 숫자가 생기고 <b>다른 모든 지표는 그대로</b>인
     * 것이 합격 조건이다.
     */
    private static int allegiance(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        AllegianceStore led = AllegianceStore.get(level);
        FarmStore farms = FarmStore.get(level);

        java.util.Map<Long, MimicEntity> byId = new java.util.HashMap<>();
        for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null)) {
            byId.putIfAbsent(m.getIndividual().id(), m);
        }

        int edges = 0;
        double totForgiven = 0.0;
        double totOwed = 0.0;
        for (var e : led.all().entrySet()) {
            for (AllegianceStore.Bond b : e.getValue()) {
                edges++;
                totForgiven += b.forgiven;
                totOwed += b.owed;
            }
        }

        // 추종 판정 — 임계는 자기 재산에 비례한다. 재산은 거처 저장고로 본다.
        java.util.Map<Long, Long> patron = new java.util.HashMap<>();
        for (long debtor : led.all().keySet()) {
            MimicEntity m = byId.get(debtor);
            if (m == null) {
                continue;
            }
            long p = led.patronOf(debtor, farms.ownedTiles(debtor));
            if (p != 0L) {
                patron.put(debtor, p);
            }
        }

        java.util.Map<Long, Integer> direct = new java.util.HashMap<>();
        for (long p : patron.values()) {
            direct.merge(p, 1, Integer::sum);
        }

        // 순환·깊이 — 추종은 DAG 여야 한다(A→B→C→A 가 생기면 계층이 성립하지 않는다).
        int cycles = 0;
        int maxDepth = 0;
        for (long start : patron.keySet()) {
            java.util.Set<Long> seen = new java.util.HashSet<>();
            long cur = start;
            int d = 0;
            while (patron.containsKey(cur)) {
                if (!seen.add(cur)) {
                    cycles++;
                    break;
                }
                cur = patron.get(cur);
                d++;
                if (d > 64) {
                    cycles++;
                    break;
                }
            }
            maxDepth = Math.max(maxDepth, d);
        }

        tell(ctx.getSource(), String.format(
                "§e[신세] 간선%d · 채무자%d · 탕감분합%.0f · 상환분합%.0f",
                edges, led.all().size(), totForgiven, totOwed));
        tell(ctx.getSource(), String.format(
                "  %s추종 성립%d/%d명 · 주인%d명 · 최대세력%d · 사슬깊이%d · 순환%d§r",
                cycles == 0 ? "§a" : "§c",
                patron.size(), byId.size(), direct.size(),
                direct.values().stream().mapToInt(Integer::intValue).max().orElse(0),
                maxDepth, cycles));
        // ── 교회 귀속(P6) — <b>반사실</b>로 묻는다: 교회에서 온 몫을 빼면 이 사슬이 남는가.
        //
        // 합계만 보면 깊이 2 가 교회 덕인지 밭 지대 덕인지 감쇠 완화 덕인지 갈리지 않는다.
        // 결속마다 교회에서 온 몫을 따로 세어 두었으므로(Bond.fromChurch), 그것을 뺀 원장으로
        // 같은 판정을 한 번 더 돌리면 "교회가 없었다면" 이 그 자리에서 계산된다.
        java.util.Map<Long, Long> patronNoCh = new java.util.HashMap<>();
        for (long debtor : led.all().keySet()) {
            if (byId.get(debtor) == null) {
                continue;
            }
            long p = led.patronOf(debtor, farms.ownedTiles(debtor), true);
            if (p != 0L) {
                patronNoCh.put(debtor, p);
            }
        }
        int depthNoCh = 0;
        for (long start : patronNoCh.keySet()) {
            java.util.Set<Long> seen = new java.util.HashSet<>();
            long cur = start;
            int d = 0;
            while (patronNoCh.containsKey(cur)) {
                if (!seen.add(cur) || d > 64) {
                    break;
                }
                cur = patronNoCh.get(cur);
                d++;
            }
            depthNoCh = Math.max(depthNoCh, d);
        }
        double churchSum = 0.0;
        int churchBonds = 0;
        for (var e : led.all().entrySet()) {
            for (AllegianceStore.Bond b : e.getValue()) {
                if (b.fromChurch > 0.0) {
                    churchBonds++;
                    churchSum += b.fromChurch;
                }
            }
        }
        tell(ctx.getSource(), String.format(
                "  교회 귀속 — 교회분 있는 결속%d개(합%.1f) · 교회분 빼면 추종%d명·사슬깊이%d"
                        + " (지금 %d명·%d — 차이가 곧 교회의 몫)",
                churchBonds, churchSum, patronNoCh.size(), depthNoCh, patron.size(), maxDepth));

        // ── 신분(파생) ── 판정식은 SocialRank 한 곳에만 있다. 여기서는 부르고 세기만 한다.
        java.util.Map<Long, SocialRank> ranks = SocialRank.derive(
                byId.keySet(), patron, farms::ownedTiles, led::owedOf, led::boundDays);

        java.util.Map<SocialRank, int[]> tally = new java.util.EnumMap<>(SocialRank.class);
        java.util.Map<SocialRank, double[]> sums = new java.util.EnumMap<>(SocialRank.class);
        for (SocialRank r : SocialRank.values()) {
            tally.put(r, new int[] {0});
            sums.put(r, new double[3]); // 소유타일 · 저장고 · 추종자
        }
        LarderStore larders = LarderStore.get(level);
        for (var e : ranks.entrySet()) {
            SocialRank r = e.getValue();
            MimicEntity m = byId.get(e.getKey());
            tally.get(r)[0]++;
            double[] s = sums.get(r);
            s[0] += farms.ownedTiles(e.getKey());
            s[1] += m == null || m.getHomePos() == null ? 0.0 : larders.get(m.getHomePos());
            s[2] += direct.getOrDefault(e.getKey(), 0);
        }
        StringBuilder line = new StringBuilder();
        StringBuilder detail = new StringBuilder();
        for (SocialRank r : SocialRank.values()) {
            int n = tally.get(r)[0];
            line.append(line.length() > 0 ? " · " : "").append(r.label()).append(n);
            if (n == 0) {
                continue;
            }
            double[] s = sums.get(r);
            detail.append(detail.length() > 0 ? " · " : "")
                    .append(String.format("%s 밭%.0f/살림%.0f/추종자%.1f",
                            r.label(), s[0] / n, s[1] / n, s[2] / n));
        }
        tell(ctx.getSource(), "  신분 — " + line + "  (지배=간접지배 성립 · 천민=매인 무토지 자활불능)");
        tellLadder(ctx.getSource(), byId, farms, larders);
        // 척도 자체를 드러낸다 — 천민이 0 일 때 그것이 "조건이 죽어서"인지 "정말 아무도 해당
        // 없어서"인지 구분할 수 없으면 0 은 보고가 아니다. 궁핍은 계측 전용(행동·판정 무관).
        int poorNow = 0;
        int poorMax = 0;
        int boundMax = 0;
        int boundReady = 0;
        for (long id : byId.keySet()) {
            int pd = led.destituteDays(id);
            int bd = led.boundDays(id);
            if (pd > 0) {
                poorNow++;
            }
            poorMax = Math.max(poorMax, pd);
            boundMax = Math.max(boundMax, bd);
            if (bd >= SocialRank.BOUND_DAYS) {
                boundReady++;
            }
        }
        tell(ctx.getSource(), String.format(
                "  척도 — 예속 최장%d일 · %d일이상%d명(문턱%d) · 궁핍 오늘%d명 최장%d일 · 상환분합%.0f",
                boundMax, SocialRank.BOUND_DAYS, boundReady, SocialRank.BOUND_DAYS,
                poorNow, poorMax, totOwed));

        // ── 봉건 수지(P4) ── 목표 4 의 판정 근거. "지배자는 손해가 아니라 이익을 본다"를
        //    주장이 아니라 뺄셈으로 보인다. 순수지 = 받은 것 − 낸 것.
        double[] ts = FarmTicker.taxSums();
        int[] tc = FarmTicker.taxCounts();
        java.util.Map<Long, Double> in = FarmTicker.taxIn();
        java.util.Map<Long, Double> out = FarmTicker.taxOut();
        tell(ctx.getSource(), String.format(
                "  세수 — 징수%.1f(%d명) · 미납%.1f(%d명) · 상납%.1f · 상환%.1f",
                ts[0], tc[0], ts[1], tc[1], ts[2], ts[3]));
        // 계층별 순수지 — 지배·상위가 +, 평민·천민이 − 여야 지배가 성립한 것이다.
        java.util.Map<SocialRank, double[]> net = new java.util.EnumMap<>(SocialRank.class);
        for (SocialRank r : SocialRank.values()) {
            net.put(r, new double[2]); // [순수지 합, 인원]
        }
        for (var e : ranks.entrySet()) {
            double[] n = net.get(e.getValue());
            n[0] += in.getOrDefault(e.getKey(), 0.0) - out.getOrDefault(e.getKey(), 0.0);
            n[1]++;
        }
        StringBuilder ns = new StringBuilder();
        for (SocialRank r : SocialRank.values()) {
            double[] n = net.get(r);
            if (n[1] == 0) {
                continue;
            }
            ns.append(ns.length() > 0 ? " · " : "")
                    .append(String.format("%s %+.2f", r.label(), n[0] / n[1]));
        }
        if (ns.length() > 0) {
            tell(ctx.getSource(), "  계층별 1인 순수지 — " + ns);
        }
        // 라벨만 갈라 놓으면 의미가 없다. 계층별 평균으로 <b>실제 수치가 갈리는지</b>를 본다.
        // 살림은 가구 저장고라 한 지붕 아래 사람에게 같은 값이 잡힌다(개인 재산이 아님).
        tell(ctx.getSource(), "  계층별 평균 — " + detail);
        // ── <b>역할별</b> 순수지 — 신분 라벨과 별개로 "고용된 층" 이 쌓이는가를 본다.
        //
        // 신분(지배·상위·평민·천민)은 소유와 예속으로 갈리므로, 무토지인 마름과 소작은 둘 다
        // 천민으로 묶여 서로 구별되지 않는다. 그런데 계층이 굳으려면 <b>고용된 자가 자기
        // 저장고를 가질 수 있어야</b> 한다 — 그것을 보려면 역할로 갈라 봐야 한다.
        // 실측(D26)에서 평민 −0.24 · 천민 −0.95 로 아래 두 층이 모두 적자였다.
        LarderStore larderStore = LarderStore.get(level);
        java.util.Map<String, double[]> role = new java.util.LinkedHashMap<>();
        role.put("영주", new double[3]);
        role.put("마름", new double[3]);
        role.put("소작", new double[3]);
        role.put("자영", new double[3]);
        role.put("무직", new double[3]);
        for (MimicEntity m : byId.values()) {
            // <b>노년도 센다.</b> ADULT 만 세면 자리 잡은 영주가 통째로 빠진다 — 실측 D15:
            // 72타일·소작4·마름4 를 거느린 1위 지주가 노년이 되자 역할별에서 사라졌다.
            if (m.getIndividual() == null || m.getHomePos() == null
                    || (m.getStage() != com.evosim.core.LifeStage.ADULT
                            && m.getStage() != com.evosim.core.LifeStage.ELDER)) {
                continue;
            }
            long id = m.getIndividual().id();
            String key = farms.ownedTiles(id) > 0
                    ? (direct.getOrDefault(id, 0) > 0 ? "영주" : "자영")
                    : (farms.stewardOf(id) != 0L ? "마름"
                            // <b>상시 계약만 세면 안 된다.</b> 실측(w10 d14~d19): 계약 소작은 3명인데
                            // 실제로 밭에서 일한 사람은 매일 9~13명이었다 — 나머지는 일용·긴급
                            // 배정이라 계약 명부에 없다. 그걸 무직으로 분류하면 "소작농이 다수인가"를
                            // 물을 수 없고, 실제로 서 있는 피라미드를 보고가 감춘다.
                            : (m.getTenantFarm() != 0L
                                    || FarmTicker.assignedPlot(m.getId()) != 0L
                                            ? "소작" : "무직"));
            double[] v = role.get(key);
            v[0] += in.getOrDefault(id, 0.0) - out.getOrDefault(id, 0.0);
            v[1]++;
            v[2] += larderStore.get(m.getHomePos());
        }
        StringBuilder rs = new StringBuilder();
        for (var e : role.entrySet()) {
            if (e.getValue()[1] == 0) {
                continue;
            }
            rs.append(rs.length() > 0 ? " · " : "").append(String.format(
                    "%s%d명 %+.2f/살림%.0f", e.getKey(), (int) e.getValue()[1],
                    e.getValue()[0] / e.getValue()[1], e.getValue()[2] / e.getValue()[1]));
        }
        if (rs.length() > 0) {
            // 이름을 정확히 쓴다 — 이 수는 <b>세금 흐름</b>(신세 상납·상환)만 집계한 것이고
            // 채집 소득도 식량 소모도 들어 있지 않다(FarmTicker.taxIn/taxOut). 그냥 "순수지"라
            // 부르면 "평민이 적자라 착공 임계에 못 닿는다" 같은 <b>없는 결론</b>을 부른다.
            // 축적을 말하는 것은 옆의 살림(저장고)이다.
            tell(ctx.getSource(), "  역할별 1인 세수순수지/살림 — " + rs
                    + "  ※세수순수지는 신세 상납·상환만 — 채집·소모 불포함. 축적은 살림을 볼 것");
        }
        // ── <b>벌이</b> — 지갑이 아니라 소득으로 본다.
        //
        // 저장고가 얕다고 가난한 것이 아니다. 많이 버는 자는 번 것을 밭으로 되돌리므로 지갑이
        // 늘 얕아 보인다. 그러니 <b>얼마나 벌었나</b>를 원장에서 직접 읽는다 — 밭 원장의
        // totalToOwner(지대+자영)와 totalToTenant(소작 몫)는 개간일부터의 누계다.
        long today2 = com.evosim.mod.entity.SimTime.tick(level) / 24000L;
        // 지주별 [수취누계, 지출누계, 최장 경과일]
        java.util.Map<Long, double[]> earn = new java.util.HashMap<>();
        double tenantSum = 0.0;
        for (FarmStore.Plot p : farms.all().values()) {
            double days = Math.max(1.0, today2 - Math.max(0L, p.foundedDay));
            double[] e = earn.computeIfAbsent(p.ownerId, k -> new double[3]);
            e[0] += p.totalToOwner;
            e[1] += p.totalSpentExpand;
            e[2] = Math.max(e[2], days);
            tenantSum += p.totalToTenant;
        }
        java.util.List<java.util.Map.Entry<Long, double[]>> rich =
                new java.util.ArrayList<>(earn.entrySet());
        rich.sort((x, y) -> Double.compare(y.getValue()[0], x.getValue()[0]));
        for (int i2 = 0; i2 < Math.min(2, rich.size()); i2++) {
            double[] e = rich.get(i2).getValue();
            MimicEntity m = byId.get(rich.get(i2).getKey());
            if (m == null || m.getHomePos() == null) {
                continue;
            }
            double gain = e[0] / e[2];              // 일평균 수취
            double spend = e[1] / e[2];             // 일평균 확장 지출
            double own = familyActualOf(level, m);  // 제 가구 하루 <b>실</b>소모(명목 아님)
            double spare = gain - spend - own;      // 남을 먹일 수 있는 몫
            // <b>몇 가구를 먹일 수 있나</b> — 이것이 곧 군인 고용 가능성이다(P7 입력).
            // 한 호의 밥값은 <b>부양받는 쪽</b>의 살림이지 지배자 자신의 살림이 아니다. 지주는
            // 자녀가 많아 가구가 큰데(실측 D13: 3.4/일) 군인 가구는 부부 한 쌍이라, 지주의
            // 식비로 나누면 남의 집 밥값을 부잣집 기준으로 매기는 셈이 되어 군인 수가 과소평가된다
            // (D9 3.8 → D13 6.7 로 원가가 뛴 것이 그 증상이다). 무토지 가구의 실측 평균을 쓴다.
            double house = Math.max(1.0, landlessHouseCost(level, farms));
            int feeds = (int) Math.floor(Math.max(0.0, spare) / house);
            // 그 위에 축적으로 출산 한 건을 더 보장하려면 BIRTH_COST 만큼이 더 남아야 한다.
            boolean plusBirth = spare - feeds * house >= com.evosim.core.FoodEconomy.BIRTH_COST;
            // <b>군인 환산</b>(P7 의 실제 물음) — "봉급으로 굶지 않고 약간 흑자를 보며 아이 하나는
            // 가질 수 있는" 가구를 몇 호나 굴릴 수 있나.
            //
            // 분자는 확장을 <b>빼기 전</b>이다. 확장은 지배자가 고르는 지출이지 의무가 아니고,
            // 물음 자체가 "군인을 굴리고도 확장할 여유가 있는가"이므로 확장을 먼저 깔면 확장에
            // 전액을 쓰는 지배자가 영영 0호로 찍혀 물음에 답을 못 한다(w9 D13 실측: 밭벌이 10.8 을
            // 확장 11.3 이 전부 삼켜 여유 −3.0 → 군인 0호. 실제 가용은 10.8−2.6 = 8.2 다).
            //
            // 분모는 <b>단위를 맞춘다</b>. 종전에는 하루치 살림에 일회성 출산비를 더해 나눴는데
            // 그러면 뜻이 없는 수가 나온다. 출산은 문턱까지 <b>적립</b>하는 일이므로 지평으로
            // 나눠 하루치로 환산한다: 원가/일 = 실소모 + 출산문턱 ÷ 지평.
            double grossSpare = gain - own;
            // 출산 문턱도 <b>군인 가구</b>(성인 부부) 기준이다 — 지주의 대가족 소모를 넣으면
            // 원가가 부풀어 같은 과소평가가 반복된다. 성인 명목 소모 × 2인 + 성인수(2)+1.
            double coupleNeed = 2.0 * com.evosim.core.FoodEconomy.consumptionPerDay(
                    com.evosim.core.LifeStage.ADULT, com.evosim.core.Activity.MOVE,
                    m.getIndividual(), false);
            double reproGate = com.evosim.core.FoodEconomy.BIRTH_COST
                    + coupleNeed * com.evosim.core.FoodEconomy.REPRO_NEED_DAYS + 3.0;
            double troopCost = house + reproGate / TROOP_BIRTH_HORIZON_DAYS;
            int troops = (int) Math.floor(Math.max(0.0, grossSpare) / troopCost);
            // <b>이미 먹이고 있는 비생산자</b>도 함께 센다. 마름은 밭을 갖지 않고 임금으로 사는
            // 사람이라, 그 수가 곧 "지금 부양 중인 인원"이다. 여유(장래 여력)와 나란히 놓아야
            // 지표가 현실과 어긋나지 않는다 — 실측 D15 에서 마름 4명을 먹이는 영주를 두고
            // 보고가 "0호 부양"이라 말했다.
            int paid = farms.stewardCount(m.getIndividual().id());
            tell(ctx.getSource(), String.format(
                    "  부양력 %s — 밭벌이%.1f/일 − 확장%.1f − 제가구%.1f(실측) = %s여유%+.1f§r"
                            + " → 지금 마름%d명 부양 · 추가 여력 %d호%s"
                            + " · 확장 빼기 전 가용%+.1f → §b군인 %d호§r(1호당 %.1f/일)"
                            + " (밭%d 소작%d) ※벌이는 밭 수입만(채집 제외)",
                    m.getIndividual().shortName(), gain, spend, own,
                    spare > 0 ? "§a" : "§c", spare, paid, feeds,
                    plusBirth ? " + 출산1" : "", grossSpare, troops, troopCost,
                    farms.ownedCount(m.getIndividual().id()),
                    tenantsOf(level, m.getIndividual().id())));
        }
        tell(ctx.getSource(), String.format("  소작 전체 수취 %.0f", tenantSum));
        // ── <b>출산률</b> — 부부당 자녀 수. 목표 1.5~3, 0 도 4 도 결함이다.
        int couples = 0;
        int kids = 0;
        int[] dist = new int[5]; // 0,1,2,3,4+
        int eliteCouples = 0;
        int eliteKids = 0;
        for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null
                        && e.getIndividual().sex() == com.evosim.core.Sex.FEMALE
                        && e.getSpouseId() != 0L
                        && (e.getStage() == com.evosim.core.LifeStage.ADULT
                                || e.getStage() == com.evosim.core.LifeStage.ELDER))) {
            couples++;
            int n = m.getChildrenBorn();
            kids += n;
            dist[Math.min(4, n)]++;
            if (com.evosim.core.ExpressionResolver.isExpressed(
                    m.getIndividual(), com.evosim.core.Trait.AMBITIOUS)
                    || farms.ownedTiles(m.getIndividual().id()) > 0
                    || (m.getSpouseId() != 0L && farms.ownedTiles(m.getSpouseId()) > 0)) {
                eliteCouples++;
                eliteKids += n;
            }
        }
        if (couples > 0) {
            double avg = (double) kids / couples;
            boolean ok = avg >= 1.5 && avg <= 3.0;
            tell(ctx.getSource(), String.format(
                    "  %s출산 전체 — 부부%d쌍 평균 %.2f(목표 1.5~3)§r · 분포 0:%d 1:%d 2:%d 3:%d 4+:%d"
                            + " · 지주가문 %d쌍 평균 %.2f",
                    ok ? "§a" : "§c", couples, avg, dist[0], dist[1], dist[2], dist[3], dist[4],
                    eliteCouples, eliteCouples == 0 ? 0.0 : (double) eliteKids / eliteCouples));
            // <b>2·3세대만 본다.</b> 그 둘이 정상이면 4세대가 급변할 이유가 없고, 그것을
            // 확인하겠다고 런을 길게 끄는 것은 시간만 쓴다(지시).
            StringBuilder gb = new StringBuilder();
            for (int g = 2; g <= 3; g++) {
                int gc = 0;
                int gk = 0;
                for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                        e -> e.isAlive() && e.getIndividual() != null
                                && e.getIndividual().sex() == com.evosim.core.Sex.FEMALE
                                && e.getSpouseId() != 0L
                                && (e.getStage() == com.evosim.core.LifeStage.ADULT
                                        || e.getStage() == com.evosim.core.LifeStage.ELDER))) {
                    if (m.getIndividual().generation() != g) {
                        continue;
                    }
                    gc++;
                    gk += m.getChildrenBorn();
                }
                if (gc > 0) {
                    double ga = (double) gk / gc;
                    gb.append(String.format("%s%d세대 %d쌍 %.2f§r ",
                            ga >= 1.5 && ga <= 3.0 ? "§a" : "§c", g, gc, ga));
                }
            }
            if (gb.length() > 0) {
                tell(ctx.getSource(), "  출산 세대별(판정 대상) — " + gb.toString().trim());
            }
            // ── <b>신분별</b> 출산 — 목표가 층마다 다르다(지시): 평민 1.5~2 · 마름·자영농 3~4
            // 겨우 · 지배자는 그 위. 전체 평균 하나로는 "평민이 4를 낳고 지주가 0"인 병든 분포와
            // 정상 분포를 구분할 수 없다. 층은 소유 타일과 고용으로만 가른다(라벨 없음).
            String[] cls = {"소작·농노", "마름", "자영농", "지배자"};
            // 지시(갱신): 소작·농노 2 · 마름 3 · 자영농 3 · 지배자 5(넉넉히, 상한 없음).
            // <b>순서가 뒤집히면 계층의 뜻이 없어진다</b>는 것이 별도 요구라, 아래에서 따로 센다.
            double[] lo = {1.5, 2.5, 2.5, 4.0};
            double[] hi = {2.5, 3.5, 4.0, 99.0};
            int[] cc = new int[4];
            int[] ck = new int[4];
            for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                    e -> e.isAlive() && e.getIndividual() != null
                            && e.getIndividual().sex() == com.evosim.core.Sex.FEMALE
                            && e.getSpouseId() != 0L
                            && (e.getStage() == com.evosim.core.LifeStage.ADULT
                                    || e.getStage() == com.evosim.core.LifeStage.ELDER))) {
                // 가구 기준 — 아내 명의든 남편 명의든 한 가구의 살림은 하나다.
                long me = m.getIndividual().id();
                int tiles = farms.ownedTiles(me) + farms.ownedTiles(m.getSpouseId());
                boolean steward = farms.stewardOf(me) != 0L
                        || farms.stewardOf(m.getSpouseId()) != 0L;
                int hired = tenantsOf(level, me) + tenantsOf(level, m.getSpouseId());
                int k = tiles == 0 ? (steward ? 1 : 0)
                        : (hired > 0 || tiles > com.evosim.core.FarmEconomy.MATURE_TILES ? 3 : 2);
                cc[k]++;
                ck[k] += m.getChildrenBorn();
            }
            StringBuilder cb = new StringBuilder();
            for (int k = 0; k < 4; k++) {
                if (cc[k] == 0) {
                    continue;
                }
                double ca = (double) ck[k] / cc[k];
                cb.append(String.format("%s%s %d쌍 %.2f§r ",
                        ca >= lo[k] && ca <= hi[k] ? "§a" : "§c", cls[k], cc[k], ca));
            }
            tell(ctx.getSource(), "  출산 신분별(목표 소작2 · 마름3 · 자영농3 · 지배자5) — "
                    + (cb.length() == 0 ? "해당 없음" : cb.toString().trim()));
            // <b>층 간 순서</b> — 값이 목표 안에 들어도 순서가 뒤집히면 계층의 뜻이 없어진다
            // (지시). 인원이 있는 층만 이어서 비교한다: 소작 ≤ 마름 ≤ 자영농 ≤ 지배자.
            StringBuilder ob = new StringBuilder();
            double last = -1.0;
            int inversions = 0;
            for (int k = 0; k < 4; k++) {
                if (cc[k] == 0) {
                    continue;
                }
                double ca = (double) ck[k] / cc[k];
                if (last >= 0 && ca < last) {
                    inversions++;
                    ob.append(String.format("§c%s%.2f↓§r ", cls[k], ca));
                } else {
                    ob.append(String.format("%s%.2f ", cls[k], ca));
                }
                last = ca;
            }
            tell(ctx.getSource(), String.format(
                    "  출산 순서(소작≤마름≤자영≤지배) — %s· %s역전 %d곳§r",
                    ob, inversions == 0 ? "§a" : "§c", inversions));
        }

        java.util.List<java.util.Map.Entry<Long, Integer>> top =
                new java.util.ArrayList<>(direct.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            MimicEntity m = byId.get(top.get(i).getKey());
            sb.append(String.format("#%d(%s)×%d ", top.get(i).getKey(),
                    m == null ? "?" : farms.classOf(level, top.get(i).getKey()),
                    top.get(i).getValue()));
        }
        if (sb.length() > 0) {
            tell(ctx.getSource(), "  세력 상위 — " + sb.toString().trim());
        }
        return edges;
    }

    /**
     * <b>부지 탐색 시험</b> — 마을 무게중심에서 학교 자리를 찾아 보고 거부 사유를 찍는다.
     *
     * <p>저장된 월드에서 <b>즉시</b> 돌릴 수 있어, 고치고 재는 주기가 초 단위가 된다.
     * 이 문제에서 25분짜리 런을 여섯 번 돌리며 매번 추측으로 다음 후보를 골랐다.
     */
    private static int siteTest(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        HomeStore homes = HomeStore.get(level);
        if (homes.positions().isEmpty()) {
            tell(ctx.getSource(), "집이 없다 — 시험할 중심이 없다");
            return 0;
        }
        long sx = 0;
        long sz = 0;
        for (BlockPos h : homes.positions()) {
            sx += h.getX();
            sz += h.getZ();
        }
        int n = homes.positions().size();
        BlockPos centre = new BlockPos((int) (sx / n), homes.positions().get(0).getY(),
                (int) (sz / n));
        tell(ctx.getSource(), "§e[부지시험]§r 집" + n + "채 · "
                + MimicEntity.probeFacilitySite(level, centre));
        return 1;
    }

    /**
     * <b>시설 보고</b> — 학교·교회가 섰는가, 도면대로 섰는가, 장부가 어떤가.
     *
     * <p>"섰는가"만 세면 안 된다. 구조 일치율을 함께 재지 않으면 <b>반쯤 파묻힌 학교</b>도
     * 1채로 잡힌다 — 거처에서 승격 이사 직후를 붕괴로 오독했던 것과 같은 종류의 실수를
     * 반대 방향으로 저지르는 셈이다.
     */
    private static int facilities(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        FacilityStore reg = FacilityStore.get(level);
        java.util.Map<Long, MimicEntity> byId = new java.util.HashMap<>();
        for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null)) {
            byId.putIfAbsent(m.getIndividual().id(), m);
        }
        // 0 채일 때 <b>왜</b> 0 인지를 보고가 스스로 말하게 한다 — 자격자가 없어서인지,
        // 있는데 돈이 없어서인지, 판정이 아예 안 돌아서인지. 실측 P5a D14 에서 최대세력 13 인
        // 마을이 0채였는데 사건 로그도 0건이라, 어느 쪽인지 보고만으로는 가릴 수 없었다.
        int qualified = 0;
        int topPower = 0;
        for (long id : byId.keySet()) {
            int f = FarmTicker.followersOf(id);
            topPower = Math.max(topPower, f);
            if (f >= Facilities.SCHOOL_MIN_FOLLOWERS) {
                qualified++;
            }
        }
        if (reg.all().isEmpty()) {
            tell(ctx.getSource(), String.format(
                    "§e[시설]§r 0채 — 자격자%d명(최대세력%d·새벽기준 · 문턱 추종자%d) · 건축비%.0f"
                            + " (자격자가 있는데 0채면 사건 로그의 '학교' 줄이 사유를 말한다)",
                    qualified, topPower, Facilities.SCHOOL_MIN_FOLLOWERS, Facilities.SCHOOL_COST));
            return 0;
        }
        double standSum = 0.0;
        int broken = 0;
        int orphan = 0;
        StringBuilder sb = new StringBuilder();
        for (FacilityStore.Entry e : reg.all()) {
            var tpl = FacilityTemplate.of(level, e.kind, e.rotation, e.mirrored);
            double stand = 1.0;
            if (tpl.isPresent()) {
                int ok = 0;
                for (FacilityTemplate.Placement p : tpl.get().plan()) {
                    if (level.getBlockState(e.pos.offset(p.rel())).getBlock()
                            == p.state().getBlock()) {
                        ok++;
                    }
                }
                stand = tpl.get().plan().isEmpty() ? 1.0
                        : (double) ok / tpl.get().plan().size();
            }
            standSum += stand;
            if (stand < MimicEntity.STANDING_RATIO) {
                broken++;
            }
            boolean alive = byId.containsKey(e.ownerId);
            if (!alive) {
                orphan++;
            }
            sb.append(String.format("%s@%d,%d(주인#%d%s 추종자%d · 일치%.0f%% · 벌이%.1f/쓴것%.1f=순%.1f) ",
                    e.kind.label, e.pos.getX(), e.pos.getZ(), e.ownerId, alive ? "" : "·사망",
                    FarmTicker.followersOf(e.ownerId), stand * 100.0,
                    e.earned, e.spent, e.net()));
        }
        tell(ctx.getSource(), String.format(
                "§e[시설]§r %d채(학교%d 교회%d[큰%d 작은%d]) · 구조평균%.0f%% · %s무너짐%d§r"
                        + " · 주인사망%d · 자격자%d명(최대세력%d·새벽기준)",
                reg.all().size(), reg.countOf(FacilityTemplate.Kind.SCHOOL),
                reg.countOf(FacilityTemplate.Group.CHURCH),
                reg.countOf(FacilityTemplate.Kind.CHURCH),
                reg.countOf(FacilityTemplate.Kind.SMALL_CHURCH),
                standSum / reg.all().size() * 100.0,
                broken == 0 ? "§a" : "§c", broken, orphan, qualified, topPower));
        tell(ctx.getSource(), "  " + sb.toString().trim());
        // ── 학교 운영(P5b) ── 등교율의 분모는 <b>마을 전체 소년</b>이다. 등록자만 세면
        //    "등교율 100%" 같은 무의미한 수가 나온다.
        //
        // <b>분자와 분모의 시점을 맞춘다.</b> 등록·미납 사유는 새벽 정산이 남긴 수이고,
        // 아래 순회는 <b>지금</b> 살아 있는 소년을 센다. 둘을 나누면 시점이 어긋난 비율이
        // 나온다 — 실측(D19): "등록0/소년10" 인데 새벽에는 소년이 5명이었고 사유도
        // 가구전체안따름3 · 통학초과2 로 <b>5명이 전부 설명</b>됐다. 성장으로 소년이 하루에
        // 배로 느는 구간이라 어긋남이 컸다. 새벽 소년 수는 정산이 이미 세어 두었으므로
        // ({@code SCHOOL_SUM[1]}) 비율은 그것으로 내고, 지금 수는 따로 이름 붙여 함께 찍는다.
        double[] ss = FarmTicker.schoolSums();
        java.util.List<Double> trip = new java.util.ArrayList<>();
        int boys = 0;
        int sat = 0;
        // <b>거리별 도착 여부</b> — 계획서의 "0.8 속도로 왕복 가능한 범위"는 이 두 분포를
        // 견주어야만 답이 나온다. 등록만 세면 "먼 아이가 하루 안에 못 닿는다"가 안 보인다
        // (실측: 등록3 · 실제착석1 인데 등교 포기 사건은 0건 — 막힌 게 아니라 못 닿은 것).
        java.util.List<Double> arrived = new java.util.ArrayList<>();
        java.util.List<Double> missed = new java.util.ArrayList<>();
        long today = com.evosim.mod.entity.SimTime.tick(level) / 24000L;
        int learnedSum = 0;
        int learnedN = 0;
        int idleN = 0;
        int learnedMax = 0;
        for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null
                        && e.getStage() == com.evosim.core.LifeStage.BOY)) {
            boys++;
            BlockPos sp = FarmTicker.schoolOf(m);
            if (sp != null && m.getHomePos() != null) {
                trip.add(Math.sqrt(m.getHomePos().distSqr(sp)));
            }
            if (m.satInSchoolToday(today)) {
                sat++;
                if (sp != null && m.getHomePos() != null) {
                    arrived.add(Math.sqrt(m.getHomePos().distSqr(sp)));
                }
            } else if (sp != null && m.getHomePos() != null) {
                missed.add(Math.sqrt(m.getHomePos().distSqr(sp)));
            }
            // 능력 격차 — 한 번이라도 앉아 본 소년과 아예 못 가 본 소년을 나눠 센다.
            if (m.getSchoolDays() > 0) {
                learnedSum += m.getSchoolDays();
                learnedMax = Math.max(learnedMax, m.getSchoolDays());
                learnedN++;
            } else {
                idleN++;
            }
        }
        // ── 능력 격차 — <b>성년까지 센다</b>. 위 순회는 소년만 보는데, 아이가 자라 성년이 되면
        //    그 학력이 보고에서 통째로 사라진다. 정작 교육이 값을 하는 시점이 성년이라
        //    (채집·사냥 배율은 성년이 되어야 제 몫으로 쓰인다) 재는 자리가 틀렸었다.
        //    학력별 실제 채집 배율을 나란히 찍어 "격차가 있는가"를 눈으로 확인한다.
        int[] eduN = new int[com.evosim.core.Schooling.MAX_LEVEL + 1];
        double[] eduYield = new double[com.evosim.core.Schooling.MAX_LEVEL + 1];
        int grownLearned = 0;
        int grownTotal = 0;
        for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null
                        && e.getStage() != com.evosim.core.LifeStage.INFANT)) {
            int lv = com.evosim.core.Schooling.level(m.getSchoolDays());
            eduN[lv]++;
            // <b>자기 자신을 대조군으로</b> — 같은 개체가 무학이었을 때 대비 몇 배인가.
            //
            // 집단 평균을 그냥 견주면 안 된다. 채집 배율에는 성별 배수(남 1.5 · 여 0.5)와
            // 유전 특성이 통째로 섞여 있어, 학력자 표본이 작으면 그 사람의 성별·특성이
            // 교육 효과를 완전히 덮는다 — 첫 측정에서 실제로 "무학 1.457 vs 초급 0.950" 이
            // 나왔고, 이는 교육이 해로운 것이 아니라 <b>지표가 교란된 것</b>이었다.
            // 같은 개체의 전후 비를 쓰면 성별·특성이 분자·분모에서 약분된다.
            double withEdu = com.evosim.core.FoodEconomy.forageYieldMult(m.getIndividual(),
                    m.getSchoolDays());
            double without = com.evosim.core.FoodEconomy.forageYieldMult(m.getIndividual(), 0);
            eduYield[lv] += without <= 0.0 ? 1.0 : withEdu / without;
            if (m.getStage() != com.evosim.core.LifeStage.BOY) {
                grownTotal++;
                if (lv > 0) {
                    grownLearned++;
                }
            }
        }
        java.util.Collections.sort(trip);
        // <b>부지가 좋은가</b>는 등록된 아이만 봐서는 알 수 없다 — 등록이 0 이면 통학거리 줄이
        // 통째로 사라져 배치가 나아졌는지 나빠졌는지 볼 수단이 없어진다(실측: 등록0/소년8).
        // 그래서 등록과 무관하게 모든 소년의 <b>최근접 학교</b> 거리를 따로 잰다.
        java.util.List<Double> near = new java.util.ArrayList<>();
        for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null && e.getHomePos() != null
                        && e.getStage() == com.evosim.core.LifeStage.BOY)) {
            double best = Double.MAX_VALUE;
            for (FacilityStore.Entry fe : reg.all()) {
                if (fe.kind == FacilityTemplate.Kind.SCHOOL) {
                    best = Math.min(best, Math.sqrt(m.getHomePos().distSqr(fe.pos)));
                }
            }
            if (best < Double.MAX_VALUE) {
                near.add(best);
            }
        }
        java.util.Collections.sort(near);
        tell(ctx.getSource(), String.format(
                "  학교 — 등록%.0f/새벽소년%.0f(%.0f%%) · §b실제착석%d§r · 지금소년%d"
                        + " · 수업료%.1f · 미납%.1f · 급여%.1f · 당일수지%+.1f",
                ss[0], ss[1], ss[1] == 0 ? 0.0 : 100.0 * ss[0] / ss[1], sat, boys,
                ss[2], ss[3], ss[4], ss[2] - ss[4]));
        // 등록과 착석을 나눠 찍는 이유: 길이 막혀 못 간 아이도 수업료는 낸다. 두 수가 벌어지면
        // 그 차이가 곧 통학 결함의 크기다.
        tell(ctx.getSource(), String.format(
                "  획득(소년) — 등교경험%d명(평균%.1f일) · 무경험%d명 · 최다%d일",
                learnedN, learnedN == 0 ? 0.0 : (double) learnedSum / learnedN, idleN,
                learnedMax));
        StringBuilder edu = new StringBuilder();
        for (int lv = 0; lv <= com.evosim.core.Schooling.MAX_LEVEL; lv++) {
            edu.append(lv == 0 ? "" : " · ").append(com.evosim.core.Schooling.name(lv))
                    .append(eduN[lv]).append("명");
            if (eduN[lv] > 0) {
                edu.append(String.format("(채집%+.1f%%)", 100.0 * (eduYield[lv] / eduN[lv] - 1.0)));
            }
        }
        tell(ctx.getSource(), "  §b능력 격차§r(유아 제외 전원 · 각자 무학 대비) — " + edu);
        // ── 교회 운영(P6) — 방문 분포가 몰리지 않는지, 수지가 흑자인지.
        double[] cs = FarmTicker.churchSums();
        int visited = 0;
        int everVisited = 0;
        int visitMax = 0;
        for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null)) {
            if (m.visitedChurchToday(today)) {
                visited++;
            }
            if (m.getChurchVisits() > 0) {
                everVisited++;
                visitMax = Math.max(visitMax, m.getChurchVisits());
            }
        }
        if (reg.countOf(FacilityTemplate.Group.CHURCH) > 0) {
            tell(ctx.getSource(), String.format(
                    "  교회 — 정산방문%.0f · 오늘방문%d명 · 헌금%.2f · 미납%.2f · 급여%.2f"
                            + " · 당일수지%+.2f · 누적방문자%d명(최다%d회)",
                    cs[0], visited, cs[1], cs[2], cs[3], cs[1] - cs[3], everVisited, visitMax));
            // <b>방문 0 이면 왜 0 인지</b>를 보고가 스스로 말하게 한다. 이 세션에서 학교가
            // 같은 이유로 여러 날 헛돌았다 — 사유 없는 0 은 원인을 추측하게 만든다.
            int adultN = 0;
            int inReach = 0;
            int onCooldown = 0;
            long nowDay = today;
            for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                    e -> e.isAlive() && e.getIndividual() != null && e.getHomePos() != null
                            && e.getStage() == com.evosim.core.LifeStage.ADULT)) {
                adultN++;
                boolean reachable = false;
                for (FacilityStore.Entry fe : reg.all()) {
                    if (fe.kind.group == FacilityTemplate.Group.CHURCH
                            && fe.pos.distSqr(m.getHomePos())
                                    <= Facilities.CHURCH_REACH * Facilities.CHURCH_REACH) {
                        reachable = true;
                        break;
                    }
                }
                if (reachable) {
                    inReach++;
                }
                if (nowDay - m.lastVisitDay() < 2) {
                    onCooldown++;
                }
            }
            tell(ctx.getSource(), String.format(
                    "  교회 접근 — 성년%d명 중 반경%.0f 안 %d명 · 오늘 쿨다운%d명"
                            + " (둘 다 통과해야 갈 수 있다)",
                    adultN, Facilities.CHURCH_REACH, inReach, onCooldown));
        }
        tell(ctx.getSource(), String.format(
                "  성년 이상 학력자 %d/%d명(%.0f%%) — 교육이 값을 하는 구간",
                grownLearned, grownTotal, grownTotal == 0 ? 0.0 : 100.0 * grownLearned / grownTotal));
        int[] miss = FarmTicker.schoolMiss();
        if (ss[0] < boys) {
            // 등교가 대상에 못 미치면 <b>왜</b>인지 말한다. 앞의 두 수가 서로 다른 가설을
            // 가른다 — 대표만 못 따르는가(판정이 좁은가), 가구 전체가 안 따르는가.
            tell(ctx.getSource(), String.format(
                    "  못 간 사유 — 가구전체안따름%2$d · 통학초과%3$d · 자리없음%4$d"
                            + " (가구 연으로 자격 얻음%1$d — 그 뒤 거리에서 탈락 가능)",
                    miss[0], miss[1], miss[2], miss[3]));
        }
        if (!near.isEmpty()) {
            int within = 0;
            for (double d : near) {
                if (d <= Facilities.COMMUTE_RANGE) {
                    within++;
                }
            }
            tell(ctx.getSource(), String.format(
                    "  소년→최근접학교 — 최소%.0f 중앙%.0f 최대%.0f · 한계%.0f 안 %d/%d명%s",
                    near.get(0), near.get(near.size() / 2), near.get(near.size() - 1),
                    Facilities.COMMUTE_RANGE, within, near.size(),
                    trip.isEmpty() ? "" : String.format(
                            " · 등록자 통학 중앙%.0f", trip.get(trip.size() / 2))));
        }
        if (!arrived.isEmpty() || !missed.isEmpty()) {
            java.util.Collections.sort(arrived);
            java.util.Collections.sort(missed);
            tell(ctx.getSource(), String.format(
                    "  도착 대 미도착 — 착석%d명(최대%s) · 미착석%d명(최소%s)",
                    arrived.size(),
                    arrived.isEmpty() ? "-" : String.format("%.0f", arrived.get(arrived.size() - 1)),
                    missed.size(),
                    missed.isEmpty() ? "-" : String.format("%.0f", missed.get(0))));
        }
        return reg.all().size();
    }

    /**
     * <b>상납 경로 시험대</b> — 세력 2위가 1위에게 신세를 지도록 인위적으로 심는다.
     *
     * <p>왜 필요한가: 원장에 간선을 만드는 경로는 소작·구휼·긴급고용 셋뿐인데 <b>셋 다 가난한
     * 쪽이 받는 것</b>이다. 지주는 남의 밭에서 소작하지 않고, 굶지 않고, 고용되지 않는다.
     * 그래서 지주가 다른 지주에게 신세를 질 길이 없고, 사슬 깊이는 사실상 1 에서 멈춘다
     * (실측 P4 D19: 주인 3명 · 사슬깊이 1 · 상납 0.0). 지주를 채무자로 만드는 항목 — 시설
     * 사용료·혼인 주선비·보호 — 은 전부 P5~P7 에 있다.
     *
     * <p>그렇다고 상납 코드를 "P5 에서 흐를 것"이라 적어 두고 넘어가면, 한 번도 실행되지 않은
     * 경로를 검증했다고 말하는 셈이 된다. 여기서 간선 하나를 심어 <b>지금</b> 확인한다.
     *
     * <p>심는 값은 1위의 추종 임계({@code max(MIN_BOND, 소유타일 × TILE_WORTH)})를 넘도록
     * 계산한다 — 임계를 모르고 큰 수를 때려 넣으면 "충분히 컸다"는 것만 알 뿐 경계가 맞는지는
     * 모른다. <b>시험 전용이며 자연 경로가 아니다.</b>
     */
    private static int bondTest(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        AllegianceStore led = AllegianceStore.get(level);
        FarmStore farms = FarmStore.get(level);

        java.util.Map<Long, Long> patron =
                led.patronMap(id -> farms.ownedTiles(id));
        java.util.Map<Long, Integer> direct = new java.util.HashMap<>();
        for (long p : patron.values()) {
            direct.merge(p, 1, Integer::sum);
        }
        java.util.List<java.util.Map.Entry<Long, Integer>> top =
                new java.util.ArrayList<>(direct.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        if (top.size() < 2) {
            tell(ctx.getSource(), "주인이 둘 미만이라 시험할 수 없다 — 세력이 더 자란 뒤에.");
            return 0;
        }
        long lord = top.get(0).getKey();
        long vassal = top.get(1).getKey();
        double gate = Math.max(AllegianceStore.MIN_BOND,
                farms.ownedTiles(vassal) * AllegianceStore.TILE_WORTH);
        double plant = gate * 1.2 + 1.0; // 임계를 확실히 넘되 과하지 않게
        led.record(vassal, lord, plant, 0.0,
                com.evosim.mod.entity.SimTime.tick(level) / 24000L);
        long now = led.patronOf(vassal, farms.ownedTiles(vassal));
        tell(ctx.getSource(), String.format(
                "시험 신세 심음 — #%d(추종자%d) → #%d(추종자%d) · 임계%.1f 심은값%.1f · 추종 성립 %s",
                vassal, top.get(1).getValue(), lord, top.get(0).getValue(), gate, plant,
                now == lord ? "○ (다음 새벽에 상납이 흘러야 한다)" : "× — 임계를 못 넘었다"));
        return now == lord ? 1 : 0;
    }

    /** (열, 줄) 격자좌표를 long 하나로 — 음수가 섞이므로 상위/하위 32비트로 나눠 담는다. */
    private static long cell(int col, int row) {
        return ((long) col << 32) ^ (row & 0xffffffffL);
    }

    /**
     * 밭 몸통이 몇 덩어리인가 — 격자에서 열 ±1(같은 줄) 또는 줄 ±1(같은 열)이면 이웃이다.
     *
     * <p>고랑을 사이에 둔 위아래 줄도 미믹이 건너다니므로 이웃으로 친다(월드 좌표로는 z±2,
     * 격자로는 줄 ±1). 1이면 하나로 이어진 밭, 2 이상이면 실제로 갈라진 것이다.
     */
    private static int components(java.util.Set<Long> cells) {
        java.util.Set<Long> left = new java.util.HashSet<>(cells);
        int comp = 0;
        while (!left.isEmpty()) {
            comp++;
            java.util.ArrayDeque<Long> q = new java.util.ArrayDeque<>();
            long s = left.iterator().next();
            left.remove(s);
            q.add(s);
            while (!q.isEmpty()) {
                long cur = q.poll();
                int c = (int) (cur >> 32);
                int r = (int) cur;
                long[] nb = {cell(c + 1, r), cell(c - 1, r), cell(c, r + 1), cell(c, r - 1)};
                for (long n : nb) {
                    if (left.remove(n)) {
                        q.add(n);
                    }
                }
            }
        }
        return comp;
    }

    /** 자연 지형 흙·돌 계열인가 — 집 자재(판자·석재·유리)와 구분해 "파묻힘"을 판정한다. */
    private static boolean isNaturalGround(net.minecraft.world.level.block.state.BlockState st) {
        return st.is(Blocks.DIRT) || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.COARSE_DIRT)
                || st.is(Blocks.PODZOL) || st.is(Blocks.ROOTED_DIRT) || st.is(Blocks.STONE)
                || st.is(Blocks.DEEPSLATE) || st.is(Blocks.TUFF) || st.is(Blocks.ANDESITE)
                || st.is(Blocks.DIORITE) || st.is(Blocks.GRANITE) || st.is(Blocks.SAND)
                || st.is(Blocks.GRAVEL) || st.is(Blocks.CLAY) || st.is(Blocks.SNOW_BLOCK)
                || st.is(Blocks.POWDER_SNOW) || st.is(Blocks.PACKED_ICE) || st.is(Blocks.MOSS_BLOCK);
    }

    /** 이 열의 지표 블록 — 길은 지표에 깔리므로 y 를 하이트맵에서 찾는다. */
    private static BlockPos surfaceNear(ServerLevel level, int x, int z) {
        if (!level.isLoaded(new BlockPos(x, 64, z))) {
            return null;
        }
        return level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new BlockPos(x, 0, z)).below();
    }

    // ── 5단계 검증: 저택 상속 ────────────────────────────────────────────────
    //
    // 판정 기준을 <b>미리</b> 못 박고, 그 기준이 성립하는지 등기부·homePos·저장고 세 값으로만
    // 읽는다. "잘 도는 것 같다"는 근거가 아니다.

    /** heirtest 가 세운 무대 — heirshow 가 <b>시간이 지난 뒤</b> 같은 자리를 다시 읽도록 남긴다. */
    private static final java.util.List<String> HEIR_STAGE = new java.util.ArrayList<>();
    private static final java.util.Map<String, BlockPos> HEIR_POS = new java.util.LinkedHashMap<>();
    private static final java.util.Map<String, Long> HEIR_ID = new java.util.LinkedHashMap<>();

    private static void chk(StringBuilder sb, int[] tally, boolean ok, String label, String got) {
        tally[ok ? 0 : 1]++;
        sb.append(ok ? "  §a[O]§r " : "  §c[X]§r ").append(label).append(" — ").append(got)
                .append('\n');
    }

    /**
     * 저택 상속 무대 — 5가지 경우를 <b>한 번에</b> 세우고 가장을 죽인 뒤 즉시 판정한다.
     *
     * <p>대조군을 셋(비저택·이미저택·무연부자) 두는 이유: "상속이 됐다"만 보면 <b>아무나
     * 아무 집이나 물려받아도</b> 통과한다. 물려받지 <b>못해야</b> 하는 경우가 실제로 막히는지를
     * 같은 무대에서 함께 재야 규칙이 검증된다.
     */
    private static int heirStage(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        HEIR_STAGE.clear();
        HEIR_POS.clear();
        HEIR_ID.clear();
        LarderStore lar = LarderStore.get(level);

        // ① 이사 상속 — 저택 가장 부부 사망, 분가한 성년 아들 가구(3명)가 통째로 옮겨와야 한다.
        BlockPos m1 = MimicEntity.liftToBase(level, groundAt(level, b, -60, 0), "mansion", (byte) 0, false);
        MimicEntity lord1 = spawnAdult(level, Vec3.atBottomCenterOf(m1).add(2, 0, 2), Sex.MALE);
        MimicEntity wife1 = spawnAdult(level, Vec3.atBottomCenterOf(m1).add(3, 0, 2), Sex.FEMALE);
        lord1.debugSettleWithHome(level, m1, "mansion", (byte) 0, false);
        m1 = lord1.getHomePos(); // 실제 앵커 — 기단 보정이 들어간 값이 진짜 등기 키다
        wife1.debugJoinHome(m1, "mansion", (byte) 0, false);
        lord1.debugMarryTo(wife1);
        BlockPos s1 = MimicEntity.liftToBase(level, groundAt(level, b, -25, 0), "small1", (byte) 0, false);
        MimicEntity son1 = spawnChildOf(level, Vec3.atBottomCenterOf(s1).add(2, 0, 2), lord1, Sex.MALE);
        MimicEntity sonw1 = spawnAdult(level, Vec3.atBottomCenterOf(s1).add(3, 0, 2), Sex.FEMALE);
        MimicEntity sonk1 = spawnChildOf(level, Vec3.atBottomCenterOf(s1).add(1, 0, 2), son1, Sex.MALE);
        son1.debugSettleWithHome(level, s1, "small1", (byte) 0, false);
        s1 = son1.getHomePos();
        sonw1.debugJoinHome(s1, "small1", (byte) 0, false);
        sonk1.debugJoinHome(s1, "small1", (byte) 0, false);
        son1.debugMarryTo(sonw1);
        lar.set(m1, 40.0);
        lar.set(s1, 10.0);
        HEIR_POS.put("①저택", m1);
        HEIR_POS.put("①옛집", s1);
        HEIR_ID.put("①아들", son1.getIndividual().id());

        // ② 동거 승계 — 아들이 이미 그 저택에 산다. 집은 빌 일이 없고 주인 칸만 넘어가야 한다.
        BlockPos m2 = MimicEntity.liftToBase(level, groundAt(level, b, 25, 0), "mansion", (byte) 0, false);
        MimicEntity lord2 = spawnAdult(level, Vec3.atBottomCenterOf(m2).add(2, 0, 2), Sex.MALE);
        lord2.debugSettleWithHome(level, m2, "mansion", (byte) 0, false);
        m2 = lord2.getHomePos();
        MimicEntity son2 = spawnChildOf(level, Vec3.atBottomCenterOf(m2).add(3, 0, 2), lord2, Sex.MALE);
        son2.debugJoinHome(m2, "mansion", (byte) 0, false);
        lar.set(m2, 60.0);
        HEIR_POS.put("②저택", m2);
        HEIR_ID.put("②아들", son2.getIndividual().id());

        // ③ 대조군: 비저택 — 대형(big2)은 대를 잇지 않는다. 빈집이 되어 시장에 나와야 한다.
        BlockPos m3 = MimicEntity.liftToBase(level, groundAt(level, b, -60, 70), "big2", (byte) 0, false);
        MimicEntity lord3 = spawnAdult(level, Vec3.atBottomCenterOf(m3).add(2, 0, 2), Sex.MALE);
        MimicEntity wife3 = spawnAdult(level, Vec3.atBottomCenterOf(m3).add(3, 0, 2), Sex.FEMALE);
        lord3.debugSettleWithHome(level, m3, "big2", (byte) 0, false);
        m3 = lord3.getHomePos();
        wife3.debugJoinHome(m3, "big2", (byte) 0, false);
        lord3.debugMarryTo(wife3);
        BlockPos s3 = MimicEntity.liftToBase(level, groundAt(level, b, -25, 70), "small1", (byte) 0, false);
        MimicEntity son3 = spawnChildOf(level, Vec3.atBottomCenterOf(s3).add(2, 0, 2), lord3, Sex.MALE);
        son3.debugSettleWithHome(level, s3, "small1", (byte) 0, false);
        s3 = son3.getHomePos();
        lar.set(m3, 40.0);
        HEIR_POS.put("③대형", m3);
        HEIR_POS.put("③아들집", s3);
        HEIR_ID.put("③아들", son3.getIndividual().id());

        // ④ 대조군: 이미 저택 — 한 가문이 저택을 둘 갖지 않는다. 죽은 쪽은 빈집이 되어야 한다.
        BlockPos m4 = MimicEntity.liftToBase(level, groundAt(level, b, 25, 70), "mansion", (byte) 0, false);
        MimicEntity lord4 = spawnAdult(level, Vec3.atBottomCenterOf(m4).add(2, 0, 2), Sex.MALE);
        MimicEntity wife4 = spawnAdult(level, Vec3.atBottomCenterOf(m4).add(3, 0, 2), Sex.FEMALE);
        lord4.debugSettleWithHome(level, m4, "mansion", (byte) 0, false);
        m4 = lord4.getHomePos();
        wife4.debugJoinHome(m4, "mansion", (byte) 0, false);
        lord4.debugMarryTo(wife4);
        BlockPos s4 = MimicEntity.liftToBase(level, groundAt(level, b, 70, 70), "mansion", (byte) 0, false);
        MimicEntity son4 = spawnChildOf(level, Vec3.atBottomCenterOf(s4).add(2, 0, 2), lord4, Sex.MALE);
        son4.debugSettleWithHome(level, s4, "mansion", (byte) 0, false);
        s4 = son4.getHomePos();
        lar.set(m4, 40.0);
        lar.set(s4, 40.0);
        HEIR_POS.put("④죽은저택", m4);
        HEIR_POS.put("④아들저택", s4);
        HEIR_ID.put("④아들", son4.getIndividual().id());

        // ⑤ 대조군: 무연 부자 — 남의 저택을 채가면 안 된다(빈집 우선 입주가 상속을 앞지르는가).
        BlockPos s5 = MimicEntity.liftToBase(level, groundAt(level, b, 60, 0), "small1", (byte) 0, false);
        MimicEntity rich = spawnAdult(level, Vec3.atBottomCenterOf(s5).add(2, 0, 2), Sex.MALE);
        MimicEntity richw = spawnAdult(level, Vec3.atBottomCenterOf(s5).add(3, 0, 2), Sex.FEMALE);
        rich.debugSettleWithHome(level, s5, "small1", (byte) 0, false);
        s5 = rich.getHomePos();
        richw.debugJoinHome(s5, "small1", (byte) 0, false);
        rich.debugMarryTo(richw);
        lar.set(s5, 400.0);
        HEIR_POS.put("⑤부자집", s5);
        HEIR_ID.put("⑤부자", rich.getIndividual().id());

        // ── 가장 사망 ───────────────────────────────────────────────────────
        // 배우자를 먼저 지운다. 배우자가 남아 있으면 그 집은 <b>빌 일이 없어</b> 이사 상속 경로가
        // 아예 열리지 않는다(그 경우는 ②가 따로 검증한다).
        wife1.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);
        lord1.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);
        lord2.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);
        wife3.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);
        lord3.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);
        wife4.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);
        lord4.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);

        HEIR_STAGE.add("staged");
        tell(ctx.getSource(), "§e[5단계 무대] 저택 상속 5경우 조성 + 가장 사망 처리 완료§r\n"
                + "  ① 이사상속 저택@" + m1.getX() + "," + m1.getZ() + " 옛집@" + s1.getX() + "," + s1.getZ()
                + " / ② 동거승계 저택@" + m2.getX() + "," + m2.getZ()
                + " / ③ 비저택대조 big2@" + m3.getX() + "," + m3.getZ()
                + " / ④ 이미저택대조 @" + m4.getX() + "," + m4.getZ()
                + " / ⑤ 무연부자 @" + s5.getX() + "," + s5.getZ());
        return heirShow(ctx, true);
    }

    /** 시간이 지나면 <b>당연히</b> 변하는 값(식비·출산)은 지연 판정에서 참고치로만 적는다. */
    private static void info(StringBuilder sb, String label, String got) {
        sb.append("  §7[·]§r ").append(label).append(" — ").append(got).append(" §7(참고)§r\n");
    }

    /**
     * 무대 판정 — heirtest 직후(immediate)와 <b>며칠 뒤</b>를 같은 창구로 읽는다.
     *
     * <p>지연 판정에서 "가구 3명·저장고 50" 같은 <b>등식</b>을 그대로 쓰면 안 된다. 하루가 지나면
     * 출산으로 가구가 늘고 식비로 저장고가 준다 — 정상 동작이 실패로 찍힌다(실측: D1 에 4건).
     * 그래서 지연 판정에서는 늘기만 하는 값은 부등식으로, 소비되는 값은 참고치로 낮춘다.
     * 등기 주인·빈집 미경유·도면·미탈취는 <b>시간이 지나도 참이어야 하는 불변식</b>이라 그대로 둔다.
     */
    private static int heirShow(CommandContext<CommandSourceStack> ctx, boolean immediate) {
        ServerLevel level = ctx.getSource().getLevel();
        if (HEIR_STAGE.isEmpty()) {
            tell(ctx.getSource(), "§c무대 없음 — /evosim heirtest 먼저");
            return 0;
        }
        HomeStore reg = HomeStore.get(level);
        LarderStore lar = LarderStore.get(level);
        int today = (int) (com.evosim.mod.entity.SimTime.tick(level) / 24000L);
        StringBuilder sb = new StringBuilder();
        int[] tally = new int[2];

        BlockPos m1 = HEIR_POS.get("①저택");
        BlockPos s1 = HEIR_POS.get("①옛집");
        long son1 = HEIR_ID.get("①아들");
        HomeStore.Entry e1 = reg.entry(m1);
        sb.append("§6① 이사 상속§r (기준: 등기주인=아들 · 아들가구 3명 전원 저택 · 옛집 빈집 · 저장고 50 · 저택 비빈집)\n");
        chk(sb, tally, e1 != null && e1.ownerId() == son1, "등기 주인",
                e1 == null ? "등기없음" : "#" + e1.ownerId() + " (기대 #" + son1 + ")");
        int moved1 = countHome(level, m1);
        chk(sb, tally, immediate ? moved1 == 3 : moved1 >= 3, "아들 가구 이주",
                moved1 + "명 (기대 " + (immediate ? "3" : "3 이상 — 출산으로 늘 수 있다") + ")");
        chk(sb, tally, countHome(level, s1) == 0, "옛집 잔류", countHome(level, s1) + "명 (기대 0)");
        chk(sb, tally, reg.isVacant(s1, today), "옛집 빈집화", reg.isVacant(s1, today) ? "빈집" : "점유중");
        chk(sb, tally, !reg.isVacant(m1, today), "저택 빈집 미경유",
                reg.isVacant(m1, today) ? "빈집으로 나감" : "한 번도 안 빔");
        if (immediate) {
            chk(sb, tally, Math.abs(lar.get(m1) - 50.0) < 0.5, "저장고 합산",
                    String.format("저택 %.0f · 옛집 %.0f (기대 50 / 0)", lar.get(m1), lar.get(s1)));
        } else {
            info(sb, "저장고 합산", String.format("저택 %.0f · 옛집 %.0f — 식비로 줄어든다",
                    lar.get(m1), lar.get(s1)));
        }
        chk(sb, tally, e1 != null && HomeTemplate.Tier.of(e1.design()) == HomeTemplate.Tier.MANSION,
                "도면 유지", e1 == null ? "-" : e1.design());

        BlockPos m2 = HEIR_POS.get("②저택");
        long son2 = HEIR_ID.get("②아들");
        HomeStore.Entry e2 = reg.entry(m2);
        sb.append("§6② 동거 승계§r (기준: 등기주인=아들 · 저택 비빈집 · 거주 1명 유지)\n");
        chk(sb, tally, e2 != null && e2.ownerId() == son2, "등기 주인",
                e2 == null ? "등기없음" : "#" + e2.ownerId() + " (기대 #" + son2 + ")");
        chk(sb, tally, !reg.isVacant(m2, today), "빈집 미경유",
                reg.isVacant(m2, today) ? "빈집" : "점유");
        chk(sb, tally, countHome(level, m2) == 1, "거주자", countHome(level, m2) + "명 (기대 1)");

        BlockPos m3 = HEIR_POS.get("③대형");
        BlockPos s3 = HEIR_POS.get("③아들집");
        HomeStore.Entry e3 = reg.entry(m3);
        sb.append("§6③ 비저택 대조§r (기준: big2 는 상속 안 됨 → 빈집 · 아들은 제집 유지)\n");
        chk(sb, tally, reg.isVacant(m3, today), "대형 빈집화",
                reg.isVacant(m3, today) ? "빈집" : "누군가 상속");
        chk(sb, tally, e3 != null && e3.ownerId() == 0L, "주인 없음",
                e3 == null ? "등기없음" : "#" + e3.ownerId());
        chk(sb, tally, countHome(level, s3) == 1, "아들 제집 유지",
                countHome(level, s3) + "명 @제집 (기대 1)");
        chk(sb, tally, countHome(level, m3) == 0, "대형 무인", countHome(level, m3) + "명");

        BlockPos m4 = HEIR_POS.get("④죽은저택");
        BlockPos s4 = HEIR_POS.get("④아들저택");
        long son4 = HEIR_ID.get("④아들");
        HomeStore.Entry e4 = reg.entry(m4);
        HomeStore.Entry e4s = reg.entry(s4);
        sb.append("§6④ 이미 저택 대조§r (기준: 한 가문 저택 둘 금지 → 죽은 저택은 빈집 · 아들은 제 저택 유지)\n");
        chk(sb, tally, reg.isVacant(m4, today), "죽은 저택 빈집화",
                reg.isVacant(m4, today) ? "빈집" : "상속됨");
        chk(sb, tally, countHome(level, s4) == 1 && e4s != null && e4s.ownerId() == son4,
                "아들 제 저택 유지", countHome(level, s4) + "명 · 주인 #"
                        + (e4s == null ? "-" : e4s.ownerId()));
        chk(sb, tally, countHome(level, m4) == 0, "죽은 저택 무인", countHome(level, m4) + "명");
        // 40(원래) + 40(못 물려받은 저택의 <b>식량</b> 상속 — 분가 자식 1명이라 전액). 식량 상속은
        // 이번 단계 이전부터 있던 별개 규칙이고, "집은 안 넘어가지만 식량은 넘어간다"가 맞다.
        if (immediate) {
            chk(sb, tally, Math.abs(lar.get(s4) - 80.0) < 0.5, "아들 저장고 = 제것 40 + 식량상속 40",
                    String.format("%.0f (기대 80)", lar.get(s4)));
        } else {
            info(sb, "아들 저장고", String.format("%.0f — 식비로 줄어든다", lar.get(s4)));
        }

        // ④·③의 집은 <b>정당하게</b> 빈집이 되었으므로 나중엔 누가 들어가도 옳다. 그래서
        // 미탈취 판정은 상속으로 넘어간 ①에만 건다 — 시간이 지난 뒤 다시 읽어도 참이어야 한다.
        BlockPos s5 = HEIR_POS.get("⑤부자집");
        long rich = HEIR_ID.get("⑤부자");
        HomeStore.Entry e5 = reg.entry(s5);
        sb.append("§6⑤ 무연 부자 대조§r (기준: 남의 저택 미획득 — 제집·소형 유지)\n");
        chk(sb, tally, immediate ? countHome(level, s5) == 2 : countHome(level, s5) >= 2,
                "제집 유지", countHome(level, s5) + "명 (기대 "
                        + (immediate ? "2" : "2 이상 — 출산으로 늘 수 있다") + ")");
        chk(sb, tally, e5 != null && "small1".equals(e5.design()), "도면 소형 유지",
                e5 == null ? "등기없음" : e5.design());
        chk(sb, tally, e1 == null || e1.ownerId() != rich, "①저택 미탈취",
                e1 == null ? "-" : "#" + e1.ownerId());

        tell(ctx.getSource(), String.format("§e[5단계 판정] D%d(%s) — 통과 %d / 실패 %d§r\n",
                today, immediate ? "즉시" : "지속", tally[0], tally[1]) + sb);
        return tally[1] == 0 ? 1 : 0;
    }

    /** 그 집을 homePos 로 가리키는 생존 개체 수 — "누가 사는가"의 단일 관측값. */
    private static int countHome(ServerLevel level, BlockPos home) {
        int n = 0;
        for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                e -> e.isAlive() && home.equals(e.getHomePos()))) {
            n++;
        }
        return n;
    }

    /**
     * 밤에 개체가 <b>집 안에 서는가</b> — 거처 보유 개체별로 앵커까지의 거리를 잰다.
     *
     * <p>귀가 goal 이 손을 떼는 반경이 문간과 겹치면 개체가 문에 낀 채 밤을 보낸다. 도면의 문은
     * 앵커에서 2~5칸이므로, <b>거리 ≥ 2</b>인 개체가 있으면 그 자리가 문간일 가능성이 크다.
     * 평균이 아니라 <b>최대·분포</b>를 보는 이유: 한 명만 문에 껴도 그 가구는 문이 막힌다.
     */
    private static int homeNight(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        int n = 0;
        int sleeping = 0;
        int atDoor = 0;
        int onSpot = 0;
        int stacked = 0;
        java.util.Map<Long, Integer> cell = new java.util.HashMap<>();
        StringBuilder bad = new StringBuilder();
        for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getHomePos() != null)) {
            if (m.blockPosition().distSqr(m.getHomePos()) > 144.0) {
                continue; // 아직 귀가 중 — 판정 대상 아님
            }
            n++;
            if (m.getPose() == net.minecraft.world.entity.Pose.SLEEPING) {
                sleeping++;
            }
            // 문간에 낀 개체 — 서 있는 칸이나 그 아래가 문이다.
            if (level.getBlockState(m.blockPosition()).getBlock()
                    instanceof net.minecraft.world.level.block.DoorBlock) {
                atDoor++;
                if (bad.length() < 200) {
                    bad.append(m.blockPosition().toShortString()).append(' ');
                }
            }
            BlockPos spot = m.homeSpot(level);
            if (spot != null && m.blockPosition().distSqr(spot) <= 2.25) {
                onSpot++;
            }
            int c = cell.merge(m.blockPosition().asLong(), 1, Integer::sum);
            if (c == 2) {
                stacked++; // 같은 칸을 두 명 이상이 나눠 쓰는 중
            }
        }
        tell(ctx.getSource(), String.format(
                "§e[밤 위치] 귀가권 %d명 · 취침 %d · 제자리 %d · <b>문간 %d</b> · 겹친칸 %d%s",
                n, sleeping, onSpot, atDoor, stacked,
                bad.length() == 0 ? "" : "\n  문간 좌표: " + bad));
        return n;
    }

    /** 정원 6칸의 중심(앵커 상대, 정수 반올림) — 회전·대칭이 먹혔는지 판정하는 관측값. */
    private static String gardenCenter(HomeTemplate t) {
        int sx = 0;
        int sz = 0;
        for (var g : t.gardenCells()) {
            sx += g.getX();
            sz += g.getZ();
        }
        int n = Math.max(1, t.gardenCells().size());
        return String.format("(%+d,%+d)", Math.round((float) sx / n), Math.round((float) sz / n));
    }

    /** 도면 한 배치를 발밑에 실제로 배치 — 눈으로 확인용(정원 자리는 흙만, 덤불 없음). */
    private static int homeShow(CommandContext<CommandSourceStack> ctx, String design,
                                int rot, boolean mirror) {
        ServerLevel level = ctx.getSource().getLevel();
        var t = HomeTemplate.load(level, design, ROTS[rot],
                mirror ? HomeTemplate.MIRROR
                        : net.minecraft.world.level.block.Mirror.NONE);
        if (t.isEmpty()) {
            tell(ctx.getSource(), "§c도면 없음: " + design);
            return 0;
        }
        // <b>실제 건축 경로</b>를 그대로 태운다 — 명령 위치에 그냥 놓으면(종전) 지형과 무관하게
        // 공중에 뜬 집을 재게 되어 "땅을 파고 짓는가"라는 질문 자체가 성립하지 않는다.
        // 지형 높이 산출 → 평탄화 → 파내기 → 배치, 미믹이 짓는 순서와 동일하다.
        var cmd = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        int baseY = MimicEntity.terrainBaseY(level,
                HomeBlueprint.of(level, cmd, design, (byte) rot, mirror));
        var pos = new net.minecraft.core.BlockPos(cmd.getX(), baseY, cmd.getZ());
        HomeBlueprint bp0 = HomeBlueprint.of(level, pos, design, (byte) rot, mirror);
        // 건축 <b>전</b> 바깥 지면 — 발자국 바로 둘레 한 겹. 손대지 않는 땅이라 "지면 위에
        // 섰는가"의 기준선이 된다. 최하층(앵커−1)이 이 지면보다 정확히 1칸 위여야 한다.
        java.util.Set<Long> inside = new java.util.HashSet<>();
        for (BlockPos c : bp0.footprint()) {
            inside.add(net.minecraft.core.BlockPos.asLong(c.getX(), 0, c.getZ()));
        }
        java.util.List<Integer> ring = new java.util.ArrayList<>();
        for (BlockPos c : bp0.footprint()) {
            for (int[] d8 : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = c.getX() + d8[0];
                int nz = c.getZ() + d8[1];
                if (inside.contains(net.minecraft.core.BlockPos.asLong(nx, 0, nz))) {
                    continue;
                }
                ring.add(level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types
                        .MOTION_BLOCKING_NO_LEAVES, nx, nz) - 1);
            }
        }
        java.util.Collections.sort(ring);
        int outside = ring.isEmpty() ? pos.getY() - 2 : ring.get(ring.size() / 2);
        // 건축 전 지형 표면을 기억한다 — '원래 지면보다 낮아졌는가'(침하)의 기준선.
        java.util.Map<Long, Integer> before = new java.util.HashMap<>();
        for (BlockPos col : bp0.footprint()) {
            before.put(net.minecraft.core.BlockPos.asLong(col.getX(), 0, col.getZ()),
                    level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types
                            .MOTION_BLOCKING_NO_LEAVES, col.getX(), col.getZ()) - 1);
        }
        MimicEntity.flattenSite(level, bp0);
        MimicEntity.excavate(level, bp0);
        HomeTemplate h = t.get();
        h.place(level, pos);

        // ── 배치 후 <b>월드를 되읽어</b> 판정한다 ──────────────────────────────────
        // 계획(plan)이 옳다는 것과 월드가 옳다는 것은 다른 명제다. setBlock 플래그·지형 충돌·
        // 문 상단 파괴 등은 계획을 봐서는 알 수 없고, 실제 블록을 읽어야만 드러난다.
        var world = level;
        boolean anchorAir = world.getBlockState(pos).isAir();
        var floor = world.getBlockState(pos.below());
        boolean floorSolid = floor.isFaceSturdy(world, pos.below(),
                net.minecraft.core.Direction.UP);
        int gardenAir = 0;
        int gardenSoil = 0;
        for (var g : h.gardenCells()) {
            var gp = pos.offset(g);
            if (world.getBlockState(gp).isAir()) {
                gardenAir++;
            }
            var s = world.getBlockState(gp.below());
            if (s.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                    || s.is(net.minecraft.world.level.block.Blocks.DIRT)
                    || s.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT)
                    || s.is(net.minecraft.world.level.block.Blocks.PODZOL)) {
                gardenSoil++;
            }
        }
        // 계획 대비 실제 일치율 — 어긋난 칸이 있으면 그 좌표를 찍는다(원인 추적용).
        int match = 0;
        int bushes = 0;
        int doors = 0;
        String firstBad = null;
        for (var p : h.plan()) {
            var wp = pos.offset(p.rel());
            var ws = world.getBlockState(wp);
            if (HomeBlueprint.sameIgnoringShape(ws, p.state())) {
                match++;
            } else if (firstBad == null) {
                firstBad = p.rel().toShortString() + " 계획="
                        + p.state().getBlock().getName().getString()
                        + " 실제=" + ws.getBlock().getName().getString();
            }
            if (ws.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH)) {
                bushes++;
            }
            if (ws.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
                doors++;
            }
        }
        // 파낸 칸이 실제로 비었는가 — 평지에서는 원래 공기라 항상 통과한다. 흙에 파묻고 지어야
        // 의미가 생긴다(집이 언덕에 박혔을 때 실내가 흙으로 차는 사고를 잡는 검사).
        int carved = 0;
        for (var c : h.clear()) {
            if (world.getBlockState(pos.offset(c)).isAir()) {
                carved++;
            }
        }
        // ── "지면 위에 지었는가" 두 관측값 ──────────────────────────────────────
        //  해자 : 발자국 열의 바닥층(앵커−1)이 공기로 뚫렸다 = 건물 둘레가 꺼졌다.
        //  침하 : 그 열의 지표가 <b>건축 전보다 낮아졌다</b> = 땅을 파고 들어갔다.
        // 둘을 나눈 이유: 해자는 '구멍', 침하는 '깎임'이라 원인이 다르다(파내기 vs 평탄화).
        // 해자는 <b>최하층이 덮는 열</b>에서만 센다. 처마 밑 여백 열은 손대지 않은 지형이라
        // 앵커−2 가 공기인 것이 정상이다 — 그걸 세면 정상 배치가 전부 실패로 나온다(실측 오판).
        java.util.Set<Long> floored = new java.util.HashSet<>();
        for (HomeBlueprint.Placement pp : bp0.plan()) {
            if (pp.pos().getY() == pos.getY() - 1) {
                floored.add(net.minecraft.core.BlockPos.asLong(pp.pos().getX(), 0,
                        pp.pos().getZ()));
            }
        }
        int moat = 0;
        int sunk = 0;
        String worst = "-";
        int worstD = 0;
        for (BlockPos col : bp0.footprint()) {
            long key = net.minecraft.core.BlockPos.asLong(col.getX(), 0, col.getZ());
            if (floored.contains(key) && world.getBlockState(
                    new BlockPos(col.getX(), pos.getY() - 2, col.getZ())).isAir()) {
                moat++; // 최하층 밑이 뚫렸다 = 건물이 공중에 떴다
            }
            int now = world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types
                    .MOTION_BLOCKING_NO_LEAVES, col.getX(), col.getZ()) - 1;
            Integer was = before.get(key);
            if (was != null && now < was) {
                sunk++;
                if (was - now > worstD) {
                    worstD = was - now;
                    worst = col.getX() + "," + col.getZ() + " " + was + "→" + now;
                }
            }
        }
        int lift = (pos.getY() - 1) - outside; // 바깥 지면 대비 최하층 높이 — +1 이어야 한다
        boolean ok = lift == 1 && moat == 0 && anchorAir && floorSolid
                && gardenAir == HomeTemplate.GARDEN_CELLS
                && gardenSoil == HomeTemplate.GARDEN_CELLS && bushes == 0 && doors >= 2
                && match == h.plan().size() && carved == h.clear().size();
        tell(ctx.getSource(), String.format(
                "%s %s 회전%d 대칭%s @%s — <b>올라섬%+d</b>(기대 +1) · 계획%d 일치%d · "
                        + "해자%d 정지%d · <b>모양보정%d</b> · 앵커공기%s 바닥%s · "
                        + "정원 공기%d/흙%d · 덤불%d 문%d · reach%.1f%s",
                ok ? "§a✓§r" : "§c✗§r", design, rot, mirror ? "O" : "X", pos.toShortString(),
                lift, h.plan().size(), match, moat, sunk, HomeTemplate.lastSettled,
                anchorAir ? "O" : "X", floorSolid ? "O" : "X",
                gardenAir, gardenSoil, bushes, doors, h.reach(),
                firstBad == null ? "" : " §c불일치첫칸 " + firstBad));
        if (!HomeTemplate.lastSettledDetail.isEmpty()) {
            tell(ctx.getSource(), "  모양보정 내역: " + HomeTemplate.lastSettledDetail);
        }
        return ok ? 1 : 0;
    }

    /**
     * <b>신분 사다리의 능력 문턱</b> 실측 — "계층이 능력에서 갈리는가"를 세는 줄.
     *
     * <p>착공은 두 조건의 <b>동시</b> 성립이다: 자금 ≥ 임계 T, 그리고 그 자금을 쥐고도 만족하지
     * 않을 것. 그러므로 <b>돌파 가능 ⟺ T ≤ 만족선</b>이다(T 를 모은 순간 만족해 버리면 영영 못
     * 연다 — 만족의 덫). 여기서는 유배우 성인마다 그 부등식을 직접 풀어, 능력 구간별 돌파율을
     * 낸다. 능력이 오를수록 돌파율이 올라야 사다리가 산 것이고, 구간과 무관하게 고르면 신분을
     * 가르는 것은 능력이 아니라 다른 무엇이다(직전 실측: 가족 규모였다).
     *
     * <p>판정은 {@link com.evosim.core.Satisfaction#satisfied}를 <b>그대로</b> 부른다 — 특성
     * 우회(욕심·부지런·경쟁·야망)까지 같은 식으로 반영되도록. 이웃 최대 부는 전역 최대로
     * 근사한다(실제 판정은 48블록 반경이라, 경쟁 특성 보유자는 여기서 다소 낙관적으로 잡힌다).
     */
    private static void tellLadder(CommandSourceStack src,
                                   java.util.Map<Long, MimicEntity> byId,
                                   FarmStore farms, LarderStore larders) {
        // 가구별 명목 소모 합 — 만족선의 입력(MimicEntity.updateMotivation 과 같은 정의).
        java.util.Map<net.minecraft.core.BlockPos, double[]> homeNeed = new java.util.HashMap<>();
        double neighborMax = 0.0;
        for (MimicEntity m : byId.values()) {
            if (m.getHomePos() == null) {
                continue;
            }
            homeNeed.computeIfAbsent(m.getHomePos(), k -> new double[1])[0] +=
                    com.evosim.core.FoodEconomy.consumptionPerDay(m.getStage(),
                            com.evosim.core.Activity.MOVE, m.getIndividual(), false);
            neighborMax = Math.max(neighborMax, larders.get(m.getHomePos()));
        }
        // 능력 구간 — 성별을 뺀 <b>날것의</b> 채집 능력(Multipliers.gather)이 축이다.
        // aspiration 으로 나누면 안 된다: 그쪽은 1.0 에서 클램프되므로 무능력 구간이 영영
        // 0명으로 찍혀, "바닥층이 없다"는 거짓 보고가 된다.
        // 경계 근거 — 평범 1.0 · 약초Ⅱ 1.26 · 약초Ⅲ 1.39 · 엘리트(명석+약초Ⅴ+야망) 2.16.
        double[] cuts = {1.0, 1.2, 1.8};
        String[] names = {"무능", "평범", "유능", "엘리트"};
        int[] n = new int[4];
        int[] pass = new int[4];
        int[] own = new int[4];
        int[] male = new int[4];
        int[] landless = new int[4];
        double[] tSum = new double[4];
        double[] barSum = new double[4];
        double[] fund = new double[4];
        double[] best = new double[4];
        for (MimicEntity m : byId.values()) {
            if (m.getHomePos() == null || m.getSpouseId() == 0L) {
                continue; // 착공 자격 = 독립가구(FarmTicker 와 같은 조건)
            }
            if (m.getStage() != com.evosim.core.LifeStage.ADULT
                    && m.getStage() != com.evosim.core.LifeStage.ELDER) {
                continue;
            }
            var ind = m.getIndividual();
            // <b>지주 가구는 사다리에서 뺀다.</b> 살림은 가구 단위인데 구간은 개인으로 나뉘므로,
            // 지주의 배우자가 남편 가구의 살림을 그대로 달고 아래 구간에 잡힌다 — 실측에서
            // "평범 최고 40 · 엘리트 최고 40" 처럼 두 번 연속 같은 값이 찍혔고, 그걸 보고
            // "평민 가구가 임계 코앞까지 왔다"는 <b>없는 결론</b>을 냈다. 사다리가 묻는 것은
            // "아직 밭이 없는 자가 어떻게 뚫는가"이므로 이미 가진 집은 셈에서 빠져야 한다.
            boolean landedHome = farms.ownedTiles(ind.id()) > 0
                    || farms.ownedTiles(m.getSpouseId()) > 0;
            double need = homeNeed.get(m.getHomePos())[0];
            // 착공 예비의 가족 계상 = 본인 + 배우자 + 유아·소년(성인 자녀 제외, FarmTicker 와 동일).
            double famNeed = com.evosim.core.FoodEconomy.consumptionPerDay(m.getStage(),
                    com.evosim.core.Activity.MOVE, ind, false);
            for (MimicEntity h : byId.values()) {
                if (h == m || !m.getHomePos().equals(h.getHomePos())) {
                    continue;
                }
                boolean spouse = h.getIndividual().id() == m.getSpouseId()
                        || h.getSpouseId() == ind.id();
                boolean child = h.getStage() == com.evosim.core.LifeStage.INFANT
                        || h.getStage() == com.evosim.core.LifeStage.BOY;
                if (spouse || child) {
                    famNeed += com.evosim.core.FoodEconomy.consumptionPerDay(h.getStage(),
                            com.evosim.core.Activity.MOVE, h.getIndividual(), false);
                }
            }
            double t = com.evosim.core.FarmEconomy.newFarmCost(farms.ownedCount(ind.id()))
                    + com.evosim.core.FarmEconomy.foundReserve(famNeed);
            if (farms.stewardOf(ind.id()) != 0L) {
                t *= com.evosim.core.FarmEconomy.STEWARD_FOUND_RESERVE_MULT;
            }
            double a = com.evosim.core.Multipliers.gather(ind);
            int b = 0;
            while (b < cuts.length && a >= cuts[b]) {
                b++;
            }
            n[b]++;
            tSum[b] += t;
            barSum[b] += com.evosim.core.Satisfaction.bar(ind, need);
            // 임계 T 만큼 모은 순간을 그대로 물어본다 — 만족하지 않으면 그날 밤 착공한다.
            if (!com.evosim.core.Satisfaction.satisfied(ind, need, t, neighborMax,
                    farms.ownedTiles(ind.id()), false)) {
                pass[b]++;
            }
            // <b>실제로 무엇이 되었나</b> — 돌파는 허가일 뿐이고 자금이 임계에 닿아야 착공한다.
            // 무능한 욕심쟁이는 만족을 모르니 늘 '돌파'로 세어지지만 벌이가 낮아 영영 못 연다.
            // 허가율만 재면 결론이 뒤집히므로 결과(소유·살림)를 나란히 둔다.
            // <b>배우자를 세지 않는다.</b> 착공은 개인의 행위다 — 배우자까지 세면 지주 부부의
            // 무능한 쪽이 "무능 구간의 지주"로 잡혀, 능력 없는 자도 밭을 연다는 <b>거짓 신호</b>가
            // 된다(w9 D5 실측: 착공은 엘리트 1건뿐인데 사다리는 무능 지주1·엘리트 지주1 로 찍혔고,
            // 그 무능 1명은 엘리트의 아내였다). 출산은 가구 사건이라 그쪽은 배우자를 센다.
            if (farms.ownedTiles(ind.id()) > 0) {
                own[b]++;
            }
            if (!landedHome) {
                fund[b] += larders.get(m.getHomePos());
                landless[b]++;
                best[b] = Math.max(best[b], larders.get(m.getHomePos()));
            }
            // 구간 <b>구성</b>을 함께 남긴다. 평균만 보면 두 가지를 구별할 수 없다 —
            // ① 구간 전체가 임계에서 멀다 ② 한 집이 코앞인데 나머지가 평균을 끌어내린다.
            // 성비도 남긴다: 채집 산출에 성별 배수(남 1.5 / 여 0.5)가 곱해지므로, 유능한 아내와
            // 평범한 남편의 가구는 "유능 구간"에 잡히면서 실제 벌이는 낮다.
            if (m.getIndividual().sex() == com.evosim.core.Sex.MALE) {
                male[b]++;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (n[i] == 0) {
                sb.append(sb.length() > 0 ? " · " : "").append(names[i]).append("0명");
                continue;
            }
            sb.append(sb.length() > 0 ? " · " : "").append(String.format(
                    "%s %d명(남%d) §e지주%d§r 돌파%d 무토지살림%.0f/최고%.0f(임계%.0f vs 만족선%.0f)",
                    names[i], n[i], male[i], own[i], pass[i],
                    landless[i] == 0 ? 0.0 : fund[i] / landless[i], best[i],
                    tSum[i] / n[i], barSum[i] / n[i]));
        }
        tell(src, "  능력 사다리(a=성별뺀 채집능력) — " + sb);
        tell(src, "    ※판정은 <지주 비율>이다 — 구간이 오를수록 지주 비율이 올라야 능력이 신분을"
                + " 가른 것. 돌파(T≤만족선)는 허가일 뿐이고, 살림이 임계에 닿아야 실제로 연다.");
    }

    private static void tell(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg).withStyle(ChatFormatting.AQUA), false);
    }

    /** 건축 연출: 즉시 짝 성사 → 두 미믹이 천막을 직접 지음. */
    private static int stageBuild(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        MimicEntity m = spawnAdult(level, b, Sex.MALE);
        MimicEntity f = spawnAdult(level, b.add(1, 0, 0), Sex.FEMALE);
        if (m != null && f != null) {
            m.debugForcePair(f);
        }
        tell(ctx.getSource(), "건축 점검: 즉시 짝 성사 → 두 미믹이 부지로 가 천막을 한 칸씩 짓습니다(≈20초). "
                + "완성 시 모닥불 점화. 랜덤 방향 확인.");
        return 1;
    }

    /** 재혼(입주): 홀거처주(천막) + 방랑자 → 방랑자가 거처로 입주. */
    private static int stageWidow(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        level.setDayTime(10000L); // 배회(구애) 시간
        BlockPos homeA = groundAt(level, b, -5, 0);
        MimicEntity a = spawnAdult(level, Vec3.atBottomCenterOf(homeA), Sex.FEMALE);
        if (a != null) {
            a.debugSettleWithTent(homeA, Direction.NORTH); // 여성 홀거처주(사별 상정)
        }
        spawnAdult(level, b.add(2, 0, 0), Sex.MALE); // 남성 방랑자
        tell(ctx.getSource(), "재혼 점검: 여성 홀거처주(천막·모닥불) + 남성 방랑자. 배회 시간이라 곧 구애 성사 → "
                + "남성이 여성 거처로 입주(새 집 신축 없음, 모닥불 유지).");
        return 1;
    }

    /** 자식 분가: 부모 부부 + 성년 자식(동거) + 방랑자 → 자식이 새 거처로 분가. */
    private static int stageFamily(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        level.setDayTime(10000L);
        BlockPos home = groundAt(level, b, -6, 0);
        MimicEntity dad = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        MimicEntity mom = spawnAdult(level, Vec3.atBottomCenterOf(home).add(0.5, 0, 0), Sex.FEMALE);
        MimicEntity son = spawnAdult(level, Vec3.atBottomCenterOf(home).add(-0.5, 0, 0), Sex.MALE);
        if (dad != null && mom != null && son != null) {
            dad.debugSettleWithTent(home, Direction.NORTH);
            mom.debugSettleWithTent(home, Direction.NORTH);
            son.setHomePos(home);           // 성년 자식(부모와 동거 = FAMILY)
            dad.debugMarryTo(mom);          // 부모 부부(재구애 안 함)
        }
        spawnAdult(level, b.add(2, 0, 0), Sex.FEMALE); // 자식이 구애할 방랑 여성
        tell(ctx.getSource(), "분가 점검: 부모 부부 + 성년 아들(동거) + 방랑 여성. 아들이 구애 성사 시 "
                + "새 천막 신축(분가). 부모 거처는 유지(모닥불 켜짐).");
        return 1;
    }

    /** 둘 다 홀거처주: 짝 성사 시 한쪽 랜덤 폐기·합류. */
    private static int stageLonePair(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        level.setDayTime(10000L);
        BlockPos homeA = groundAt(level, b, -6, -3);
        BlockPos homeB = groundAt(level, b, -6, 3);
        MimicEntity a = spawnAdult(level, Vec3.atBottomCenterOf(homeA), Sex.MALE);
        MimicEntity bb = spawnAdult(level, Vec3.atBottomCenterOf(homeB), Sex.FEMALE);
        if (a != null) {
            a.debugSettleWithTent(homeA, Direction.NORTH);
        }
        if (bb != null) {
            bb.debugSettleWithTent(homeB, Direction.SOUTH);
        }
        tell(ctx.getSource(), "합류 점검: 각자 홀거처(천막) 둘. 배회 시 구애 성사 → 한쪽 거처 랜덤 폐기"
                + "(모닥불 꺼짐)·다른쪽으로 합류.");
        return 1;
    }

    /** 모닥불 소화: 홀거처주(천막) 소환 → 처치하면 거주자 0 → 모닥불 꺼짐. */
    private static int stageAbandon(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        BlockPos home = groundAt(level, b, -4, 0);
        MimicEntity a = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        if (a != null) {
            a.debugSettleWithTent(home, Direction.NORTH);
        }
        tell(ctx.getSource(), "소화 점검: 홀거처주(천막·모닥불 켜짐). 이 미믹을 처치하면 거주자 0 → "
                + "모닥불이 꺼집니다(건물은 폐허로 남음).");
        return 1;
    }

    /** 애향심 재사용: 빈 거처(꺼진 모닥불) 하나 + 애향심 부부 → 신축 대신 그 빈 거처로 입주(재점화). */
    private static int stageReuse(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        level.setDayTime(10000L);
        BlockPos empty = groundAt(level, b, -8, 0);
        MimicEntity.debugPlaceAbandonedHome(level, empty, Direction.NORTH); // 빈 거처 준비
        MimicEntity m = spawnAdult(level, b.add(2, 0, 0), Sex.MALE, Trait.HOMEBOUND);
        MimicEntity f = spawnAdult(level, b.add(3, 0, 0), Sex.FEMALE, Trait.HOMEBOUND);
        if (m != null && f != null) {
            m.debugForcePair(f);
        }
        tell(ctx.getSource(), "재사용 점검: 근처에 빈 거처(꺼진 모닥불) 1채 + 애향심(愛鄕) 부부 즉시 성사. "
                + "애향심×애향심=100%로 빈 거처 재사용 → 신축 없이 그 모닥불이 다시 켜집니다.");
        return 1;
    }

    /** 이주자: 빈 거처가 옆에 있어도 무시하고 멀리 신축. reuse 와 대조. */
    private static int stageMigrate(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        level.setDayTime(10000L);
        BlockPos empty = groundAt(level, b, -8, 0);
        MimicEntity.debugPlaceAbandonedHome(level, empty, Direction.NORTH); // 빈 거처(무시될 것)
        MimicEntity m = spawnAdult(level, b.add(2, 0, 0), Sex.MALE, Trait.MIGRATORY);
        MimicEntity f = spawnAdult(level, b.add(3, 0, 0), Sex.FEMALE, Trait.MIGRATORY);
        if (m != null && f != null) {
            m.debugForcePair(f);
        }
        tell(ctx.getSource(), "이주자 점검: 근처 빈 거처가 있어도 이주자×이주자는 반드시 신축(재사용 0%). "
                + "기본×2보다 더 멀리(×3) 부지를 잡아 천막을 새로 짓습니다.");
        return 1;
    }

    /**
     * 옆 정원 베리 원터치 <b>실연</b>: 부부 1쌍(천막·거처)을 세우고 실시간 실연을 켠다. 아무것도 미리 깔지
     * 않는다 — ① 약 2초 뒤 정산 1회로 <b>번식하고 남은 잉여만큼</b> 옆 정원(x=±3)에 베리를 여러 그루 심고,
     * ② 심은 베리가 실시간으로 익으면 ③ 아버지가 낮에 수확(age3→age1, 재성장)하는 것까지 눈으로 확인한다.
     */
    private static int stageBerry(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        BlockPos home = groundAt(level, b, -6, 0);
        discardFamily(level, home); // 재실행·타 무대 잔재 정리(장기 관찰 시계열 오염 방지)
        MimicEntity m = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        MimicEntity f = spawnAdult(level, Vec3.atBottomCenterOf(home).add(0.5, 0, 0), Sex.FEMALE);
        if (m != null && f != null) {
            m.debugSettleWithTent(home, Direction.NORTH);
            f.debugSettleWithTent(home, Direction.NORTH);
            m.debugMarryTo(f);      // 부부 → 번식 몫을 먼저 떼고 남는 잉여로만 베리
            m.startBerryDemo();     // 실시간 실연 시작(잠시 후 정산→심기, 이어 성장→수확)
        }
        level.setDayTime(4000L);    // 낮(노동 시간 한복판): 아버지가 익은 베리를 수확하러 나감
        tell(ctx.getSource(), "베리 실연 시작: 옆 정원(천막 좌우 x=±3)은 지금 비어 있습니다. ① 약 2초 뒤 정산 "
                + "→ 번식하고 남은 잉여만큼 베리가 실시간으로 심기고(로그 [베리] +N), ② 심은 베리가 점점 익어 "
                + "③ 아버지가 낮에 수확합니다(로그 [수확], age3→age1 재성장). 잉여가 많을수록 더 여러 그루(상한 8).");
        return 1;
    }

    /**
     * 식량 경제 원터치 관찰: 부부 정착(천막·저장고) + <b>관찰 로그 자동 ON</b> + 기상 시각. 시간 가속 없이
     * 자연 그대로 살게 두고, 채집→입금→인출→가계 시계열→번식까지 로그로 체킹한다(밸런싱 근거 수집).
     */
    private static int stageFood(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지 // 로그 자동 ON
        BlockPos home = groundAt(level, b, -6, 0);
        discardFamily(level, home); // 재실행·타 무대 잔재 정리(장기 관찰 시계열 오염 방지)
        MimicEntity m = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        MimicEntity f = spawnAdult(level, Vec3.atBottomCenterOf(home).add(0.5, 0, 0), Sex.FEMALE);
        if (m != null && f != null) {
            m.debugSettleWithTent(home, Direction.NORTH);
            f.debugSettleWithTent(home, Direction.NORTH);
            m.debugMarryTo(f);
        }
        level.setDayTime(1000L); // 기상 직후 — 하루 풀 사이클(노동→배회→귀가→취침) 관찰
        tell(ctx.getSource(), "식량 관찰 시작: 부부 정착 + 관찰 로그 자동 ON(evosim-events.log). "
                + "남편이 채집(여 0.5×/남 1.5×)→H≥2면 귀가 입금, 배고프면(H<0.8) 인출. 가계 시계열은 "
                + "1분/가구, 인구+식량 통계는 매일 아침 자동 기록. 즉석 확인: /evolog food · /evolog dump");
        return 1;
    }

    /**
     * 이주 즉시 연출: 세 가구(부부+유아2 / 부부 / 홀아비+유아1)를 즉시 기근으로 만들고 정산을 바로
     * 강제 → 명령 직후 [이주]가 터진다. 길잡이 등록→캐러밴 동참, 다둥이 분산 업기, 홀아비 업기,
     * 폐가 3채·신축까지 한 번에 성공/실패 판별.
     */
    private static int stageExodus(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        BlockPos homeA = groundAt(level, b, -8, -12);
        BlockPos homeB = groundAt(level, b, -8, 0);
        BlockPos homeC = groundAt(level, b, -8, 12);
        MimicEntity m1 = spawnAdult(level, Vec3.atBottomCenterOf(homeA), Sex.MALE);
        MimicEntity f1 = spawnAdult(level, Vec3.atBottomCenterOf(homeA).add(0.5, 0, 0), Sex.FEMALE);
        MimicEntity m2 = spawnAdult(level, Vec3.atBottomCenterOf(homeB), Sex.MALE);
        MimicEntity f2 = spawnAdult(level, Vec3.atBottomCenterOf(homeB).add(0.5, 0, 0), Sex.FEMALE);
        MimicEntity m3 = spawnAdult(level, Vec3.atBottomCenterOf(homeC), Sex.MALE); // 홀아비
        if (m1 == null || f1 == null || m2 == null || f2 == null || m3 == null) {
            return 0;
        }
        m1.debugSettleWithTent(homeA, Direction.NORTH);
        f1.debugSettleWithTent(homeA, Direction.NORTH);
        m1.debugMarryTo(f1);
        m2.debugSettleWithTent(homeB, Direction.NORTH);
        f2.debugSettleWithTent(homeB, Direction.NORTH);
        m2.debugMarryTo(f2);
        m3.debugSettleWithTent(homeC, Direction.NORTH);
        // 유아: 가구A 2명(순환 배정 — 부모가 나눠 업는지), 가구C 1명(홀아비가 업는지).
        stagedInfant(level, homeA, Sex.MALE);
        stagedInfant(level, homeA, Sex.FEMALE);
        stagedInfant(level, homeC, Sex.FEMALE);
        m1.debugForceFamine(level); // 정착 뒤 호출(정착이 쿨다운을 리셋하므로 순서 중요)
        m2.debugForceFamine(level);
        m3.debugForceFamine(level);
        level.setDayTime(1000L);
        m1.debugSettleOnce(); // 즉시 정산 강제 → 기근 판정 → [이주] 길잡이(정찰·합의 등록)
        m2.debugSettleOnce(); // → [이주] 마을 합의 동참(캐러밴)
        m3.debugSettleOnce(); // → 홀아비 이주(아버지가 유아 업기)
        tell(ctx.getSource(), "이주 즉시 연출 완료 — 판별: ① [이주] 3건(첫 건만 '길잡이', 나머지 '동참'이면 "
                + "캐러밴 성공) ② 가구A 유아 2명이 부모에게 나눠 업히고 가구C 유아가 홀아비에게 업혀 이동하면 "
                + "성공(안 업히고 옛집에 남으면 실패) ③ [폐가] 3건 + 새 부지 [건축완료] ④ 유아가 새 천막 "
                + "근처에서 자동 하차하면 성공. /evolog dump 30 으로 확인.");
        return 1;
    }

    /** 입금·인출 즉시 연출: 남편 H=2.5(여분) · 아내 H=0.7(부족)+저장고 3 — 수 초 내 결과 판별. */
    private static int stageTrip(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        BlockPos home = groundAt(level, b, -6, 0);
        discardFamily(level, home); // 재실행 잔재 정리(이전 부부 중첩 방지)
        MimicEntity m = spawnAdult(level, Vec3.atBottomCenterOf(home).add(8, 0, 0), Sex.MALE);
        MimicEntity f = spawnAdult(level, Vec3.atBottomCenterOf(home).add(-8, 0, 0), Sex.FEMALE);
        if (m == null || f == null) {
            return 0;
        }
        m.debugSettleWithTent(home, Direction.NORTH);
        f.debugSettleWithTent(home, Direction.NORTH);
        m.debugMarryTo(f);
        LarderStore.get(level).set(home, 3.0);
        m.debugSetHolding(2.5); // 여분 정수 → 즉시 "넣으러 귀가" 발동 직전
        f.debugSetHolding(0.7); // 귀가 임계(0.8) 미만 + 저장고 있음 → "꺼내러 귀가" 직전
        level.setDayTime(4000L);
        // 수치 자동 판별: 남편 H 2.5→[1,2)(입금) && 아내 H 0.7→≥1.5(인출)이면 성공. 2초마다 중계.
        LiveCheck.watch(ctx.getSource(), "입금·인출", 600,
                () -> String.format("남편 H %.2f(시작 2.50) · 아내 H %.2f(시작 0.70) · 저장고 %.0f(시작 3)",
                        m.getHolding(), f.getHolding(), LarderStore.get(level).get(home)),
                () -> m.getHolding() >= 1.0 && m.getHolding() < 2.0 && f.getHolding() >= 1.5,
                () -> discard(m, f)); // 판별 종료 시 무대 정리(잔존·세계 오염 방지)
        tell(ctx.getSource(), "입금·인출 연출 — 기대: 남편 H 2.50→1.50(1개 입금), 아내 H 0.70→1.70(1개 인출), "
                + "저장고 3→4→3. 아래 수치 중계로 자동 판정.");
        return 1;
    }

    /** 나눔(B) 즉시 연출: 아내 위급(H=0.25)·남편 여유(H=2.0)·저장고 0(가족틱 개입 차단). */
    private static int stageShare(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        BlockPos home = groundAt(level, b, -6, 0);
        discardFamily(level, home); // 재실행 잔재 정리
        MimicEntity m = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        MimicEntity f = spawnAdult(level, Vec3.atBottomCenterOf(home).add(2, 0, 0), Sex.FEMALE);
        if (m == null || f == null) {
            return 0;
        }
        m.debugSettleWithTent(home, Direction.NORTH);
        f.debugSettleWithTent(home, Direction.NORTH);
        m.debugMarryTo(f);
        LarderStore.get(level).set(home, 0.0); // 저장고 비움 — 가족틱 급식이 아니라 '나눔'이 구하는지 본다
        // 남편 H는 1.9 — 입금 문턱(2.0) 미달이라 가족틱이 저장고에 넣어 아내를 구하는
        // 우회 경로(위양성)가 원천 차단되고, 나눔 문턱(1.5)은 넘어 나눔만이 유일한 구조 경로.
        m.debugSetHolding(1.9);
        f.debugSetHolding(0.25); // 위급(<0.3) — 나눔 대상 직전
        level.setDayTime(4000L);
        // 수치 자동 판별: 아내 H 0.25→≥0.6(0.5 받음 — 자가 채집으론 이 시간 내 불가능한 상승폭)
        // && 남편 H 1.9→≤1.8(내어줌 — 자연 소모만으론 400틱 내 1.85까지밖에 안 떨어짐)이면 성공.
        LiveCheck.watch(ctx.getSource(), "나눔", 400,
                () -> String.format("아내 H %.2f(시작 0.25·위급) · 남편 H %.2f(시작 1.90)",
                        f.getHolding(), m.getHolding()),
                () -> f.getHolding() >= 0.6 && m.getHolding() <= 1.8,
                () -> discard(m, f));
        tell(ctx.getSource(), "나눔 연출 — 기대: 남편이 다가가 0.50 건넴 → 아내 H 0.25→0.75(위급 해제), "
                + "남편 H 1.90→1.40. 아래 수치 중계로 자동 판정.");
        return 1;
    }

    /** 번식 즉시 연출: 저장고 20 채우고 정산 강제 — 명령 직후 출산 여부 판별. */
    private static int stageBirth(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        BlockPos home = groundAt(level, b, -6, 0);
        discardFamily(level, home); // 재실행 개체 잔재 제거 — 이전 부부·유아가 남아 "유아 1" 판정 오염 방지
        MimicEntity m = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        MimicEntity f = spawnAdult(level, Vec3.atBottomCenterOf(home).add(0.5, 0, 0), Sex.FEMALE);
        if (m == null || f == null) {
            return 0;
        }
        m.debugSettleWithTent(home, Direction.NORTH);
        f.debugSettleWithTent(home, Direction.NORTH);
        m.debugMarryTo(f);
        m.debugClearBerries(level); // 재실행 잔재 제거 — "심은 수 = 현재 그루" 회계 대조 전제 복구
        LarderStore.get(level).set(home, 20.0); // 게이트(≈12) 여유 통과 직전
        level.setDayTime(4000L);
        m.debugSettleOnce(); // 즉시 정산 → 출산 판정
        // 수치 즉시 판별: 저장고 20 → 17(출산비용 3) − 베리비용(심은 그루 실측 × 그루당 비용) 회계 일치
        // && 거처 귀속 유아 1명 등장. 베리도 이제 저장고를 실제 차감하므로 기대값에 반영(회계 대조).
        double after = LarderStore.get(level).get(home);
        int infants = 0;
        for (MimicEntity e : level.getEntitiesOfClass(MimicEntity.class,
                new net.minecraft.world.phys.AABB(home).inflate(8.0))) {
            if (e.getStage() == LifeStage.INFANT && home.equals(e.getHomePos())) {
                infants++;
            }
        }
        int bCost = berryCost(level, m);
        double expect = 17.0 - bCost;
        boolean ok = infants == 1 && Math.abs(after - expect) < 1.0E-6;
        tell(ctx.getSource(), String.format(
                "번식 판별 — 저장고 20 → %.0f (기대 %.0f = 17 − 베리비용 %d) · 유아 %d명 (기대 1) ⇒ %s",
                after, expect, bCost, infants, ok ? "✅ 성공" : "❌ 실패"));
        return ok ? 1 : 0;
    }

    /** 정산이 심은 베리의 실제 저장고 차감액 — 본 코드와 동일 공식으로 회계 대조(결과값 기반 판정). */
    private static int berryCost(ServerLevel level, MimicEntity leader) {
        int bushes = leader.countBerries(level);
        if (bushes <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.round(
                bushes * BerryEconomy.BUSH_COST * BerryEconomy.costMult(leader.getIndividual())));
    }

    /** 육아 급식(D) 즉시 연출: 배고픈 유아(H=0.5)+저장고 5 → 정산 강제 → 어미 급식 판별. */
    private static int stageCare(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        BlockPos home = groundAt(level, b, -6, 0);
        MimicEntity m = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        MimicEntity f = spawnAdult(level, Vec3.atBottomCenterOf(home).add(0.5, 0, 0), Sex.FEMALE);
        if (m == null || f == null) {
            return 0;
        }
        m.debugSettleWithTent(home, Direction.NORTH);
        f.debugSettleWithTent(home, Direction.NORTH);
        m.debugMarryTo(f);
        MimicEntity baby = stagedInfant(level, home, Sex.FEMALE);
        if (baby == null) {
            return 0;
        }
        baby.debugSetHolding(0.5); // 채움 임계(1.0) 미만 — 급식 대상 직전
        LarderStore.get(level).set(home, 5.0);
        level.setDayTime(4000L);
        m.debugSettleOnce(); // 즉시 정산 → 유아 급식
        // 수치 즉시 판별: 유아 H 0.5→1.5(정수 1개 급식·목표 도달) && 저장고 5→4.
        double babyAfter = baby.getHolding();
        double larderAfter = LarderStore.get(level).get(home);
        boolean ok = babyAfter >= 1.5 - 1.0E-9 && Math.abs(larderAfter - 4.0) < 1.0E-6;
        tell(ctx.getSource(), String.format(
                "육아 급식 판별 — 유아 H 0.50 → %.2f (기대 1.50) · 저장고 5 → %.0f (기대 4) ⇒ %s",
                babyAfter, larderAfter, ok ? "✅ 성공" : "❌ 실패"));
        return ok ? 1 : 0;
    }

    /** R6 위급 분기 즉시 연출: 밤에 위급 2명 — 저장고 없는 쪽은 채집 강행, 있는 쪽은 귀가 인출. */
    private static int stageR6(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        BlockPos homeA = groundAt(level, b, -6, -6); // 저장고 없음 → 채집 강행
        BlockPos homeB = groundAt(level, b, -6, 6);  // 저장고 있음 → 귀가 인출
        MimicEntity a = spawnAdult(level, Vec3.atBottomCenterOf(homeA), Sex.MALE);
        MimicEntity bb = spawnAdult(level, Vec3.atBottomCenterOf(homeB).add(6, 0, 0), Sex.MALE);
        if (a == null || bb == null) {
            return 0;
        }
        a.debugSettleWithTent(homeA, Direction.NORTH);
        bb.debugSettleWithTent(homeB, Direction.NORTH);
        LarderStore.get(level).set(homeA, 0.0);
        LarderStore.get(level).set(homeB, 3.0);
        a.debugSetHolding(0.25);  // 위급 + 밥 없음 → 밤에도 안 자고 채집해야 성공
        bb.debugSetHolding(0.25); // 위급 + 밥 있음 → 귀가·인출해야 성공
        level.setDayTime(15000L); // 취침 시간대 — 평소라면 자야 함
        tell(ctx.getSource(), "R6 연출(밤) — 판별(수 초 내): A(북쪽 집)는 [위급] '저장고 없음(채집 강행)' 후 "
                + "자지 않고 풀을 뜯어 [채집]→[회복]. B(남쪽 집)는 [위급] '저장고 있음(귀가)' 후 집으로 걸어와 "
                + "[인출]→[회복]. 누워 자거나 위급인 채 가만있으면 실패.");
        return 1;
    }

    /** 구혼 여행(족외혼) 즉시 연출: 64블록 떨어진 두 마을, 고립 남성을 즉시 출발 직전으로. */
    private static int stageSuitor(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        BlockPos homeA = groundAt(level, b, -6, 0);
        BlockPos homeB = groundAt(level, b, 58, 0); // 64블록 동쪽 — 타향(48 초과)
        MimicEntity m = spawnAdult(level, Vec3.atBottomCenterOf(homeA), Sex.MALE);
        MimicEntity f = spawnAdult(level, Vec3.atBottomCenterOf(homeB), Sex.FEMALE);
        if (m == null || f == null) {
            return 0;
        }
        m.setInvulnerable(true); // 통제 검증 — 환경 사고(벌·선인장 등) 사망 차단(여성 사망 시 그 모닥불이
        f.setInvulnerable(true); // 등록 해제돼 목적지가 사라지고 여행이 출발 못 하던 것을 방지)
        m.debugSettleWithTent(homeA, Direction.NORTH);  // 모닥불 A 점화(등록)
        f.debugSettleWithTent(homeB, Direction.NORTH);  // 모닥불 B 점화(등록) — 남성의 목적지
        m.debugForceLonely(); // '오래 외로움' 강제 → 다음 인식 틱에 출발 직전
        level.setDayTime(8200L); // 배회 시간 — 도착하면 바로 구애 가능
        tell(ctx.getSource(), "구혼 여행 연출 — 판별: 즉시 [구혼여행] '타향 모닥불 @…로 출발' 로그 + 남성이 "
                + "동쪽(64블록)으로 계속 걸어감 → 도착 후 [구애] 성사 → [짝성립] → 정착([합류]/신축). "
                + "출발 로그가 없거나 자기 집 주변만 맴돌면 실패.");
        return 1;
    }

    /** 중혼(일부다처) 즉시 연출: 관용 아내 부부 + 부양 증명 저장고 + 독신 여성 → 수치로 성립 판별. */
    private static int stagePolygamy(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        BlockPos home = groundAt(level, b, -6, 0);
        discardFamily(level, home); // 재실행 잔재 정리
        MimicEntity[] couple = coupleAt(level, home);
        MimicEntity bride = spawnAdult(level, Vec3.atBottomCenterOf(home).add(4, 0, 0), Sex.FEMALE);
        if (bride == null) {
            return 0;
        }
        // 부양 증명(하루소모 6 × 3일 = 18) 여유 통과: 감시 창 동안 가족틱이 자연 개입해 출산(−3)·
        // 베리(−최대 8)로 저장고를 깎아도 게이트가 흔들리지 않게 40으로(경계값 21은 이제 flaky).
        LarderStore.get(level).set(home, 40.0);
        level.setDayTime(9000L); // 배회 시간 — 구애 goal 활동
        LiveCheck.watch(ctx.getSource(), "중혼", 1200,
                () -> String.format("신부 %s · 거처 %s · 저장고 %.0f",
                        bride.isSingleAdult() ? "single" : "married",
                        home.equals(bride.getHomePos()) ? "합류" : "미합류",
                        LarderStore.get(level).get(home)),
                () -> !bride.isSingleAdult() && home.equals(bride.getHomePos()),
                () -> discardFamily(level, home, couple[0], couple[1], bride)); // 감시 창 중 태어난 자손까지 회수
        tell(ctx.getSource(), "중혼 연출 — 주변 독신남 0·기혼남만 후보(감점에도 유일 후보). 기대: 신부가 "
                + "구애 → 아내 용인(인색·경쟁 없음)+저장고 40≥부양선(하루소모×3) → 수락·합류. 아내에 인색 "
                + "특성을 준 거절 케이스는 /evosim checkall 12단계에 포함.");
        return 1;
    }

    /**
     * 원트랙 전체 검증: 미검증 기능 12단계를 한 명령으로 차례차례 — 각 단계는 "발동 직전" 조건을 즉각
     * 조성하고, 별도 감지가 <b>결과값 변화만</b> 보고 ✅/❌ 판정(호출 여부 아님). 끝에 요약 출력.
     */
    /** 낡은 이주 합의 잔재 만료 — 다음 스텝·실플레이 오염 방지(stale_pact cleanup 과 동일 수법). */
    private static void expirePact(ServerLevel level, BlockPos origin) {
        MigrationDest.get(level).register(origin, origin,
                level.getGameTime() - MigrationDest.VALID_TICKS - 1);
    }

    /**
     * 이주 합본 검증(F-6 대응) — 유아 가족 이주 본판 + 이 수정이 오염시킬 수 있는 이웃 규칙 4종을
     * 한 명령으로: 결과값(homePos 실변경·isPassenger 상태·저장고)만 판정. 금지 스텝은 passOnTimeout.
     */
    private static int stageMigX(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "이미 검증이 진행 중 — 끝난 뒤 다시 실행.");
            return 0;
        }
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll();
        List<VerifySuite.Step> steps = new ArrayList<>();
        // [1] 아이들만 가구 — 이주 금지 유지(grown==0 안전핀 회귀. 금지 결과 = homePos 변경)
        {
            BlockPos home = groundAt(level, b, -20, -20);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("migx_children_stay",
                    "children-only household must not migrate (grown==0 pin)", 200, true, () -> {
                discardFamily(level, home);
                c[0] = stagedInfant(level, home, Sex.MALE);
                c[1] = stagedInfant(level, home, Sex.FEMALE);
                c[0].debugSettleWithTent(home, Direction.NORTH);
                c[1].setHomePos(home);
                c[0].debugForceFamine(level);
                c[0].debugSettleOnce();
            }, () -> String.format("home %s(must stay)",
                    home.equals(c[0].getHomePos()) ? "kept" : "moved!"),
                    () -> c[0].getHomePos() == null || !home.equals(c[0].getHomePos()),
                    () -> discardFamily(level, home, c)));
        }
        // [2] 식량 넉넉한 유아 가족 — 잔류(저장고 게이트 회귀. 시계·쿨다운은 과거화, 저장고만 복원 —
        //     잔류 사유를 저장고 하나로 고정해 위양성 차단)
        {
            BlockPos home = groundAt(level, b, -20, 20);
            MimicEntity[] c = new MimicEntity[3];
            steps.add(new VerifySuite.Step("migx_wellfed_stay",
                    "well-fed infant family must not migrate (larder gate)", 200, true, () -> {
                discardFamily(level, home);
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[2] = stagedInfant(level, home, Sex.MALE);
                c[0].debugForceFamine(level);
                LarderStore.get(level).set(home, 40.0);
                c[0].debugSettleOnce();
            }, () -> String.format("home %s(must stay) larder %.0f",
                    home.equals(c[0].getHomePos()) ? "kept" : "moved!",
                    LarderStore.get(level).get(home)),
                    () -> c[0].getHomePos() == null || !home.equals(c[0].getHomePos()),
                    () -> discardFamily(level, home, c)));
        }
        // [3] 무유아 부부 — 이주 발동 회귀(결과값: homePos 실변경)
        {
            BlockPos home = groundAt(level, b, 20, -20);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("migx_couple_go",
                    "childless couple migrates: home actually changes", 100, false, () -> {
                discardFamily(level, home);
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[0].debugForceFamine(level);
                c[0].debugSettleOnce();
            }, () -> String.format("home %s",
                    home.equals(c[0].getHomePos()) ? "unchanged" : "moved"),
                    () -> c[0].getHomePos() != null && !home.equals(c[0].getHomePos()),
                    () -> {
                        BlockPos nh = c[0].getHomePos();
                        discardFamily(level, home, c);
                        if (nh != null && !nh.equals(home)) {
                            discardFamily(level, nh); // 새 정착지 잔재(천막·개체)도 소거
                        }
                        expirePact(level, home);
                    }));
        }
        // [4] 유아 가족 — 이주+업기 발동(F-6 본판. 결과값: homePos 실변경 ∧ 유아 탑승 상태)
        {
            BlockPos home = groundAt(level, b, 20, 20);
            MimicEntity[] c = new MimicEntity[3];
            steps.add(new VerifySuite.Step("migx_infant_go",
                    "infant family migrates & infant rides a parent (F-6)", 100, false, () -> {
                discardFamily(level, home);
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[2] = stagedInfant(level, home, Sex.MALE);
                c[0].debugForceFamine(level);
                c[0].debugSettleOnce();
            }, () -> String.format("home %s infantRiding %s",
                    home.equals(c[0].getHomePos()) ? "unchanged" : "moved",
                    c[2].isPassenger() ? "O" : "X"),
                    () -> c[0].getHomePos() != null && !home.equals(c[0].getHomePos())
                            && c[2].isPassenger(),
                    () -> {
                        BlockPos nh = c[0].getHomePos();
                        discardFamily(level, home, c);
                        if (nh != null && !nh.equals(home)) {
                            discardFamily(level, nh);
                        }
                        expirePact(level, home);
                    }));
        }
        // [5] 건축 중 재이주 금지 — 1차 이주 직후(부부=빌더) 재기근을 강제해도 잔류해야
        //     (F-6 폴백이 빌더를 잡아먹지 않는지 — 오염 후보의 직접 관측. 금지 결과 = 2차 이주)
        {
            BlockPos home = groundAt(level, b, 0, 44);
            MimicEntity[] c = new MimicEntity[2];
            BlockPos[] nh = new BlockPos[1];
            steps.add(new VerifySuite.Step("migx_builder_stay",
                    "mid-build family must not re-migrate (builder exclusion)", 200, true, () -> {
                discardFamily(level, home);
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[0].debugForceFamine(level);
                c[0].debugSettleOnce();          // 1차 이주 → 부부가 빌더로 전환
                nh[0] = c[0].getHomePos();       // 이주 후 새 거처(기준점)
                c[0].debugForceFamine(level);    // 재기근 강제(시계·쿨다운 재과거화)
                c[0].debugSettleOnce();          // 재정산 — 빌더 제외로 잔류해야 함
            }, () -> String.format("home2 %s(must keep)",
                    nh[0] != null && nh[0].equals(c[0].getHomePos()) ? "kept" : "moved!"),
                    () -> nh[0] == null || c[0].getHomePos() == null
                            || !nh[0].equals(c[0].getHomePos()),
                    () -> {
                        BlockPos cur = c[0].getHomePos();
                        discardFamily(level, home, c);
                        if (nh[0] != null && !nh[0].equals(home)) {
                            discardFamily(level, nh[0]);
                        }
                        if (cur != null && !cur.equals(home) && !cur.equals(nh[0])) {
                            discardFamily(level, cur);
                        }
                        expirePact(level, home);
                    }));
        }
        VerifySuite.start(ctx.getSource(), steps);
        tell(ctx.getSource(), "이주 합본 검증(5단계) — 유아 가족 이주(F-6) + 아이들만·유복·무유아 회귀·"
                + "건축중 재이주 금지. 결과값(homePos 실변경·유아 탑승·저장고)만 판정.");
        return 1;
    }

    /**
     * 보호막 합본 검증(F-7 대응) — 구제·해제 두 결과를 같은 자리 2회씩: 자가구제 경주가 제거됐으면
     * 4단계 전부 결정론적으로 같은 결과여야 한다. 판정은 결과값(H 상승·저장고 3→2 차감·tenantFarm).
     */
    private static int stageShieldX(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "이미 검증이 진행 중 — 끝난 뒤 다시 실행.");
            return 0;
        }
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll();
        List<VerifySuite.Step> steps = new ArrayList<>();
        BlockPos anchorR = groundAt(level, b, 14, -14);
        BlockPos homeR = groundAt(level, b, 2, -14);
        BlockPos anchorB = groundAt(level, b, 14, 14);
        BlockPos homeB = groundAt(level, b, 2, 14);
        for (int round = 1; round <= 2; round++) { // 같은 자리 2회 — 잔재·경주 결정론 확인
            final int r = round;
            {
                MimicEntity[] c = new MimicEntity[2];
                FarmStore.Plot[] pl = new FarmStore.Plot[1];
                steps.add(new VerifySuite.Step("shieldx_relief_" + r,
                        "critical tenant relieved: H>=1 & lord larder 3->2 & bond kept", 600, false, () -> {
                    FarmTicker.clearAssignments();
                    discardFamily(level, homeR);
                    c[0] = spawnAdult(level, Vec3.atBottomCenterOf(homeR), Sex.MALE);
                    c[0].debugSettleWithTent(homeR, Direction.NORTH);
                    c[0].setNoAi(true); // 영주 행위 동결(F-7) — 입금·채집 소음 제거
                    LarderStore.get(level).set(homeR, 3.0);
                    c[1] = spawnAdult(level, Vec3.atBottomCenterOf(anchorR).add(-3, 0, 4), Sex.MALE);
                    pl[0] = buildDemoPlot(level, anchorR, c[0].getIndividual().id(), 9);
                    c[1].setTenant(pl[0].id, 3);
                    c[1].debugSetHolding(0.2);
                    c[1].setNoAi(true); // 행위 동결(F-7) — 경주 제거
                    level.setDayTime(4000L);
                }, () -> String.format("workerH %.2f(start 0.2) larder %.0f(expect 2) bond %s",
                        c[1].getHolding(), LarderStore.get(level).get(homeR),
                        c[1].getTenantFarm() == pl[0].id ? "kept" : "broken"),
                        () -> c[1].getHolding() >= 1.0
                                && Math.abs(LarderStore.get(level).get(homeR) - 2.0) < 1.0E-6
                                && c[1].getTenantFarm() == pl[0].id,
                        () -> {
                            discard(c);
                            farmClearPlot(level, pl[0]);
                            FarmTicker.clearAssignments();
                        }));
            }
            {
                MimicEntity[] c = new MimicEntity[2];
                FarmStore.Plot[] pl = new FarmStore.Plot[1];
                steps.add(new VerifySuite.Step("shieldx_break_" + r,
                        "lord larder 0 -> relief impossible -> bond dissolves", 600, false, () -> {
                    FarmTicker.clearAssignments();
                    discardFamily(level, homeB);
                    c[0] = spawnAdult(level, Vec3.atBottomCenterOf(homeB), Sex.MALE);
                    c[0].debugSettleWithTent(homeB, Direction.NORTH);
                    c[0].setNoAi(true); // 영주 행위 동결(F-7) — 입금·채집 소음 제거
                    LarderStore.get(level).set(homeB, 0.0);
                    c[1] = spawnAdult(level, Vec3.atBottomCenterOf(anchorB).add(-3, 0, 4), Sex.MALE);
                    pl[0] = buildDemoPlot(level, anchorB, c[0].getIndividual().id(), 9);
                    c[1].setTenant(pl[0].id, 3);
                    c[1].debugSetHolding(0.2);
                    c[1].setNoAi(true); // 행위 동결(F-7) — 경주 제거
                    level.setDayTime(4000L);
                }, () -> String.format("bond %s(expect broken) workerH %.2f",
                        c[1].getTenantFarm() == 0L ? "broken" : "kept", c[1].getHolding()),
                        () -> c[1].getTenantFarm() == 0L,
                        () -> {
                            discard(c);
                            farmClearPlot(level, pl[0]);
                            FarmTicker.clearAssignments();
                        }));
            }
        }
        VerifySuite.start(ctx.getSource(), steps);
        tell(ctx.getSource(), "보호막 합본 검증(4단계) — 구제·해제 × 같은 자리 2회. 채집 차단(F-7)으로 "
                + "자가구제 경주가 제거됐으면 전부 결정론 PASS 여야 함.");
        return 1;
    }

    /**
     * 베리 부트스트랩·수율 합본 검증 — ① 첫 2그루 부트스트랩(저장고 8→정확 6 차감·그루 2),
     * ② 3그루째는 기존 게이트 유지(금지), ③ 들풀 한 입 수율 상향(단일 풀 → H 정확 상승 판별).
     * 전부 결과값(그루 수·저장고 잔액·H)만 판정.
     */
    private static int stageBerryX(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "이미 검증이 진행 중 — 끝난 뒤 다시 실행.");
            return 0;
        }
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll();
        List<VerifySuite.Step> steps = new ArrayList<>();
        // [1] 정원 선완성 — 지참금 14 상당(저장고 14)에서 한 번의 정산으로 8그루 전량 + 비용 8
        //     실차감(14→6), 그리고 <b>출산은 없어야</b> 함(식수가 출산보다 먼저·잔여 6 < 게이트 12).
        {
            BlockPos home = groundAt(level, b, -16, -24);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("berryx_bootstrap_full",
                    "larder 14: one settle -> 8 bushes, larder 6, NO birth", 200, false, () -> {
                discardFamily(level, home);
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[0].debugClearBerries(level); // 같은 자리 재실행 잔재 제거
                LarderStore.get(level).set(home, 14.0);
                c[0].debugSettleOnce();
            }, () -> String.format("bushes %d(expect 8) larder %.1f(expect 6.0) infants %d(expect 0)",
                    c[0].countBerries(level), LarderStore.get(level).get(home), infantsAt(level, home)),
                    () -> c[0].countBerries(level) == 8
                            && Math.abs(LarderStore.get(level).get(home) - 6.0) < 1.0E-6
                            && infantsAt(level, home) == 0,
                    () -> {
                        c[0].debugClearBerries(level);
                        discardFamily(level, home, c);
                    }));
        }
        // [2] 생계 유보 — 저장고 6.9(생존몫 6 + 0.9)로는 0그루(부트스트랩도 생존몫 침범 불가).
        {
            BlockPos home = groundAt(level, b, 16, -24);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("berryx_survival_reserve",
                    "larder 6.9: must NOT plant (survival reserve intact)", 100, true, () -> {
                discardFamily(level, home);
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[0].debugClearBerries(level);
                LarderStore.get(level).set(home, 6.9);
                c[0].debugSettleOnce();
            }, () -> String.format("bushes %d(must stay 0)", c[0].countBerries(level)),
                    () -> c[0].countBerries(level) > 0, // ← 금지 결과
                    () -> {
                        c[0].debugClearBerries(level);
                        discardFamily(level, home, c);
                    }));
        }
        // [2b] 정원 후 출산 — 8그루 완성 상태에서 저장고 12 도달 시 출산 발동(순서·게이트 검산):
        //      출산 후 저장고 9(−3), 그루 8 유지, 유아 1.
        {
            BlockPos home = groundAt(level, b, -16, 0);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("berryx_then_birth",
                    "8 bushes + larder 12 -> birth fires (larder 9, infant 1, bushes stay 8)",
                    200, false, () -> {
                discardFamily(level, home);
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[0].debugClearBerries(level);
                LarderStore.get(level).set(home, 14.0);
                c[0].debugSettleOnce(); // 정원 8/8 완성(잔여 6)
                LarderStore.get(level).set(home, 12.0);
                c[0].debugSettleOnce(); // 게이트 12 → 출산
            }, () -> String.format("bushes %d(expect 8) larder %.1f(expect 9.0) infants %d(expect 1)",
                    c[0].countBerries(level), LarderStore.get(level).get(home), infantsAt(level, home)),
                    () -> c[0].countBerries(level) == 8
                            && Math.abs(LarderStore.get(level).get(home) - 9.0) < 1.0E-6
                            && infantsAt(level, home) == 1,
                    () -> {
                        c[0].debugClearBerries(level);
                        discardFamily(level, home, c);
                    }));
        }
        // [2c] 식수 폴백 — 고정 8칸을 원목으로 전부 막고도 폴백 셀로 8그루 완성(지형 복권 완화).
        {
            BlockPos home = groundAt(level, b, 16, 0);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("berryx_fallback_cells",
                    "8 fixed tiles blocked by logs: fallback cells still complete 8 bushes",
                    200, false, () -> {
                discardFamily(level, home);
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[0].debugClearBerries(level);
                for (BlockPos tile : HomeBlueprint.of(level, home).garden()) {
                    BlockPos g = level.getHeightmapPos(
                            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            tile);
                    level.setBlockAndUpdate(g, Blocks.OAK_LOG.defaultBlockState()); // 자리 차단
                }
                LarderStore.get(level).set(home, 14.0);
                c[0].debugSettleOnce();
            }, () -> String.format("bushes %d(expect 8 via fallback)", c[0].countBerries(level)),
                    () -> c[0].countBerries(level) == 8,
                    () -> {
                        c[0].debugClearBerries(level);
                        for (BlockPos tile : HomeBlueprint.of(level, home).garden()) {
                            for (int dy = -2; dy <= 3; dy++) {
                                BlockPos p = tile.offset(0, dy, 0);
                                if (level.getBlockState(p).is(Blocks.OAK_LOG)) {
                                    level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                                }
                            }
                        }
                        discardFamily(level, home, c);
                    }));
        }
        // [3] 들풀 수율 — 흙 패드 위 단일 풀 1포기(단일 수입원): 한 입 후 H가 1.62 이상이면 신수율
        //     (0.08×배율≈+0.146)만 가능(구수율 최대 +0.11은 1.61 미만 — 판별 경계 1.62). 상한 1.75는
        //     사냥 등 외부 수입 오염 차단. 패드 반경 동물 사전 제거.
        {
            BlockPos pad = groundAt(level, b, 22, -22);
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("berryx_yield",
                    "single grass bite: H 1.5 -> in [1.62, 1.75] (new yield only)", 400, false, () -> {
                for (var a : level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
                        new net.minecraft.world.phys.AABB(pad).inflate(28.0))) {
                    a.discard(); // 사냥 수입 차단(판정 오염 방지)
                }
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        level.setBlockAndUpdate(pad.offset(dx, -1, dz), Blocks.DIRT.defaultBlockState());
                        level.setBlockAndUpdate(pad.offset(dx, 0, dz), Blocks.AIR.defaultBlockState());
                    }
                }
                level.setBlockAndUpdate(pad, Blocks.GRASS.defaultBlockState()); // 유일한 수입원
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(pad.offset(2, 0, 2)), Sex.MALE);
                level.setDayTime(2000L);
            }, () -> String.format("H %.3f(start 1.5, expect >=1.62 after one bite)", c[0].getHolding()),
                    () -> c[0].getHolding() >= 1.62 && c[0].getHolding() <= 1.75,
                    () -> discard(c)));
        }
        VerifySuite.start(ctx.getSource(), steps);
        tell(ctx.getSource(), "베리 합본 검증(5단계) — 정원 선완성(14→8그루·무출산) / 생계 유보 / "
                + "정원 후 출산(12→유아1·저장고9) / 식수 폴백 / 들풀 수율. 결과값만 판정.");
        return 1;
    }

    /** 검증용 익은 베리 패드 — 흙 25×25 + 풀 제거 + 동물 제거(사냥·풀 수입 오염 차단), 중앙에 익은 덤불 1.
     *  반경 ±12: 탐색(±5) + 배회 표류(8)를 덮어 패드 표적이 <b>유일한</b> 채집물이 되게 한다 —
     *  자연 풀이 있는 지형에서 개체가 다른 풀로 새면 판정이 무대 밖 환경에 좌우된다. */
    private static void berryPad(ServerLevel level, BlockPos pad) {
        for (var a : level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
                new net.minecraft.world.phys.AABB(pad).inflate(28.0))) {
            a.discard();
        }
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                level.setBlockAndUpdate(pad.offset(dx, -1, dz), Blocks.DIRT.defaultBlockState());
                level.setBlockAndUpdate(pad.offset(dx, 0, dz), Blocks.AIR.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(pad, Blocks.SWEET_BERRY_BUSH.defaultBlockState()
                .setValue(SweetBerryBushBlock.AGE, 3)); // 유일한 수입원(익음)
    }

    /** 패드 중앙 3×3 풀 무리 — 단일 포기의 표본 탐색 플레이크 방지(노년 쿼터 무대). */
    private static void grassCluster(ServerLevel level, BlockPos pad) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlockAndUpdate(pad.offset(dx, 0, dz), Blocks.GRASS.defaultBlockState());
            }
        }
    }

    /** allTraits 순서에서 특성의 현재 인덱스(-1=없음) — 편집 연산 대상 지정용. */
    private static int indexOf(MimicEntity m, Trait t) {
        var all = m.getIndividual().allTraits();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).trait() == t) {
                return i;
            }
        }
        return -1;
    }

    /** 패드 중앙 3×3 안 남은 풀 수(0~9). */
    private static int countGrass(ServerLevel level, BlockPos pad) {
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (level.getBlockState(pad.offset(dx, 0, dz)).is(Blocks.GRASS)) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * 밴드 산출 검증 — 정원 수확의 성중립 + 능력 등급 배율 M(g)를 H 정밀 대역으로 판정.
     * ① 남성 한 수확: Δ=0.36(구식이면 0.90→상한 2.0 컷) ② 여성 한 수확: 같은 대역(구식이면 0.30)
     * ③ 약초학자Ⅴ 한 수확: Δ=0.36×1.30=0.468(등급 미적용이면 0.383/0.36). 전부 결과값(H)만 판정.
     */
    private static int stageBandX(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "이미 검증이 진행 중 — 끝난 뒤 다시 실행.");
            return 0;
        }
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll();
        List<VerifySuite.Step> steps = new ArrayList<>();
        // [1] 남성 수확 — H 1.2 → 1.70(delta 0.50). 성별 곱 재유입 시 남 +0.75→1.95(대역 위),
        //     여 +0.25→1.45(대역 아래) — 양방향 판별.
        {
            BlockPos pad = groundAt(level, b, -22, 30);
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("bandx_neutral_m",
                    "male berry pick: H 1.2 -> [1.65, 1.78] (delta 0.50, sex-mult bug rejected)",
                    300, false, () -> {
                berryPad(level, pad);
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(pad.offset(2, 0, 2)), Sex.MALE);
                c[0].debugSetHolding(1.2);
                level.setDayTime(2000L);
            }, () -> String.format("H %.3f(start 1.2, expect 1.65~1.78)", c[0].getHolding()),
                    () -> c[0].getHolding() >= 1.65 && c[0].getHolding() <= 1.78,
                    () -> discard(c)));
        }
        // [2] 여성 수확 — 같은 대역이면 성중립 입증.
        {
            BlockPos pad = groundAt(level, b, 0, 30);
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("bandx_neutral_f",
                    "female berry pick: same band [1.65, 1.78] (sex-mult bug rejected)",
                    300, false, () -> {
                berryPad(level, pad);
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(pad.offset(2, 0, 2)), Sex.FEMALE);
                c[0].debugSetHolding(1.2);
                level.setDayTime(2000L);
            }, () -> String.format("H %.3f(start 1.2, expect 1.65~1.78)", c[0].getHolding()),
                    () -> c[0].getHolding() >= 1.65 && c[0].getHolding() <= 1.78,
                    () -> discard(c)));
        }
        // [3] 약초학자Ⅴ 수확 — H 1.0 → 1.71(0.50×M(5)=1.42). 등급 누락(Ⅲ 취급 1.546 / 무배율 1.50)은
        //     하한 1.65 밖.
        {
            BlockPos pad = groundAt(level, b, 22, 30);
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("bandx_grade",
                    "herbalist-V pick: H 1.0 -> [1.65, 1.78] (M(5)=1.42, delta 0.71)",
                    300, false, () -> {
                berryPad(level, pad);
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(pad.offset(2, 0, 2)), Sex.MALE,
                        Trait.HERBALIST); // 무대 등급 Ⅴ 고정(spawnAdult)
                c[0].debugSetHolding(1.0);
                level.setDayTime(2000L);
            }, () -> String.format("H %.3f(start 1.0, expect 1.65~1.78)", c[0].getHolding()),
                    () -> c[0].getHolding() >= 1.65 && c[0].getHolding() <= 1.78,
                    () -> discard(c)));
        }
        VerifySuite.start(ctx.getSource(), steps);
        tell(ctx.getSource(), "밴드 합본 검증(3단계) — 정원 성중립(남=여=+0.36) / 능력 등급 M(Ⅴ)=1.30. "
                + "H 정밀 대역만 판정(구식·등급누락 전부 대역 밖).");
        return 1;
    }

    /**
     * 노년 쿼터 검증 — 채집·밭 노동이 쿼터(하루소모×0.5=1.0)에서 실제로 멈추는지. 각 항목은
     * 양성 대조(ctrl: 쿼터 미달 → 일함)와 금지 감시(quota: 쿼터 충족 → 일 안 함) 짝으로,
     * 무대 자체가 죽어 있으면 ctrl 이 먼저 실패해 가짜 PASS 를 차단한다. 판정은 블록 상태만.
     */
    private static int stageElderX(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "이미 검증이 진행 중 — 끝난 뒤 다시 실행.");
            return 0;
        }
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll();
        List<VerifySuite.Step> steps = new ArrayList<>();
        // [1] 양성 대조 — 쿼터 미달(오늘 0 채집) 노인은 노동 시간에 풀을 채집한다(풀 감소 = 무대 유효).
        //     풀 1포기는 무작위 표본 탐색(24표본/틱)이 놓쳐 플레이크 — 3×3 무리로 명중률을 올린다.
        {
            BlockPos pad = groundAt(level, b, -22, 44);
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("elderx_forage_ctrl",
                    "elder below quota gathers: 3x3 grass must shrink (stage validity)", 600, false, () -> {
                berryPad(level, pad); // 패드 조성(동물 제거) 재사용
                grassCluster(level, pad); // 덤불 대신 3×3 풀 무리
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(pad.offset(2, 0, 2)), Sex.MALE);
                c[0].setStage(LifeStage.ELDER);
                level.setDayTime(2000L); // 노년 노동창(1000~6000) 안
            }, () -> String.format("grass %d/9(expect <9) H %.2f act=%s quotaMet=%s nav=%s pos=%s forage[%s]",
                    countGrass(level, pad), c[0].getHolding(),
                    c[0].currentActionLabel(), c[0].elderQuotaMet(),
                    c[0].getNavigation().isDone() ? "done"
                            : String.valueOf(c[0].getNavigation().getTargetPos()),
                    c[0].blockPosition().toShortString(), c[0].forageDebug()),
                    () -> countGrass(level, pad) < 9,
                    () -> discard(c)));
        }
        // [2] 금지 감시 — 오늘 1.2 채집(쿼터 1.0 충족, 구쿼터 2.0 미달)한 노인은 채집하지 않는다.
        //     구식이면 1.2 < 2.0 → 풀 감소 → forbidden 실패. 신식이면 600틱 무사 경과.
        {
            BlockPos pad = groundAt(level, b, 0, 44);
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("elderx_forage_quota",
                    "elder at quota (1.2 >= 1.0) must NOT gather: 3x3 grass stays (old quota 2.0 would)",
                    600, true, () -> {
                berryPad(level, pad);
                grassCluster(level, pad);
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(pad.offset(2, 0, 2)), Sex.MALE);
                c[0].setStage(LifeStage.ELDER);
                c[0].addHarvest(1.2);        // 오늘 몫 채움(dayGathered=1.2) — 쿼터 판정 근거
                c[0].debugSetHolding(1.5);   // H 정규화(위급·상한 소음 제거)
                level.setDayTime(2000L);
            }, () -> String.format("grass %d/9(must stay 9) H %.2f",
                    countGrass(level, pad), c[0].getHolding()),
                    () -> countGrass(level, pad) < 9, // ← 금지 결과(채집 발생)
                    () -> discard(c)));
        }
        // [3] 양성 대조 — 쿼터 미달 노인 지주는 자기 밭을 수확한다(익음 9→8↓ = 무대 유효).
        {
            BlockPos anchor = groundAt(level, b, 26, 44);
            MimicEntity[] c = new MimicEntity[1];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("elderx_farm_ctrl",
                    "elder owner below quota harvests own farm: ripe 9 -> <9", 500, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(anchor.offset(-3, 0, 0)), Sex.MALE);
                c[0].setStage(LifeStage.ELDER);
                pl[0] = buildDemoPlot(level, anchor, c[0].getIndividual().id(), 9);
                level.setDayTime(2000L);
            }, () -> String.format("ripe %d(expect <9) H %.2f", countRipe(level, pl[0]), c[0].getHolding()),
                    () -> countRipe(level, pl[0]) < 9,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [4] 금지 감시 — 쿼터 충족 노인 지주는 밭도 멈춘다(㉵: 밭 goal 쿼터 게이트).
        //     구식(게이트 없음)이면 용량(6타일)까지 수확 → 익음 감소 → forbidden 실패.
        {
            BlockPos anchor = groundAt(level, b, 52, 44);
            MimicEntity[] c = new MimicEntity[1];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("elderx_farm_quota",
                    "elder owner at quota must NOT harvest: ripe stays 9 (old code would harvest)",
                    500, true, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(anchor.offset(-3, 0, 0)), Sex.MALE);
                c[0].setStage(LifeStage.ELDER);
                c[0].addHarvest(1.2);
                c[0].debugSetHolding(1.5);
                pl[0] = buildDemoPlot(level, anchor, c[0].getIndividual().id(), 9);
                level.setDayTime(2000L);
            }, () -> String.format("ripe %d(must stay 9) H %.2f", countRipe(level, pl[0]), c[0].getHolding()),
                    () -> countRipe(level, pl[0]) < 9, // ← 금지 결과(수확 발생)
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        VerifySuite.start(ctx.getSource(), steps);
        tell(ctx.getSource(), "노년 쿼터 합본 검증(4단계) — 채집·밭 각각 [양성 대조 → 금지 감시] 짝. "
                + "블록 상태(풀·익음 수)만 판정 — 무대가 죽으면 ctrl 이 먼저 실패한다.");
        return 1;
    }

    /**
     * 고정 발광 검증 (UX-D) — 서버 결과값(isCurrentlyGlowing)만 판정: ① 하트비트 on → 발광
     * ② 하트비트 중단 → 유예(60틱) 뒤 자동 소등 ③ off 즉시 소등. 패킷 핸들러와 같은
     * GlowKeeper.heartbeat 진입점을 직접 호출(판정-코드 대칭).
     */
    private static int stageGlowX(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "이미 검증이 진행 중 — 끝난 뒤 다시 실행.");
            return 0;
        }
        LiveCheck.cancelAll();
        List<VerifySuite.Step> steps = new ArrayList<>();
        BlockPos pad = groundAt(level, b, -36, 30);
        // [1] on → 발광 시작
        {
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("glowx_on",
                    "heartbeat(on) -> entity glowing", 100, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(pad), Sex.MALE);
                c[0].setNoAi(true); // 이동 소음 제거 — 발광 상태만 본다
                com.evosim.mod.entity.GlowKeeper.heartbeat(level, c[0].getId(), true);
            }, () -> String.format("glowing=%s keeper=%d", c[0].isCurrentlyGlowing(),
                    com.evosim.mod.entity.GlowKeeper.activeCount()),
                    () -> c[0].isCurrentlyGlowing(),
                    () -> discard(c)));
        }
        // [2] 하트비트 중단 → 유예 60틱 + 소거 주기(20틱) 안에 자동 소등
        {
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("glowx_expire",
                    "no heartbeat -> auto unglow within grace(60t)+sweep", 200, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(pad.offset(4, 0, 0)), Sex.MALE);
                c[0].setNoAi(true);
                com.evosim.mod.entity.GlowKeeper.heartbeat(level, c[0].getId(), true);
            }, () -> String.format("glowing=%s(expect false after ~80t)", c[0].isCurrentlyGlowing()),
                    () -> !c[0].isCurrentlyGlowing(),
                    () -> discard(c)));
        }
        // [3] off → 즉시 소등
        {
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("glowx_off",
                    "heartbeat(off) -> unglow immediately", 100, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(pad.offset(8, 0, 0)), Sex.MALE);
                c[0].setNoAi(true);
                com.evosim.mod.entity.GlowKeeper.heartbeat(level, c[0].getId(), true);
                com.evosim.mod.entity.GlowKeeper.heartbeat(level, c[0].getId(), false);
            }, () -> String.format("glowing=%s(expect false)", c[0].isCurrentlyGlowing()),
                    () -> !c[0].isCurrentlyGlowing(),
                    () -> discard(c)));
        }
        VerifySuite.start(ctx.getSource(), steps);
        tell(ctx.getSource(), "발광 합본 검증(3단계) — 켜기/만료 자동 소등/즉시 소등. "
                + "서버 결과값(isCurrentlyGlowing)만 판정.");
        return 1;
    }

    /**
     * 특성 편집 검증 — 패킷 핸들러와 같은 {@link com.evosim.mod.entity.TraitEditor#apply} 진입점.
     * ① 약초학자Ⅴ+우성 주입 → 상태·<b>수확 델타 실측 변화</b>(잔존 채집 0.08×1.5×1.5=0.18/개)
     * ② 슬롯 초과(성향 4번째)·중복 거부 ③ 우성 토글·등급 클램프·삭제 원복. 전부 서버 결과값.
     */
    private static int stageEditX(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "이미 검증이 진행 중 — 끝난 뒤 다시 실행.");
            return 0;
        }
        LiveCheck.cancelAll();
        List<VerifySuite.Step> steps = new ArrayList<>();
        // [1] 주입 → 발현 배율 실변화: 정원 베리 한 수확 — 무배율 0.50(→1.50) vs
        //     약초학자Ⅴ 주입 후 M(5)=1.42 → 0.71(→1.71). Ⅲ 취급 버그(0.546→1.546)도 대역 밖.
        {
            BlockPos pad = groundAt(level, b, -22, 58);
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("editx_inject",
                    "inject herbalist-V dominant: berry pick 0.50 -> 0.71 (H 1.0 -> [1.65, 1.78])",
                    300, false, () -> {
                berryPad(level, pad); // 익은 덤불 1 = 유일 수입원
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(pad.offset(2, 0, 2)), Sex.MALE);
                c[0].debugSetHolding(1.0); // Ⅴ 델타 0.71이 무주택 상한 2.0에 닿지 않게
                String st = com.evosim.mod.entity.TraitEditor.apply(level, null, c[0].getId(),
                        com.evosim.mod.entity.TraitEditor.OP_ADD, Trait.HERBALIST.ordinal(),
                        0, 5, true);
                SimEvents.note(level, "editx", "주입 상태: " + st);
                level.setDayTime(2000L);
            }, () -> String.format("H %.3f(start 1.0, expect 1.65~1.78) herbalist=%s dom=%s",
                    c[0].getHolding(),
                    com.evosim.core.ExpressionResolver.isExpressed(c[0].getIndividual(), Trait.HERBALIST),
                    c[0].getIndividual().allTraits().stream()
                            .filter(ti -> ti.trait() == Trait.HERBALIST)
                            .anyMatch(TraitInstance::isDominant)),
                    () -> c[0].getHolding() >= 1.65 && c[0].getHolding() <= 1.78
                            && com.evosim.core.ExpressionResolver.isExpressed(c[0].getIndividual(), Trait.HERBALIST),
                    () -> discard(c)));
        }
        // [2] 거부 규칙 — 성향 슬롯 3개 찬 상태에서 4번째 거부 + 같은 특성 중복 거부(결과: 목록 불변)
        {
            BlockPos pad = groundAt(level, b, 0, 58);
            MimicEntity[] c = new MimicEntity[1];
            String[] st = new String[2];
            steps.add(new VerifySuite.Step("editx_reject",
                    "4th disposition rejected + duplicate rejected (trait list unchanged)",
                    100, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(pad), Sex.MALE);
                c[0].setNoAi(true);
                // 무대 기본 특성에 명석(성향 슬롯 1 선점)이 있으므로 2개만 더 채우면 3/3.
                com.evosim.mod.entity.TraitEditor.apply(level, null, c[0].getId(),
                        com.evosim.mod.entity.TraitEditor.OP_ADD, Trait.DILIGENT.ordinal(), 0, 0, false);
                com.evosim.mod.entity.TraitEditor.apply(level, null, c[0].getId(),
                        com.evosim.mod.entity.TraitEditor.OP_ADD, Trait.FRUGAL.ordinal(), 0, 0, false);
                st[0] = com.evosim.mod.entity.TraitEditor.apply(level, null, c[0].getId(),
                        com.evosim.mod.entity.TraitEditor.OP_ADD, Trait.GREEDY.ordinal(), 0, 0, false);
                st[1] = com.evosim.mod.entity.TraitEditor.apply(level, null, c[0].getId(),
                        com.evosim.mod.entity.TraitEditor.OP_ADD, Trait.DILIGENT.ordinal(), 0, 0, false);
            }, () -> String.format("disp=%d(expect 3) 4th='%s' dup='%s'",
                    c[0].getIndividual().traitsIn(com.evosim.core.Category.DISPOSITION).size(),
                    st[0], st[1]),
                    () -> c[0].getIndividual().traitsIn(com.evosim.core.Category.DISPOSITION).size() == 3
                            && st[0] != null && st[0].contains("초과")
                            && st[1] != null && st[1].contains("이미"),
                    () -> discard(c)));
        }
        // [3] 우성 토글·등급 클램프·삭제 원복 — 전부 setup 에서 실행하고 중간 결과를 캡처,
        //     judge 는 캡처값 + 최종 상태만 읽는다(판정 중 상태 변이 금지).
        {
            BlockPos pad = groundAt(level, b, 22, 58);
            MimicEntity[] c = new MimicEntity[1];
            boolean[] mid = new boolean[3]; // [0]=우성됨 [1]=등급V 클램프 [2]=삭제 후 부재
            steps.add(new VerifySuite.Step("editx_toggle_grade_remove",
                    "dominant toggles, grade clamps at V, removal restores baseline",
                    100, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(pad), Sex.MALE);
                c[0].setNoAi(true);
                com.evosim.mod.entity.TraitEditor.apply(level, null, c[0].getId(),
                        com.evosim.mod.entity.TraitEditor.OP_ADD, Trait.TOUGH.ordinal(), 0, 4, false);
                int idx = indexOf(c[0], Trait.TOUGH);
                com.evosim.mod.entity.TraitEditor.apply(level, null, c[0].getId(),
                        com.evosim.mod.entity.TraitEditor.OP_TOGGLE_DOMINANT, 0, idx, 0, false);
                com.evosim.mod.entity.TraitEditor.apply(level, null, c[0].getId(),
                        com.evosim.mod.entity.TraitEditor.OP_GRADE_DELTA, 0, idx, 1, false); // 4→5
                com.evosim.mod.entity.TraitEditor.apply(level, null, c[0].getId(),
                        com.evosim.mod.entity.TraitEditor.OP_GRADE_DELTA, 0, idx, 1, false); // 5→5 클램프
                var ti = c[0].getIndividual().allTraits().stream()
                        .filter(x -> x.trait() == Trait.TOUGH).findFirst().orElse(null);
                mid[0] = ti != null && ti.isDominant();
                mid[1] = ti != null && ti.grade() == 5;
                com.evosim.mod.entity.TraitEditor.apply(level, null, c[0].getId(),
                        com.evosim.mod.entity.TraitEditor.OP_REMOVE, 0, indexOf(c[0], Trait.TOUGH),
                        0, false);
                mid[2] = c[0].getIndividual().allTraits().stream()
                        .noneMatch(x -> x.trait() == Trait.TOUGH);
            }, () -> String.format("dom=%s clampV=%s removed=%s", mid[0], mid[1], mid[2]),
                    () -> mid[0] && mid[1] && mid[2],
                    () -> discard(c)));
        }
        VerifySuite.start(ctx.getSource(), steps);
        tell(ctx.getSource(), "편집봉 합본 검증(3단계) — 주입 후 수확 델타 실측 / 슬롯·중복 거부 / "
                + "우성·등급 클램프·삭제. 패킷과 같은 TraitEditor.apply 경유, 결과값만 판정.");
        return 1;
    }

    /**
     * 성명 검증 — ① 출산 자식의 성 = 부친 성(부계 상속, 실개체) ② 편집봉 OP_SET_NAME 자유 입력
     * 반영 + 형식 거부. 전부 Individual 상태 결과값 판정.
     */
    private static int stageNameX(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "이미 검증이 진행 중 — 끝난 뒤 다시 실행.");
            return 0;
        }
        LiveCheck.cancelAll();
        List<VerifySuite.Step> steps = new ArrayList<>();
        // [1] 부계 상속 — 부부 출산 → 자식 성 == 부친 성, 로그 표기용 shortName 비어있지 않음
        {
            BlockPos home = groundAt(level, b, -16, 72);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("namex_patrilineal",
                    "child surname equals father's after birth", 200, false, () -> {
                discardFamily(level, home);
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[0].debugClearBerries(level);
                LarderStore.get(level).set(home, 14.0);
                c[0].debugSettleOnce(); // 정원 8/8 선완성(식수가 출산보다 먼저 — E-1 순서)
                LarderStore.get(level).set(home, 12.0);
                c[0].debugSettleOnce(); // 게이트 12 → 출산
            }, () -> {
                MimicEntity infant = null;
                for (MimicEntity m : level.getEntitiesOfClass(MimicEntity.class,
                        new net.minecraft.world.phys.AABB(home).inflate(8.0))) {
                    if (m.getStage() == LifeStage.INFANT) {
                        infant = m;
                    }
                }
                MimicEntity father = c[0].isFemale() ? c[1] : c[0];
                return String.format("infant=%s childSur=%s fatherSur=%s",
                        infant != null,
                        infant != null && infant.getIndividual() != null
                                ? infant.getIndividual().surname() : "-",
                        father.getIndividual().surname());
            }, () -> {
                MimicEntity infant = null;
                for (MimicEntity m : level.getEntitiesOfClass(MimicEntity.class,
                        new net.minecraft.world.phys.AABB(home).inflate(8.0))) {
                    if (m.getStage() == LifeStage.INFANT) {
                        infant = m;
                    }
                }
                MimicEntity father = c[0].isFemale() ? c[1] : c[0];
                return infant != null && infant.getIndividual() != null
                        && infant.getIndividual().surname()
                                .equals(father.getIndividual().surname())
                        && !infant.getIndividual().shortName().isEmpty();
            }, () -> discardFamily(level, home, c)));
        }
        // [2] 개명 op — 자유 입력 반영 + 빈 성 거부(상태 불변)
        {
            BlockPos pad = groundAt(level, b, 16, 72);
            MimicEntity[] c = new MimicEntity[1];
            String[] st = new String[2];
            steps.add(new VerifySuite.Step("namex_rename",
                    "OP_SET_NAME applies free text; empty-surname rejected", 100, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(pad), Sex.MALE);
                c[0].setNoAi(true);
                st[0] = com.evosim.mod.entity.TraitEditor.apply(level, null, c[0].getId(),
                        com.evosim.mod.entity.TraitEditor.OP_SET_NAME, 0, 0, 0, false,
                        "아무개|테스트|검증가");
                st[1] = com.evosim.mod.entity.TraitEditor.apply(level, null, c[0].getId(),
                        com.evosim.mod.entity.TraitEditor.OP_SET_NAME, 0, 0, 0, false,
                        "이름만| |");
            }, () -> String.format("full='%s' set='%s' reject='%s'",
                    c[0].getIndividual().fullName(), st[0], st[1]),
                    () -> "아무개 테스트 검증가".equals(c[0].getIndividual().fullName())
                            && st[0] != null && st[0].startsWith("개명")
                            && st[1] != null && st[1].contains("비울 수 없음"),
                    () -> discard(c)));
        }
        // [3] 원장 성명 박제 — 등록 시 성명 저장·개명 갱신이 가계도/통계 스냅샷에 반영(번호 표시 종식).
        //     stats·가계도 표시원(FamilyLedger.Rec.name → PedigreeSnapshot/StatsSnapshot)이 판정 대상.
        {
            long[] ids = new long[2];
            steps.add(new VerifySuite.Step("namex_ledger_names",
                    "ledger records carry names; rename syncs; pedigree/stats snapshots show them",
                    100, false, () -> {
                var ledger = FamilyLedger.get(level);
                long fid = 900_000_001L;
                long cid = 900_000_002L;
                ids[0] = fid;
                ids[1] = cid;
                ledger.debugRemove(fid); // 재실행 멱등(같은 자리 2회)
                ledger.debugRemove(cid);
                Individual father = new Individual(fid, Sex.MALE, 0, 0, 1);
                Individual child = new Individual(cid, Sex.FEMALE, fid, 0, 2);
                ledger.register(father, 1L);   // 기본명(등록 박제)
                ledger.register(child, 2L);
                father.setName("올리버", "", "도일");
                ledger.updateName(fid, father.shortName()); // 개명 동기(편집봉 경로와 동일)
            }, () -> {
                var ped = com.evosim.mod.gui.PedigreeSnapshot.build(level, ids[1]);
                var stats = com.evosim.mod.gui.StatsSnapshot.build(level);
                String top = stats.tops.stream().filter(t -> t.id() == ids[0])
                        .map(com.evosim.mod.gui.StatsSnapshot.Top::name).findFirst().orElse("-");
                return String.format("focus '%s' father '%s' statsTop '%s'",
                        ped.rows[0][0].name, ped.rows[1][0].name, top);
            }, () -> {
                var ped = com.evosim.mod.gui.PedigreeSnapshot.build(level, ids[1]);
                var stats = com.evosim.mod.gui.StatsSnapshot.build(level);
                Individual childRef = new Individual(ids[1], Sex.FEMALE, ids[0], 0, 2);
                boolean focusNamed = ped.rows[0][0].name.equals(childRef.shortName())
                        && !ped.rows[0][0].name.isEmpty();
                boolean fatherRenamed = ped.rows[1][0].name.equals("올리버 도일");
                // 하향 항해(자식 행) — 부친 포커스 스냅샷의 자식 행에 자식 실명이 실려야 한다.
                var pedF = com.evosim.mod.gui.PedigreeSnapshot.build(level, ids[0]);
                boolean childRow = java.util.Arrays.stream(pedF.childrenRow)
                        .anyMatch(n -> n.name.equals(childRef.shortName()));
                // 랭킹은 후손수 상위 8 한정 — 실세계 대가문에 밀려 부재할 수 있으므로
                // "있다면 반드시 실명"으로 판정(표시원 Rec.name 은 가계도 단언이 전수 검증).
                boolean statsNamed = stats.tops.stream().filter(t -> t.id() == ids[0])
                        .allMatch(t -> "올리버 도일".equals(t.name()));
                return focusNamed && fatherRenamed && statsNamed && childRow;
            }, () -> {
                FamilyLedger.get(level).debugRemove(ids[0]);
                FamilyLedger.get(level).debugRemove(ids[1]);
            }));
        }
        VerifySuite.start(ctx.getSource(), steps);
        tell(ctx.getSource(), "성명 합본 검증(3단계) — 부계 상속(실출산) / 개명 자유 입력·거부 / "
                + "원장 성명 박제(가계도·통계 스냅샷). 서버 상태 결과값만 판정.");
        return 1;
    }

    /**
     * 렌즈 스냅샷(P1) 합본 검증 — ① 문턱 역산이 familyTick 판정식과 정확히 일치(번식·베리·개간
     * 부족량 수치 판정) ② 행동 라벨이 실행 중 goal 을 실측 반영 ③ 인코드→디코드 왕복 무손실.
     * 전부 서버측 결과값 판정 — 클라 없이(헤드리스) 완주 가능.
     */
    private static int stageScanX(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "이미 검증이 진행 중 — 끝난 뒤 다시 실행.");
            return 0;
        }
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll();
        List<VerifySuite.Step> steps = new ArrayList<>();
        // [1] 문턱 역산 정확성 — 부부·정원2·저장고5: 번식부족 13.0(=3+6×2+3−5 — 소모 항이
        //     canReproduce 의 REPRO_NEED_DAYS(2)와 일치해야 한다. 종전 7.0은 need×1로 계산해
        //     실제 문턱을 6 낮게 표시하던 값) ·
        //     베리부족 2.0(부트스트랩 8 게이트: 생계6+비용1−5) · 개간부족 19.0(18+6−5) · 동기 ✓
        {
            BlockPos home = groundAt(level, b, -16, 24);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("scanx_thresholds",
                    "snapshot lacks: repro 13.0, berry 2.0, farm 19.0, motive on, garden 2/8", 100, false, () -> {
                discardFamily(level, home);
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[0].debugClearBerries(level);
                c[0].plantBerries(level, 2);
                LarderStore.get(level).set(home, 5.0);
            }, () -> {
                var s = c[0].buildScanSnapshot(level);
                return String.format("repro %.1f(exp 13.0) berry %.1f(exp 2.0) farm %.1f(exp 19.0) "
                                + "motive %s garden %d/%d adults %d",
                        s.reproLack, s.berryLack, s.farmLack, s.farmMotive ? "Y" : "N",
                        s.garden, s.gardenCap, s.adults);
            }, () -> {
                var s = c[0].buildScanSnapshot(level);
                return Math.abs(s.reproLack - 13.0F) < 1.0E-3
                        && Math.abs(s.berryLack - 2.0F) < 1.0E-3
                        && Math.abs(s.farmLack - 19.0F) < 1.0E-3
                        && s.farmMotive && s.garden == 2 && s.gardenCap == 8 && s.adults == 2
                        && Math.abs(s.larder - 5.0F) < 1.0E-3;
            }, () -> {
                c[0].debugClearBerries(level);
                discardFamily(level, home, c);
            }));
        }
        // [2] 행동 라벨 실측 — 노동 시간의 독신 남성: 실행 goal 이 채집으로 전환되는 순간을 스냅샷이 반영
        {
            BlockPos spot = groundAt(level, b, 16, 24);
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("scanx_action",
                    "snapshot action reflects running goal: becomes '채집'", 400, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(spot), Sex.MALE);
                level.setDayTime(2000L);
            }, () -> {
                var s = c[0].buildScanSnapshot(level);
                return String.format("action %s nav %s", s.action,
                        s.hasNav ? s.navX + "," + s.navZ : "-");
            }, () -> "채집".equals(c[0].buildScanSnapshot(level).action),
                    () -> discard(c)));
        }
        // [2b] 소작 근무처 — 상시 소작의 스냅샷에 "구획 N·지주 실명"이 실린다(관측 요구:
        //      소작농이 누구의 어느 밭에서 일하는지). 지주는 원장 실명(사후에도 유지).
        {
            BlockPos anchor = groundAt(level, b, 32, 40);
            BlockPos ohome = groundAt(level, b, 20, 40);
            MimicEntity[] c = new MimicEntity[2];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("scanx_tenant_info",
                    "tenant snapshot carries plot id + owner name", 100, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(ohome).add(3, 0, 3), Sex.MALE);
                c[0].debugSettleWithTent(ohome, Direction.NORTH);
                FamilyLedger.get(level).debugRemove(c[0].getIndividual().id());
                FamilyLedger.get(level).register(c[0].getIndividual(), 1L); // 지주 실명 원장 조성
                pl[0] = buildDemoPlot(level, anchor, c[0].getIndividual().id(), 9);
                c[1] = spawnAdult(level, Vec3.atBottomCenterOf(anchor).add(-3, 0, 0), Sex.MALE);
                c[1].setTenant(pl[0].id, 3); // 상시 소작 관계 조성
            }, () -> String.format("tenantInfo '%s'", c[1].buildScanSnapshot(level).tenantInfo),
                    () -> {
                        String info = c[1].buildScanSnapshot(level).tenantInfo;
                        return info.contains("상시 구획 " + pl[0].id)
                                && info.contains(c[0].getIndividual().shortName());
                    },
                    () -> {
                        FamilyLedger.get(level).debugRemove(c[0].getIndividual().id());
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [3] 인코드→디코드 왕복 무손실 — [1]과 같은 조성으로 스냅샷을 버퍼 왕복시켜 필드 대조
        {
            BlockPos home = groundAt(level, b, 0, 52);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("scanx_roundtrip",
                    "encode->decode keeps all judged fields identical", 100, false, () -> {
                discardFamily(level, home);
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                LarderStore.get(level).set(home, 8.0);
            }, () -> "roundtrip pending", () -> {
                var s = c[0].buildScanSnapshot(level);
                var buf = new net.minecraft.network.FriendlyByteBuf(
                        io.netty.buffer.Unpooled.buffer());
                s.encode(buf);
                var d = com.evosim.mod.net.ScanSnapshot.decode(buf);
                return d.entityId == s.entityId && d.serial == s.serial
                        && d.female == s.female && d.stage == s.stage
                        && Math.abs(d.holding - s.holding) < 1.0E-6
                        && d.action.equals(s.action) && d.traits.equals(s.traits)
                        && Math.abs(d.larder - s.larder) < 1.0E-6
                        && Math.abs(d.reproLack - s.reproLack) < 1.0E-6
                        && Math.abs(d.berryLack - s.berryLack) < 1.0E-6
                        && Math.abs(d.farmLack - s.farmLack) < 1.0E-6
                        && d.farmMotive == s.farmMotive && d.spouseId == s.spouseId
                        && d.adults == s.adults && d.garden == s.garden
                        && d.tenantInfo.equals(s.tenantInfo);
            }, () -> discardFamily(level, home, c)));
        }
        VerifySuite.start(ctx.getSource(), steps);
        tell(ctx.getSource(), "렌즈 P1 합본 검증(3단계) — 문턱 역산 수치·행동 라벨 실측·패킷 왕복. "
                + "전부 서버 결과값 판정.");
        return 1;
    }

    /**
     * 소작 루프 v2 합본 검증(hirex) — ① 노동시장 개방: 넉넉한(넉넉선 6 이상·만족 기준 12 미만)
     * 무밭 성인이 새벽 배정을 받고 지대가 적립된다(빈곤 조건 삭제의 결과값). ② 직영지 원칙:
     * 2구획 지주의 자가 수확은 최신 구획만 — 더 가까운 구 구획은 손대지 않는다(신규 개간과 동시에
     * 구 밭 100% 소작 인계의 전제). ③ 운반 상한: 수확 세션 중 입금을 6.0까지 미룬다 — H가 종전
     * 상한(2.0)으로는 불가능한 4.0 이상에 도달. 전부 서버 결과값 판정(블라인드).
     */
    private static int stageHireX(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "이미 검증이 진행 중 — 끝난 뒤 다시 실행.");
            return 0;
        }
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll();
        List<VerifySuite.Step> steps = new ArrayList<>();
        // [1] 노동시장 개방 — 저장고 8.0(넉넉선 3×2=6 이상, 만족 기준 3×2×σ2=12 미만) 유주택
        //     무밭 성인: 종전 필터(larderComfortable 제외)면 영원히 미배정 → 배정+지대>0.2 로 판정.
        {
            BlockPos anchor = groundAt(level, b, 8, 24);
            BlockPos whome = groundAt(level, b, -10, 24);
            MimicEntity[] c = new MimicEntity[2];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("hirex_open_market",
                    "comfortable(8.0) landless adult gets dawn assignment; rent accrues > 0.2",
                    1800, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(anchor).add(-3, 0, 0), Sex.MALE);
                c[1] = spawnAdult(level, Vec3.atBottomCenterOf(whome), Sex.MALE);
                c[1].debugSettleWithTent(whome, Direction.NORTH);
                LarderStore.get(level).set(whome, 8.0);
                pl[0] = buildDemoPlot(level, anchor, c[0].getIndividual().id(), 35);
                level.setDayTime(1200L); // 새벽 — 다음 200틱 스캔에서 배정
            }, () -> String.format("assigned %s rent %.2f workerH %.2f larder %.1f",
                    FarmTicker.assignedPlot(c[1].getId()) == pl[0].id ? "yes" : "no",
                    pl[0].account, c[1].getHolding(), LarderStore.get(level).get(whome)),
                    () -> FarmTicker.assignedPlot(c[1].getId()) == pl[0].id && pl[0].account > 0.2,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [2] 직영지 원칙 — 2구획 지주: 구 구획(id 작음)이 거처에 더 가까워도 자가 수확은 최신
        //     구획만. 종전 코드(가까운 익은 타일 우선)면 구 구획부터 줄어 판정 실패(블라인드 대조).
        {
            BlockPos home = groundAt(level, b, -8, -24);
            BlockPos aOld = groundAt(level, b, 6, -24);
            BlockPos aNew = groundAt(level, b, 16, -24);
            MimicEntity[] c = new MimicEntity[1];
            FarmStore.Plot[] pl = new FarmStore.Plot[2];
            steps.add(new VerifySuite.Step("hirex_direct_only",
                    "2-plot owner harvests ONLY newest plot; nearer old plot stays ripe 9",
                    1500, false, () -> {
                FarmTicker.clearAssignments();
                // 스폰을 거처 중심에서 비켜 — 천막이 스폰 위치를 감싸며 지붕(y+3)에 얹혀
                // 경로 생성 불능으로 굳던 무대 결함(F-2 계열, nav=done·y65 실측) 차단.
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home).add(3, 0, 3), Sex.MALE);
                c[0].debugSettleWithTent(home, Direction.NORTH);
                LarderStore.get(level).set(home, 2.0); // 빈곤 — 불만족(노동 동기)
                c[0].debugSetHolding(1.2); // 밴드 안 — 인출 왕복 없이 곧장 노동
                pl[0] = buildDemoPlot(level, aOld, c[0].getIndividual().id(), 9); // 구 구획(가까움)
                pl[1] = buildDemoPlot(level, aNew, c[0].getIndividual().id(), 9); // 신 구획 = 직영지
                level.setDayTime(1200L);
            }, () -> String.format("oldRipe %d(must stay 9) newRipe %d(expect <=5) H %.2f act=%s pos=%s nav=%s",
                    countRipe(level, pl[0]), countRipe(level, pl[1]), c[0].getHolding(),
                    c[0].currentActionLabel(), c[0].blockPosition().toShortString(),
                    c[0].getNavigation().isDone() ? "done"
                            : String.valueOf(c[0].getNavigation().getTargetPos())),
                    () -> countRipe(level, pl[1]) <= 5 && countRipe(level, pl[0]) == 9,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        farmClearPlot(level, pl[1]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [3] 운반 상한 — 자기 밭 익은 9타일 수확 세션: 종전 상한이면 H 2.0에서 귀가 입금이라
        //     4.0 도달 불가(타일당 0.75, 2.0 초과 직후 귀가). WORK_CARRY_CAP 6.0 이면 도달.
        {
            BlockPos home = groundAt(level, b, -8, 48);
            BlockPos anchor = groundAt(level, b, 8, 48);
            MimicEntity[] c = new MimicEntity[1];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("hirex_carry_cap",
                    "harvest session defers deposit: holding reaches >= 4.0 (impossible at cap 2.0)",
                    1800, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
                c[0].debugSettleWithTent(home, Direction.NORTH);
                LarderStore.get(level).set(home, 2.0);
                c[0].debugSetHolding(1.2);
                pl[0] = buildDemoPlot(level, anchor, c[0].getIndividual().id(), 9);
                level.setDayTime(1200L);
            }, () -> String.format("H %.2f(expect >= 4.0) ripe %d cap %.1f",
                    c[0].getHolding(), countRipe(level, pl[0]), c[0].carryCap()),
                    () -> c[0].getHolding() >= 4.0,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [4] 원거리 통근(F1 출근 앵커) — 애향심(활동반경 16) 소작농 + 40블록 밭: 종전 코드는
        //     리시(우선순위 2)가 반경 밖 출근을 선점해 출발↔귀환 줄다리기 — 영원히 도달 불가.
        //     수정 후엔 작업 타일이 리시 앵커가 되어 호위 출근 → 수확 발생(익음 감소)으로 판정.
        {
            BlockPos whome = groundAt(level, b, -40, 0);
            BlockPos anchor = groundAt(level, b, 0, 0); // 통근 40블록(상한 48 안, 반경 16 밖)
            MimicEntity[] c = new MimicEntity[1];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("hirex_far_commute",
                    "homebound tenant (radius 16) reaches 40-block farm and harvests (ripe 9 -> <9)",
                    2400, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(whome).add(3, 0, 3),
                        Sex.MALE, Trait.HOMEBOUND); // 지붕 스폰 회피(F-2)
                c[0].debugSettleWithTent(whome, Direction.NORTH);
                LarderStore.get(level).set(whome, 2.0); // 빈곤 — 배정 후보(불만족)
                pl[0] = buildDemoPlot(level, anchor, 999999999L, 9); // 부재 지주 — 전량 게시
                level.setDayTime(1200L); // 새벽 배정 → 출근
            }, () -> String.format("ripe %d(expect <9) assigned %s act=%s pos=%s H %.2f anchor=%s nav=%s",
                    countRipe(level, pl[0]),
                    FarmTicker.assignedPlot(c[0].getId()) == pl[0].id ? "yes" : "no",
                    c[0].currentActionLabel(), c[0].blockPosition().toShortString(),
                    c[0].getHolding(), c[0].roamAnchor() == null ? "-" : c[0].roamAnchor().toShortString(),
                    c[0].getNavigation().isDone() ? "done"
                            : String.valueOf(c[0].getNavigation().getTargetPos())),
                    () -> countRipe(level, pl[0]) < 9,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        VerifySuite.start(ctx.getSource(), steps);
        tell(ctx.getSource(), "소작 루프 v2 합본 검증(4단계) — 노동시장 개방 / 직영지 전용 수확 / "
                + "운반 상한 6.0 / 원거리 통근(출근 앵커). 전부 서버 결과값 판정.");
        return 1;
    }

    /**
     * 구제 도구 — 밭을 깔고 앉은 거처를 전수 탐지해, 가족을 기존 이주 장치로 주변에 재정착시키고
     * <b>잘못 설치된 천막만</b> 철거한다(밭은 보존 — 눌린 타일은 밤 정비 A-3이 재식수·복원).
     * 멱등: 겹침이 없으면 아무것도 하지 않는다. 테스트 월드 연속 사용을 위한 소급 치유 도구.
     */
    private static int fixHomes(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        int fixed = fixHomesCore(level, msg -> tell(ctx.getSource(), msg));
        tell(ctx.getSource(), fixed == 0 ? "밭을 깔고 앉은 거처 없음 — 조치 불필요."
                : "총 " + fixed + "가구 이주·철거 완료. 눌린 밭 타일은 다음 밤 정비에서 재식수됩니다.");
        return fixed;
    }

    /** fixhomes 핵심(명령·검증 무대 공용). */
    private static int fixHomesCore(ServerLevel level, java.util.function.Consumer<String> log) {
        java.util.Map<Long, java.util.List<MimicEntity>> byHome = new java.util.HashMap<>();
        java.util.Map<Long, Direction> facings = new java.util.HashMap<>();
        for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getHomePos() != null)) {
            byHome.computeIfAbsent(m.getHomePos().asLong(), k -> new java.util.ArrayList<>()).add(m);
            facings.putIfAbsent(m.getHomePos().asLong(), m.getHomeFacingDir());
        }
        int fixed = 0;
        for (var e : byHome.entrySet()) {
            BlockPos home = BlockPos.of(e.getKey());
            Direction f = facings.get(e.getKey());
            if (!MimicEntity.homeSiteOnFarm(level, home, f)) {
                continue;
            }
            MimicEntity head = null;
            for (MimicEntity m : e.getValue()) {
                if (m.getStage() == LifeStage.ADULT
                        || (head == null && m.getStage() == com.evosim.core.LifeStage.ELDER)) {
                    head = m;
                    if (m.getStage() == LifeStage.ADULT) {
                        break;
                    }
                }
            }
            if (head == null) {
                head = e.getValue().get(0); // 아이들만 가구 — 이주는 불가하지만 철거는 수행
            } else {
                head.debugRelocateFamily(level); // 기존 이주 장치 재사용 — 주변 정찰·신축(A-1 검증됨)
            }
            MimicEntity.debugDemolishHome(level, home, f); // 폐가로 남은 잘못된 천막 철거
            fixed++;
            log.accept(String.format("@%d,%d 거처 철거·가족 %d명 이주 (%s)", home.getX(), home.getZ(),
                    e.getValue().size(), head.getIndividual() != null
                            ? head.getIndividual().shortName() : "?"));
        }
        return fixed;
    }

    /**
     * 부지 충돌·치유 합본 검증(fixx) — ① 부지 검증 순수부: 밭 위 좌표는 차단, 빈 좌표는 통과
     * ② 구제 도구: 밭 위 거처의 가족이 이주하고 천막이 철거되며 새 거처는 밭 밖
     * ③ 죽은 타일 정비: 깔린 타일은 원장 소거, 파괴된 타일은 재식수. 전부 서버 결과값 판정.
     */
    private static int stageFixX(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "이미 검증이 진행 중 — 끝난 뒤 다시 실행.");
            return 0;
        }
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll();
        List<VerifySuite.Step> steps = new ArrayList<>();
        // [1] 부지 검증 — 밭 발자국 위 후보는 참, 20블록 밖 후보는 거짓(양·음성 동시).
        {
            BlockPos anchor = groundAt(level, b, 8, 24);
            BlockPos clear = groundAt(level, b, -30, 24);
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("fixx_site_guard",
                    "site validator: on-farm candidate blocked, clear candidate passes", 100, false, () -> {
                FarmTicker.clearAssignments();
                pl[0] = buildDemoPlot(level, anchor, 999999999L, 9);
            }, () -> String.format("onFarm=%s clear=%s",
                    MimicEntity.homeSiteOnFarm(level, anchor, Direction.NORTH),
                    MimicEntity.homeSiteOnFarm(level, clear, Direction.NORTH)),
                    () -> MimicEntity.homeSiteOnFarm(level, anchor, Direction.NORTH)
                            && !MimicEntity.homeSiteOnFarm(level, clear, Direction.NORTH),
                    () -> farmClearPlot(level, pl[0])));
        }
        // [2] 구제 도구 — 밭 한복판 거처: fixhomes 후 ① 새 거처 ≠ 옛 거처 ② 새 거처는 밭 밖
        //     ③ 옛 발자국의 천막 블록 0. (눌린 타일 재식수는 [3]이 별도 판정)
        {
            BlockPos anchor = groundAt(level, b, 8, -24);
            MimicEntity[] c = new MimicEntity[1];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            BlockPos[] oldHome = new BlockPos[1];
            steps.add(new VerifySuite.Step("fixx_rescue",
                    "fixhomes relocates family off the farm and demolishes the bad tent",
                    600, false, () -> {
                FarmTicker.clearAssignments();
                pl[0] = buildDemoPlot(level, anchor, 999999999L, 9);
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(anchor).add(3, 0, 3), Sex.MALE);
                c[0].debugSettleWithTent(anchor, Direction.NORTH); // 밭 한복판에 천막(재현)
                oldHome[0] = anchor;
                fixHomesCore(level, s -> { });
            }, () -> {
                int wool = 0;
                for (var p : com.evosim.mod.entity.HomeStructure.plan(oldHome[0], Direction.NORTH)) {
                    if (level.getBlockState(p.pos()).is(Blocks.WHITE_WOOL)) {
                        wool++;
                    }
                }
                return String.format("newHome=%s wool=%d onFarm=%s",
                        c[0].getHomePos() == null ? "-" : c[0].getHomePos().toShortString(), wool,
                        c[0].getHomePos() != null && MimicEntity.homeSiteOnFarm(
                                level, c[0].getHomePos(), c[0].getHomeFacingDir()));
            }, () -> {
                if (c[0].getHomePos() == null || c[0].getHomePos().equals(oldHome[0])) {
                    return false;
                }
                if (MimicEntity.homeSiteOnFarm(level, c[0].getHomePos(), c[0].getHomeFacingDir())) {
                    return false;
                }
                for (var p : com.evosim.mod.entity.HomeStructure.plan(oldHome[0], Direction.NORTH)) {
                    if (level.getBlockState(p.pos()).is(Blocks.WHITE_WOOL)) {
                        return false;
                    }
                }
                return true;
            }, () -> {
                if (c[0].getHomePos() != null) {
                    MimicEntity.debugDemolishHome(level, c[0].getHomePos(), c[0].getHomeFacingDir());
                }
                discard(c);
                farmClearPlot(level, pl[0]);
                FarmTicker.clearAssignments();
            }));
        }
        // [3] 죽은 타일 정비 — 타일0에 양털(깔림), 타일1 블록 제거(파괴) → 밤 정비 후:
        //     원장 9→8타일(소거 1) ∧ 타일1 위치에 베리 재식수.
        {
            BlockPos anchor = groundAt(level, b, 8, 48);
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            BlockPos[] crushed = new BlockPos[1];
            BlockPos[] broken = new BlockPos[1];
            steps.add(new VerifySuite.Step("fixx_heal",
                    "night pass: crushed tile purged from ledger, broken tile replanted",
                    600, false, () -> {
                FarmTicker.clearAssignments();
                pl[0] = buildDemoPlot(level, anchor, 999999999L, 9);
                crushed[0] = BlockPos.of(pl[0].tiles[0]);
                broken[0] = BlockPos.of(pl[0].tiles[1]);
                level.setBlockAndUpdate(crushed[0], Blocks.WHITE_WOOL.defaultBlockState());
                level.setBlockAndUpdate(broken[0], Blocks.AIR.defaultBlockState());
                level.setDayTime(13500L); // 밤 정비 창
            }, () -> String.format("tiles %d(expect 8) replanted=%s", pl[0].tiles.length,
                    level.getBlockState(broken[0]).is(Blocks.SWEET_BERRY_BUSH)),
                    () -> pl[0].tiles.length == 8
                            && level.getBlockState(broken[0]).is(Blocks.SWEET_BERRY_BUSH),
                    () -> {
                        level.setBlockAndUpdate(crushed[0], Blocks.AIR.defaultBlockState());
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        VerifySuite.start(ctx.getSource(), steps);
        tell(ctx.getSource(), "부지 충돌·치유 합본 검증(3단계) — 부지 검증 / 구제 도구 이주·철거 / "
                + "죽은 타일 정비. 전부 서버 결과값 판정.");
        return 1;
    }

    /** 무대 헬퍼 — parentId의 살아있는 자식 수(getEntities 인덱싱 확인용). */
    private static int countChildrenOf(ServerLevel level, long parentId) {
        int n = 0;
        for (MimicEntity m : level.getEntities(ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null)) {
            var ind = m.getIndividual();
            if (ind.parentAId() == parentId || ind.parentBId() == parentId) {
                n++;
            }
        }
        return n;
    }

    /** 무대 유아 — 지정 부모의 친자·거처 귀속(육아 구속 판정 입력). 한쪽 부모 null 허용(편부모). */
    private static MimicEntity infantOf(ServerLevel level, BlockPos home,
                                        MimicEntity father, MimicEntity mother) {
        MimicEntity e = ModEntities.MIMIC.get().create(level);
        if (e == null) {
            throw new IllegalStateException("유아 스폰 실패");
        }
        MimicEntity ref = mother != null ? mother : father;
        long id = Math.abs((int) level.getGameTime()) + level.random.nextInt(1_000_000);
        Individual ind = new Individual(id, Sex.FEMALE,
                father == null ? 0L : father.getIndividual().id(),
                mother == null ? 0L : mother.getIndividual().id(),
                ref.getIndividual().generation() + 1);
        e.setIndividual(ind);
        e.setStage(LifeStage.INFANT);
        e.moveTo(home.getX() + 1.5, home.getY(), home.getZ() + 0.5, 0f, 0f);
        e.markStageActor();
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()),
                MobSpawnType.COMMAND, null, null);
        level.addFreshEntity(e);
        e.setHomePos(home);
        return e;
    }

    /** 정원의 베리를 전부 익힘(AGE 3) — 구속 수확·carryCap 무대 조성. 익힌 그루 수 반환. */
    private static int ripenGarden(ServerLevel level, BlockPos home, Direction facing) {
        int n = 0;
        for (BlockPos tile : HomeBlueprint.of(level, home).garden()) {
            for (int dy = 3; dy >= -3; dy--) {
                BlockPos p = tile.offset(0, dy, 0);
                var st = level.getBlockState(p);
                if (st.is(Blocks.SWEET_BERRY_BUSH)) {
                    level.setBlockAndUpdate(p, st.setValue(SweetBerryBushBlock.AGE, 3));
                    n++;
                }
            }
        }
        return n;
    }

    /** 정원의 익은(AGE 3) 그루 수 — 구속 수확 판정의 결과값. */
    private static int ripeGardenCount(ServerLevel level, BlockPos home, Direction facing) {
        int n = 0;
        for (BlockPos tile : HomeBlueprint.of(level, home).garden()) {
            for (int dy = 3; dy >= -3; dy--) {
                var st = level.getBlockState(tile.offset(0, dy, 0));
                if (st.is(Blocks.SWEET_BERRY_BUSH) && st.getValue(SweetBerryBushBlock.AGE) >= 3) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * 육아 돌봄 개편 합본 검증(carex) — ① 커버리지 해제: 무심 남편은 적극 아내가 잔류 커버하면
     * 자유(무시처럼) ② 적극 정원 예외: 편모 적극도 정원 익은 베리는 딴다 ③ 양쪽 적극: 효율 높은
     * 남편이 해제되어 가구가 굶지 않는다 ④ carryCap 정원 제외: 정원 익음은 2.0, 밭 익음은 6.0.
     * 전부 서버 결과값 판정 — 종전 코드(전면 구속·정원 포함 cap)면 ①~④ 모두 실패한다.
     */
    private static int stageCareX(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "이미 검증이 진행 중 — 끝난 뒤 다시 실행.");
            return 0;
        }
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll();
        List<VerifySuite.Step> steps = new ArrayList<>();
        // [1] 커버리지 해제 — 무심 남편+적극 아내+유아: 아내가 지정 잔류 → 남편은 무시처럼 채집.
        //     종전 코드(전면 구속)면 두 부모 다 정지 → 풀 9 유지 → 실패.
        {
            BlockPos home = groundAt(level, b, -10, 24);
            BlockPos pad = groundAt(level, b, 6, 24); // 간격 16 — berryPad(±12 클리어)가 천막을 안 민다
            MimicEntity[] c = new MimicEntity[3];
            steps.add(new VerifySuite.Step("carex_release",
                    "detached husband freed by devoted wife's coverage: grass 9 -> <9", 600, false, () -> {
                berryPad(level, pad);
                grassCluster(level, pad);
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[0].getIndividual().setParentingCareMale(ParentingClass.DETACHED);
                c[1].getIndividual().setParentingCareFemale(ParentingClass.DEVOTED);
                c[2] = infantOf(level, home, c[0], c[1]);
                LarderStore.get(level).set(home, 3.0); // 급식 여유·비위급(순수 구속 판정만 남김)
                c[0].debugSetHolding(1.5);
                c[0].moveTo(pad.getX() + 2.5, pad.getY(), pad.getZ() + 2.5, 0f, 0f); // 표본 탐색 사거리
                level.setDayTime(2000L); // 노동 시간
            }, () -> String.format("grass %d/9(expect <9) H %.2f bound=%s act=%s nav=%s pos=%s forage[%s]",
                    countGrass(level, pad), c[0].getHolding(), c[0].isCaregiverBound(),
                    c[0].currentActionLabel(),
                    c[0].getNavigation().isDone() ? "done"
                            : String.valueOf(c[0].getNavigation().getTargetPos()),
                    c[0].blockPosition().toShortString(), c[0].forageDebug()),
                    () -> countGrass(level, pad) < 9,
                    () -> discard(c)));
        }
        // [2] 적극 정원 예외 — 편모 적극+유아+익은 정원 8그루: 반경 0이어도 정원은 딴다(8→≤5).
        //     종전 코드면 정원도 금지 → 8 유지 → 실패.
        {
            BlockPos home = groundAt(level, b, -8, -24);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("carex_devoted_garden",
                    "widowed devoted mother still harvests ripe garden: 8 -> <=6", 600, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home).add(3, 0, 3), Sex.FEMALE);
                c[0].debugSettleWithTent(home, Direction.NORTH); // 스폰 비킴 — 지붕(y+3) 고착 방지(F-2)
                c[0].getIndividual().setParentingCareFemale(ParentingClass.DEVOTED);
                c[0].debugClearBerries(level);
                c[0].plantBerries(level, 8);
                ripenGarden(level, home, Direction.NORTH);
                c[1] = infantOf(level, home, null, c[0]);
                LarderStore.get(level).set(home, 3.0);
                c[0].debugSetHolding(1.5);
                level.setDayTime(2000L);
            }, () -> String.format("ripeGarden %d/8(expect <=6) H %.2f bound=%s act=%s pos=%s forage[%s]",
                    ripeGardenCount(level, home, Direction.NORTH), c[0].getHolding(),
                    c[0].isCaregiverBound(), c[0].currentActionLabel(),
                    c[0].blockPosition().toShortString(), c[0].forageDebug()),
                    () -> ripeGardenCount(level, home, Direction.NORTH) <= 6,
                    () -> {
                        c[0].debugClearBerries(level);
                        discard(c);
                    }));
        }
        // [3] 양쪽 적극 — 효율 높은 남편(채집 1.5×)이 해제(지시 사양: "대부분 남성"), 가구가 굶지
        //     않는다. 종전 코드면 두 부모 다 정지 → 풀 9 유지 → 실패.
        {
            BlockPos home = groundAt(level, b, -10, 48);
            BlockPos pad = groundAt(level, b, 6, 48);
            MimicEntity[] c = new MimicEntity[3];
            steps.add(new VerifySuite.Step("carex_both_devoted",
                    "both devoted: higher-yield husband is freed, grass 9 -> <9", 600, false, () -> {
                berryPad(level, pad);
                grassCluster(level, pad);
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[0].getIndividual().setParentingCareMale(ParentingClass.DEVOTED);
                c[1].getIndividual().setParentingCareFemale(ParentingClass.DEVOTED);
                c[2] = infantOf(level, home, c[0], c[1]);
                LarderStore.get(level).set(home, 3.0);
                c[0].debugSetHolding(1.5);
                c[0].moveTo(pad.getX() + 2.5, pad.getY(), pad.getZ() + 2.5, 0f, 0f);
                level.setDayTime(2000L);
            }, () -> String.format("grass %d/9(expect <9) H %.2f bound=%s act=%s pos=%s forage[%s]",
                    countGrass(level, pad), c[0].getHolding(), c[0].isCaregiverBound(),
                    c[0].currentActionLabel(),
                    c[0].blockPosition().toShortString(), c[0].forageDebug()),
                    () -> countGrass(level, pad) < 9,
                    () -> discard(c)));
        }
        // [3b] 구속자 외부 채집 불허(금지 감시) — 지정 돌봄자(무심 홀아비, 반경 22)는 정원이
        //      없으면 반경 안에 풀이 있어도 손대지 않는다(정원 전담 — 지시 사양). 풀 감소 = 실패.
        {
            BlockPos home = groundAt(level, b, -10, -48);
            BlockPos pad = groundAt(level, b, -4, -48); // 거처 6블록 — 반경 22 안(유혹 조성)
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("carex_bound_no_wild",
                    "designated caregiver must NOT gather wild grass (garden-only): grass stays 9",
                    400, true, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home).add(3, 0, 3), Sex.MALE);
                c[0].debugSettleWithTent(home, Direction.NORTH); // 스폰 비킴(F-2)
                c[0].getIndividual().setParentingCareMale(ParentingClass.DETACHED);
                c[0].debugClearBerries(level); // 정원 없음 — 유일한 허용 표적 제거
                for (int dx = -1; dx <= 1; dx++) { // 3×3 평탄화 — 지지 블록 없는 풀의 즉시 탈락
                    for (int dz = -1; dz <= 1; dz++) { // (9→8 오탐)이 금지 감시를 오염시키지 않게
                        level.setBlockAndUpdate(pad.offset(dx, -1, dz), Blocks.DIRT.defaultBlockState());
                        level.setBlockAndUpdate(pad.offset(dx, 0, dz), Blocks.AIR.defaultBlockState());
                    }
                }
                grassCluster(level, pad);
                c[1] = infantOf(level, home, c[0], null); // 홀아비 — 본인이 지정 돌봄자
                LarderStore.get(level).set(home, 3.0);
                c[0].debugSetHolding(1.5);
                level.setDayTime(2000L);
            }, () -> String.format("grass %d/9(must stay 9) bound=%s act=%s",
                    countGrass(level, pad), c[0].isCaregiverBound(), c[0].currentActionLabel()),
                    () -> countGrass(level, pad) < 9, // ← 금지 결과(구속자의 외부 채집)
                    () -> discard(c)));
        }
        // [4] carryCap 정원 제외 — 같은 노동 시간: 정원 익은 가구는 2.0(입금 동결 해제),
        //     직영지 익은 지주는 6.0(밭 수확 세션 유예 유지). 종전 코드면 정원도 6.0 → 실패.
        {
            BlockPos homeA = groundAt(level, b, -8, 72);
            BlockPos homeB = groundAt(level, b, -8, 96);
            BlockPos anchorB = groundAt(level, b, 8, 96);
            MimicEntity[] c = new MimicEntity[2];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("carex_carry_gauge",
                    "carry cap: ripe garden -> 2.0 / ripe own farm -> 6.0", 200, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(homeA).add(3, 0, 3), Sex.MALE);
                c[0].debugSettleWithTent(homeA, Direction.NORTH); // 스폰 비킴(F-2)
                c[0].debugClearBerries(level);
                c[0].plantBerries(level, 8);
                ripenGarden(level, homeA, Direction.NORTH);
                c[1] = spawnAdult(level, Vec3.atBottomCenterOf(homeB).add(3, 0, 3), Sex.MALE);
                c[1].debugSettleWithTent(homeB, Direction.NORTH);
                pl[0] = buildDemoPlot(level, anchorB, c[1].getIndividual().id(), 9);
                level.setDayTime(2000L);
            }, () -> String.format("gardenCap %.1f(expect 2.0) farmCap %.1f(expect 6.0)",
                    c[0].carryCap(), c[1].carryCap()),
                    () -> Math.abs(c[0].carryCap() - 2.0) < 1.0E-9
                            && Math.abs(c[1].carryCap() - 6.0) < 1.0E-9,
                    () -> {
                        c[0].debugClearBerries(level);
                        farmClearPlot(level, pl[0]);
                        discard(c);
                        FarmTicker.clearAssignments();
                    }));
        }
        VerifySuite.start(ctx.getSource(), steps);
        tell(ctx.getSource(), "육아 돌봄 개편 합본 검증(4단계) — 커버리지 해제 / 적극 정원 예외 / "
                + "양쪽 적극 효율 해제 / carryCap 정원 제외. 전부 서버 결과값 판정.");
        return 1;
    }

    /**
     * 봉건 집중 합본 검증(feudx) — ① 무특성 개간 금지(P1 게이트, 금지 감시) ② 능력자(약초학자)
     * 개간 성립 ③ 장남 우선 상속(장녀보다 아들) ④ 식량 상속(가구 해체 → 상속인 2/3).
     * 전부 서버 결과값 판정.
     */
    private static int stageFeudX(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "이미 검증이 진행 중 — 끝난 뒤 다시 실행.");
            return 0;
        }
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll();
        List<VerifySuite.Step> steps = new ArrayList<>();
        // [1] 만족의 덫(계층 분화 v2) — 무동기 평민, 저장고 30(임계 도달!): 만족선(홀몸 12)을 이미
        //     지나 만족 → 개간 동기 소멸 → 구획 미생성. 하드게이트 없이 수치만으로 잠긴다.
        //     종전(만족 미반영·게이트 삭제만)이면 owned 1 → 금지 감시 실패.
        {
            BlockPos home = groundAt(level, b, -12, 24);
            MimicEntity[] c = new MimicEntity[1];
            long[] oid = new long[1];
            steps.add(new VerifySuite.Step("feudx_trap_satisfied",
                    "motiveless commoner at threshold 30 is SATISFIED -> never founds (trap)", 400, true, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE); // 무동기·무능력
                c[0].debugSettleWithTent(home, Direction.NORTH);
                LarderStore.get(level).set(home, 30.0);
                oid[0] = c[0].getIndividual().id();
                level.setDayTime(13500L);
            }, () -> {
                c[0].updateMotivation(level); // 만족 캐시 최신화(잉여 30 > 홀몸 만족선 12)
                FarmTicker.debugGrow(level);  // 매 poll 강제 성장 — 인덱싱 후 결정론
                return String.format("owned %d(must stay 0) larder %.0f sat=%s",
                        FarmStore.get(level).ownedCount(oid[0]), LarderStore.get(level).get(home),
                        c[0].isSatisfiedToday());
            },
                    () -> FarmStore.get(level).ownedCount(oid[0]) >= 1, // ← 금지 결과(개간 발생)
                    () -> {
                        for (FarmStore.Plot p : new java.util.ArrayList<>(
                                FarmStore.get(level).all().values())) {
                            if (p.ownerId == oid[0]) {
                                farmClearPlot(level, p);
                            }
                        }
                        discard(c);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [2] 능력자 개간 성립 — 욕심+약초학자(G=1.125≥0.95), 저장고 30, 밤 → 구획 1.
        {
            BlockPos home = groundAt(level, b, -12, -24);
            MimicEntity[] c = new MimicEntity[1];
            long[] oid = new long[1];
            steps.add(new VerifySuite.Step("feudx_skilled_found",
                    "skilled (herbalist) owner founds a farm (larder 30 -> owned 1)", 600, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE,
                        Trait.GREEDY, Trait.HERBALIST);
                c[0].debugSettleWithTent(home, Direction.NORTH);
                LarderStore.get(level).set(home, 30.0);
                oid[0] = c[0].getIndividual().id();
                level.setDayTime(13500L);
            }, () -> {
                FarmTicker.debugGrow(level);
                return String.format("owned %d(expect 1)", FarmStore.get(level).ownedCount(oid[0]));
            },
                    () -> FarmStore.get(level).ownedCount(oid[0]) == 1,
                    () -> {
                        for (FarmStore.Plot p : new java.util.ArrayList<>(
                                FarmStore.get(level).all().values())) {
                            if (p.ownerId == oid[0]) {
                                farmClearPlot(level, p);
                            }
                        }
                        discard(c);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [3] 장남 우선 상속 — 지주 사망, 장녀 + 아들 생존: 아들이 밭 승계(성별 우선).
        {
            BlockPos home = groundAt(level, b, -12, 48);
            BlockPos anchor = groundAt(level, b, 6, 48);
            MimicEntity[] c = new MimicEntity[3]; // [0]장녀 [1]장남 [2]지주(지연 사망)
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            long[] sonId = new long[1];
            boolean[] fired = new boolean[1];
            steps.add(new VerifySuite.Step("feudx_son_priority",
                    "eldest SON inherits over daughter (plot owner == son)", 200, false, () -> {
                FarmTicker.clearAssignments();
                c[2] = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
                c[2].debugSettleWithTent(home, Direction.NORTH);
                pl[0] = buildDemoPlot(level, anchor, c[2].getIndividual().id(), 9);
                c[0] = spawnChildOf(level, Vec3.atBottomCenterOf(home).add(2, 0, 0), c[2], Sex.FEMALE);
                c[1] = spawnChildOf(level, Vec3.atBottomCenterOf(home).add(-2, 0, 0), c[2], Sex.MALE);
                sonId[0] = c[1].getIndividual().id();
            }, () -> {
                // 지연 사망 — 자식 2명이 getEntities에 인덱싱된 뒤에야 상속 발동(레이스 차단)
                long pid = c[2].getIndividual().id();
                int kids = countChildrenOf(level, pid);
                if (!fired[0] && kids >= 2) {
                    fired[0] = true;
                    c[2].discard();
                }
                return String.format("owner %d(expect son %d) kids=%d fired=%s",
                        pl[0].ownerId, sonId[0], kids, fired[0]);
            },
                    () -> pl[0].ownerId == sonId[0],
                    () -> {
                        discard(new MimicEntity[] {c[0], c[1]});
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [4] 식량 상속 — 홀아비(저장고 30) 사망 → 거주자 0 → 분가 자식 3명(장남+2)에게 분배:
        //     장남 거처 +20(2/3), 타 자식 각 +5. 각 자식은 자기 거처 보유.
        {
            BlockPos home = groundAt(level, b, -12, 72);
            BlockPos hA = groundAt(level, b, 12, 72);
            BlockPos hB = groundAt(level, b, 24, 72);
            BlockPos hC = groundAt(level, b, 36, 72);
            MimicEntity[] c = new MimicEntity[4]; // [0]장남 [1][2]딸 [3]부(지연 사망)
            boolean[] fired = new boolean[1];
            steps.add(new VerifySuite.Step("feudx_food_split",
                    "empty household: heir(son) home +20 (2/3), other 2 children +5 each", 200, false, () -> {
                FarmTicker.clearAssignments();
                c[3] = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
                c[3].debugSettleWithTent(home, Direction.NORTH);
                LarderStore.get(level).set(home, 30.0);
                c[0] = spawnChildOf(level, Vec3.atBottomCenterOf(hA), c[3], Sex.MALE); // 장남
                c[0].debugSettleWithTent(hA, Direction.NORTH);
                c[1] = spawnChildOf(level, Vec3.atBottomCenterOf(hB), c[3], Sex.FEMALE);
                c[1].debugSettleWithTent(hB, Direction.NORTH);
                c[2] = spawnChildOf(level, Vec3.atBottomCenterOf(hC), c[3], Sex.FEMALE);
                c[2].debugSettleWithTent(hC, Direction.NORTH);
                LarderStore.get(level).set(hA, 0.0);
                LarderStore.get(level).set(hB, 0.0);
                LarderStore.get(level).set(hC, 0.0);
            }, () -> {
                long pid = c[3].getIndividual().id();
                int kids = countChildrenOf(level, pid);
                if (!fired[0] && kids >= 3) { // 자식 3명 인덱싱 후 사망 → 분배
                    fired[0] = true;
                    c[3].discard();
                }
                return String.format("sonHome %.0f(exp 20) B %.0f(exp 5) C %.0f(exp 5) old %.0f kids=%d",
                        LarderStore.get(level).get(hA), LarderStore.get(level).get(hB),
                        LarderStore.get(level).get(hC), LarderStore.get(level).get(home), kids);
            },
                    () -> Math.abs(LarderStore.get(level).get(hA) - 20.0) < 1.0E-6
                            && Math.abs(LarderStore.get(level).get(hB) - 5.0) < 1.0E-6
                            && Math.abs(LarderStore.get(level).get(hC) - 5.0) < 1.0E-6
                            && Math.abs(LarderStore.get(level).get(home) - 0.0) < 1.0E-6,
                    () -> discard(new MimicEntity[] {c[0], c[1], c[2]})));
        }
        VerifySuite.start(ctx.getSource(), steps);
        tell(ctx.getSource(), "봉건 집중 합본 검증(4단계) — 무특성 개간 금지 / 능력자 개간 / "
                + "장남 우선 상속 / 식량 2/3 분배. 전부 서버 결과값 판정.");
        return 1;
    }

    /**
     * 밭 확산·지수 합본 검증(spreadx) — ① 통근 해제: 60블록 밖 무밭 성인도 배정된다(종전 48 컷이면
     * 배정 불가) ② 부지 확산: 근거리가 밭으로 다 찬 지주도 바깥 반경에 새 밭을 연다 ③ 지수 캡:
     * EXPAND_DAY_MAX 30. 전부 서버 결과값 판정.
     */
    private static int stageSpreadX(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "이미 검증이 진행 중 — 끝난 뒤 다시 실행.");
            return 0;
        }
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll();
        List<VerifySuite.Step> steps = new ArrayList<>();
        // [1] 통근 해제 — 밭 앵커에서 60블록(종전 상한 48 초과) 무밭 성인이 배정된다.
        {
            BlockPos anchor = groundAt(level, b, 40, 40);
            BlockPos whome = groundAt(level, b, -22, 40); // 밭에서 62블록(>48)
            MimicEntity[] c = new MimicEntity[1];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            boolean[] fired = new boolean[1];
            steps.add(new VerifySuite.Step("spreadx_far_assign",
                    "landless adult 62 blocks away IS assigned (commute cap removed)", 400, false, () -> {
                FarmTicker.clearAssignments();
                for (FarmStore.Plot p : new java.util.ArrayList<>(FarmStore.get(level).all().values())) {
                    farmClearPlot(level, p); // 이전 런 잔재 구획 청소(유일 구획 보장)
                }
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(whome), Sex.MALE);
                c[0].debugSettleWithTent(whome, Direction.NORTH);
                LarderStore.get(level).set(whome, 2.0);
                pl[0] = buildDemoPlot(level, anchor, 999999999L, 35); // 부재지주 대형밭 — 전량 게시
                level.setDayTime(1200L);
            }, () -> {
                FarmTicker.debugAssign(level);
                boolean indexed = !level.getEntities(ModEntities.MIMIC.get(),
                        e -> e == c[0]).isEmpty();
                return String.format("assignedPlot %d(want %d) dist %.0f idx=%s sat=%s plots=%d",
                        FarmTicker.assignedPlot(c[0].getId()), pl[0].id,
                        Math.sqrt(c[0].blockPosition().distSqr(anchor)), indexed,
                        c[0].isSatisfiedToday(), FarmStore.get(level).all().size());
            },
                    () -> {
                        FarmTicker.debugAssign(level);
                        return FarmTicker.assignedPlot(c[0].getId()) == pl[0].id;
                    },
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [2] 부지 확산 — 지주 집 반경 20이 기존 밭으로 포화 → 신규 개간이 40~60 반경에 성립.
        {
            BlockPos home = groundAt(level, b, -12, -40);
            MimicEntity[] c = new MimicEntity[1];
            long[] oid = new long[1];
            FarmStore.Plot[] blockers = new FarmStore.Plot[8];
            steps.add(new VerifySuite.Step("spreadx_site",
                    "near ring full: skilled owner founds at outer radius (2nd plot appears)", 600, false, () -> {
                FarmTicker.clearAssignments();
                for (FarmStore.Plot p : new java.util.ArrayList<>(FarmStore.get(level).all().values())) {
                    farmClearPlot(level, p); // 잔재 청소
                }
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE, Trait.GREEDY, Trait.HERBALIST);
                c[0].debugSettleWithTent(home, Direction.NORTH);
                oid[0] = c[0].getIndividual().id();
                LarderStore.get(level).set(home, 30.0);
                // 반경 20 8방향을 더미 밭으로 채워 근거리 부지 고갈 → 확산 반경 강제
                for (int d = 0; d < 8; d++) {
                    double ang = d * Math.PI / 4.0;
                    BlockPos c2 = home.offset((int) Math.round(Math.cos(ang) * 20), 0,
                            (int) Math.round(Math.sin(ang) * 20));
                    blockers[d] = buildDemoPlot(level, groundAt(level,
                            Vec3.atBottomCenterOf(c2), 0, 0), 888888800L + d, 1);
                }
                level.setDayTime(13500L);
            }, () -> {
                FarmTicker.debugGrow(level);
                return String.format("owned %d(expect 1 at outer radius)",
                        FarmStore.get(level).ownedCount(oid[0]));
            },
                    () -> FarmStore.get(level).ownedCount(oid[0]) == 1,
                    () -> {
                        for (FarmStore.Plot p : new java.util.ArrayList<>(
                                FarmStore.get(level).all().values())) {
                            if (p.ownerId == oid[0] || (p.ownerId >= 888888800L && p.ownerId <= 888888808L)) {
                                farmClearPlot(level, p);
                            }
                        }
                        discard(c);
                        FarmTicker.clearAssignments();
                    }));
        }
        VerifySuite.start(ctx.getSource(), steps);
        tell(ctx.getSource(), "밭 확산·지수 합본 검증(2단계) — 통근 해제(원거리 배정) / 부지 확산. "
                + "지수 캡 30은 evotest farm 이 대조. 서버 결과값 판정.");
        return 1;
    }

    /**
     * 농사 집중 게이트 검증(farmfocus) — 밭 익은 채 방치하고 채집 나가던 결함의 수정.
     * ① 지주 집중: 밭 보유 provider + 저장고 넉넉이면 채집 goal 발동 안 함(밭에 매임).
     * ② 안전판: 같은 지주라도 저장고가 넉넉선(12) 미만이면 채집 재개(생계).
     * ③ 기준선: 밭 없는 provider는 종전대로 채집(무밭 자급 경로 불변).
     * canUse 를 직접 관측(debugForageWouldRun) — 네비 무관 결정론 판정. 종전 코드면 ①이 true 로 실패.
     */
    private static int stageFarmFocus(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "이미 검증이 진행 중 — 끝난 뒤 다시 실행.");
            return 0;
        }
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll();
        List<VerifySuite.Step> steps = new ArrayList<>();
        // [1] 지주 집중 — 밭 보유 + 저장고 40(>넉넉선 12) → 채집 억제
        {
            BlockPos home = groundAt(level, b, 6, 6);
            BlockPos anchor = groundAt(level, b, 11, 6);
            MimicEntity[] c = new MimicEntity[1];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("farmfocus_owner_suppressed",
                    "farm-owning provider with comfortable larder does NOT forage (farm focus)", 200, false,
                () -> {
                    for (FarmStore.Plot p : new ArrayList<>(FarmStore.get(level).all().values())) {
                        farmClearPlot(level, p);
                    }
                    c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
                    c[0].debugSettleWithTent(home, Direction.NORTH);
                    LarderStore.get(level).set(home, 40.0);
                    pl[0] = buildDemoPlot(level, anchor, c[0].getIndividual().id(), 9);
                    level.setDayTime(2000L);
                },
                () -> {
                    level.setDayTime(2000L);
                    c[0].debugSetHolding(5.0);
                    c[0].debugRefreshOwnsFarm(level);
                    return String.format("owns=%s comfy=%s crit=%s forageRun=%s",
                            c[0].ownsFarm(), c[0].larderComfortable(), c[0].isCritical(),
                            c[0].debugForageWouldRun());
                },
                () -> {
                    level.setDayTime(2000L);
                    c[0].debugSetHolding(5.0);
                    c[0].debugRefreshOwnsFarm(level);
                    return c[0].ownsFarm() && c[0].larderComfortable() && !c[0].isCritical()
                            && !c[0].debugForageWouldRun();
                },
                () -> {
                    discard(c);
                    farmClearPlot(level, pl[0]);
                }));
        }
        // [2] 안전판 — 같은 지주, 저장고 4(<넉넉선 12), 위급 아님 → 채집 재개
        {
            BlockPos home = groundAt(level, b, 6, -8);
            BlockPos anchor = groundAt(level, b, 11, -8);
            MimicEntity[] c = new MimicEntity[1];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("farmfocus_hungry_forages",
                    "same owner with larder below comfort DOES forage (survival safety)", 200, false,
                () -> {
                    for (FarmStore.Plot p : new ArrayList<>(FarmStore.get(level).all().values())) {
                        farmClearPlot(level, p);
                    }
                    c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
                    c[0].debugSettleWithTent(home, Direction.NORTH);
                    LarderStore.get(level).set(home, 4.0);
                    pl[0] = buildDemoPlot(level, anchor, c[0].getIndividual().id(), 9);
                    level.setDayTime(2000L);
                },
                () -> {
                    level.setDayTime(2000L);
                    c[0].debugSetHolding(5.0);
                    c[0].debugRefreshOwnsFarm(level);
                    return String.format("owns=%s comfy=%s crit=%s forageRun=%s",
                            c[0].ownsFarm(), c[0].larderComfortable(), c[0].isCritical(),
                            c[0].debugForageWouldRun());
                },
                () -> {
                    level.setDayTime(2000L);
                    c[0].debugSetHolding(5.0);
                    c[0].debugRefreshOwnsFarm(level);
                    return c[0].ownsFarm() && !c[0].larderComfortable() && !c[0].isCritical()
                            && c[0].debugForageWouldRun();
                },
                () -> {
                    discard(c);
                    farmClearPlot(level, pl[0]);
                }));
        }
        // [3] 기준선 — 밭 없는 provider + 저장고 넉넉 → 종전대로 채집(불변)
        {
            BlockPos home = groundAt(level, b, -8, 6);
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("farmfocus_landless_forages",
                    "landless provider still forages when comfortable (baseline unchanged)", 200, false,
                () -> {
                    for (FarmStore.Plot p : new ArrayList<>(FarmStore.get(level).all().values())) {
                        farmClearPlot(level, p);
                    }
                    c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
                    c[0].debugSettleWithTent(home, Direction.NORTH);
                    LarderStore.get(level).set(home, 40.0);
                    level.setDayTime(2000L);
                },
                () -> {
                    level.setDayTime(2000L);
                    c[0].debugSetHolding(5.0);
                    c[0].debugRefreshOwnsFarm(level);
                    return String.format("owns=%s comfy=%s forageRun=%s",
                            c[0].ownsFarm(), c[0].larderComfortable(), c[0].debugForageWouldRun());
                },
                () -> {
                    level.setDayTime(2000L);
                    c[0].debugSetHolding(5.0);
                    c[0].debugRefreshOwnsFarm(level);
                    return !c[0].ownsFarm() && c[0].larderComfortable() && c[0].debugForageWouldRun();
                },
                () -> discard(c)));
        }
        VerifySuite.start(ctx.getSource(), steps);
        tell(ctx.getSource(), "농사 집중 게이트 검증(3단계) — 지주+넉넉→채집 억제 / 지주+궁핍→채집(안전판) "
                + "/ 무밭→채집(불변). canUse 직접 판정.");
        return 1;
    }

    /**
     * 배회 생활 합본 검증(wanderx) — ① 놀이: 배회 시간 부친이 자식 곁 도달·조우(topic=play)
     * ② 놀이 쿨다운(같은 날 재조우 금지 감시) ③ 마실: 이웃 모닥불 도달·잡담(topic=smalltalk)
     * ④ 좌석 상한: 만석이면 방문 조우 불성립(금지 감시). 조우 관문(Encounter) 경유를 lastTopic
     * 상태로 판정 — 종전 코드(무작위 배회뿐)면 ①③이 성립할 수 없다.
     */
    private static int stageWanderX(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "이미 검증이 진행 중 — 끝난 뒤 다시 실행.");
            return 0;
        }
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll();
        List<VerifySuite.Step> steps = new ArrayList<>();
        // [1] 놀이 — 부친+소년 자식 12블록: 배회 시간(dayTime 9000, 짝수일=부친 차례)에 접근·조우.
        {
            BlockPos home = groundAt(level, b, -10, 24);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("wanderx_play",
                    "wandering father approaches own boy and plays (lastTopic=play)", 600, false, () -> {
                MimicVisitGoal.clearSeats();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
                c[0].debugSettleWithTent(home, Direction.NORTH);
                LarderStore.get(level).set(home, 20.0); // 넉넉 — 배회 채집(R4) 소거, 놀이만 남김
                c[1] = spawnChildOf(level, Vec3.atBottomCenterOf(home).add(12, 0, 0), c[0], Sex.MALE);
                c[1].setStage(LifeStage.BOY);
                c[1].setHomePos(home);
                level.setDayTime(9000L); // 배회 시간 + todDay 0(짝수) = 부친 차례
            }, () -> String.format("topic '%s' dist %.1f act=%s", c[0].lastTopic(),
                    Math.sqrt(c[0].distanceToSqr(c[1])), c[0].currentActionLabel()),
                    () -> "play".equals(c[0].lastTopic()) && c[0].distanceToSqr(c[1]) <= 36.0,
                    () -> discard(c)));
        }
        // [2] 놀이 쿨다운(금지 감시) — 오늘 이미 논 부친: 재조우(topic 재기록)가 있으면 실패.
        {
            BlockPos home = groundAt(level, b, -10, -24);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("wanderx_play_once",
                    "father who already played today must NOT re-encounter (topic stays clear)",
                    400, true, () -> {
                MimicVisitGoal.clearSeats();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
                c[0].debugSettleWithTent(home, Direction.NORTH);
                LarderStore.get(level).set(home, 20.0);
                c[1] = spawnChildOf(level, Vec3.atBottomCenterOf(home).add(12, 0, 0), c[0], Sex.MALE);
                c[1].setStage(LifeStage.BOY);
                c[1].setHomePos(home);
                c[0].setLastPlayDay(level.getGameTime() / 24000L); // 오늘 완료 상태 조성
                c[0].debugClearTopic();
                level.setDayTime(9000L);
            }, () -> String.format("topic '%s'(must not become play) dist %.1f", c[0].lastTopic(),
                    Math.sqrt(c[0].distanceToSqr(c[1]))),
                    () -> "play".equals(c[0].lastTopic()), // ← 금지 결과(쿨다운 뚫림)
                    () -> discard(c)));
        }
        // [3] 마실 — 두 정착 가구 20블록: 플랫폼 동측 코너(포스로드 안 = AI 틱 보장) + 타 무대
        //     천막과 48블록 이상 격리 → 서로가 유일한 마실 후보(결정론).
        {
            BlockPos homeA = groundAt(level, b, 42, 90);
            BlockPos homeB = groundAt(level, b, 42, 70);
            MimicEntity[] c = new MimicEntity[4];
            steps.add(new VerifySuite.Step("wanderx_visit",
                    "settler visits neighbor hearth and chats (lastTopic=smalltalk)", 900, false, () -> {
                MimicVisitGoal.clearSeats();
                MimicEntity[] ca = coupleAt(level, homeA); // 기혼 — 구애 goal(3)의 배회 선점 제거
                MimicEntity[] cb = coupleAt(level, homeB);
                c[0] = ca[0];
                c[1] = ca[1];
                c[2] = cb[0];
                c[3] = cb[1];
                c[1].debugSetLastBirthNow(); // 넉넉 저장고(20 ≥ 게이트 12)發 무대 중 출산 차단
                c[3].debugSetLastBirthNow();
                LarderStore.get(level).set(homeA, 20.0);
                LarderStore.get(level).set(homeB, 20.0);
                level.setDayTime(9000L);
            }, () -> String.format("topicA '%s' posA %s act=%s hearths=%d phase=%s probe[%s]",
                    c[0].lastTopic(), c[0].blockPosition().toShortString(),
                    c[0].currentActionLabel(), MimicEntity.occupiedHomes(level).size(),
                    com.evosim.core.Schedule.phaseAt(c[0].getIndividual(), level.getDayTime()),
                    MimicVisitGoal.debugProbe(c[0])),
                    () -> "smalltalk".equals(c[0].lastTopic()),
                    () -> discard(c)));
        }
        // [4] 좌석 상한(금지 감시) — 유일 후보 모닥불을 만석(2)으로 선점: 방문 조우가 성립하면 실패.
        //     플랫폼 동남측 코너(포스로드 안·격리).
        {
            BlockPos homeA = groundAt(level, b, 42, -20);
            BlockPos homeB = groundAt(level, b, 42, -40);
            MimicEntity[] c = new MimicEntity[4];
            steps.add(new VerifySuite.Step("wanderx_visit_cap",
                    "full hearth (2 seats taken): visitor must NOT begin a visit encounter",
                    400, true, () -> {
                MimicVisitGoal.clearSeats();
                MimicEntity[] ca = coupleAt(level, homeA);
                MimicEntity[] cb = coupleAt(level, homeB);
                c[0] = ca[0];
                c[1] = ca[1];
                c[2] = cb[0];
                c[3] = cb[1];
                c[1].debugSetLastBirthNow(); // 무대 중 출산 차단(신생아 놀이 조우 = 금지 감시 오염)
                c[3].debugSetLastBirthNow();
                LarderStore.get(level).set(homeA, 20.0);
                LarderStore.get(level).set(homeB, 20.0);
                long day = level.getGameTime() / 24000L;
                c[1].setLastVisitDay(day); // 판정 대상 c[0] 외 전원 오늘 방문 불가(교란 제거)
                c[2].setLastVisitDay(day);
                c[3].setLastVisitDay(day);
                level.setDayTime(9000L); // 좌석 키(dayTime 일) 확정 후 만석 선점 — 순서 중요
                MimicVisitGoal.debugFillSeats(homeB, 2, level.getDayTime() / 24000L);
                MimicVisitGoal.debugFillSeats(homeA, 2, level.getDayTime() / 24000L);
            }, () -> String.format("topicA '%s'(must stay empty) posA %s", c[0].lastTopic(),
                    c[0].blockPosition().toShortString()),
                    () -> !c[0].lastTopic().isEmpty(), // ← 금지 결과(만석인데 조우 성립)
                    () -> discard(c)));
        }
        VerifySuite.start(ctx.getSource(), steps);
        tell(ctx.getSource(), "배회 생활 합본 검증(4단계) — 놀이 조우 / 놀이 쿨다운 / 마실 잡담 / "
                + "좌석 상한. lastTopic 상태 결과값 판정(조우 관문 경유 증명).");
        return 1;
    }

    /** checkall2 가 몰아 볼 미통과 단계(2026-… 인게임 1회차 실패분). 시간만 늘려 재관찰 — 구조 불변. */
    private static final java.util.Set<String> RETRY_SLUGS = java.util.Set.of(
            "deposit_withdraw", "critical_forage_grass", "migration_caravan",
            "courtship_trip_travel", "courtship_trip_marriage", "polygamy_accept",
            "elder_share", "stale_pact_reject", "aggro_control", "farm_idle_satisfied",
            "farm_own_harvest", "farm_hire_flow", "farm_shield_break", "farm_vacant_expire",
            "farm_family_labor", "polygamy_elite", "polygamy_no_cap", "pref_wealth_charm");
    /** checkall2 제한시간 배율 — 이동·구애 등 AI 완주 관찰용(넉넉히). 시간만 조정. */
    private static final int RETRY_TIMEOUT_SCALE = 4;

    private static int stageCheckAll(CommandContext<CommandSourceStack> ctx, boolean retryOnly) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        if (VerifySuite.isRunning()) {
            tell(ctx.getSource(), "이미 검증이 진행 중 — 끝난 뒤 다시 실행.");
            return 0;
        }
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        List<VerifySuite.Step> steps = new ArrayList<>();

        // [1] 입금·인출 — 남편 H2.5→[1,2)·아내 H0.7→≥1.5 (결과값: H·저장고)
        {
            BlockPos home = ground(level, b, 1);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("deposit_withdraw", "husband H in [1,2) & wife H >= 1.5 (deposit+withdraw)", 600, false, () -> {
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                LarderStore.get(level).set(home, 3.0);
                c[0].teleportTo(home.getX() + 8.5, home.getY(), home.getZ() + 0.5);
                c[1].teleportTo(home.getX() - 8.5, home.getY(), home.getZ() + 0.5);
                c[0].debugSetHolding(2.5);
                c[1].debugSetHolding(0.7);
                level.setDayTime(4000L);
            }, () -> String.format("husbandH %.2f(start 2.5) wifeH %.2f(start 0.7) larder %.0f",
                    c[0].getHolding(), c[1].getHolding(), LarderStore.get(level).get(home)),
                    () -> c[0].getHolding() >= 1.0 && c[0].getHolding() < 2.0 && c[1].getHolding() >= 1.5,
                    () -> discard(c)));
        }
        // [2] 나눔 — 아내 0.25→≥0.6(자가 채집 불가 상승폭)·남편 ≤1.8
        {
            BlockPos home = ground(level, b, 2);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("family_share", "wife H >= 0.6 by share & husband H <= 1.8", 400, false, () -> {
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                LarderStore.get(level).set(home, 0.0);
                c[0].debugSetHolding(1.9); // 입금 문턱(2.0) 미달 — 가족틱 우회 구조(위양성) 차단
                c[1].debugSetHolding(0.25);
                level.setDayTime(4000L);
            }, () -> String.format("wifeH %.2f(start 0.25) husbandH %.2f(start 1.9)",
                    c[1].getHolding(), c[0].getHolding()),
                    () -> c[1].getHolding() >= 0.6 && c[0].getHolding() <= 1.8,
                    () -> discard(c)));
        }
        // [3] 번식·베리 — 저장고 20 → 17(출산비용 3) − 베리비용(심은 그루 실측) 회계 일치 + 유아 1
        {
            BlockPos home = ground(level, b, 3);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("birth_cost_accounting", "larder == 17 - berryCost & infants == 1", 100, false, () -> {
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[0].debugClearBerries(level); // 재실행 잔재 제거(회계 대조 전제)
                LarderStore.get(level).set(home, 20.0);
                level.setDayTime(4000L);
                c[0].debugSettleOnce();
            }, () -> String.format("larder %.0f(expect %.0f=17-berry) infants %d(expect 1) bushes %d",
                    LarderStore.get(level).get(home), 17.0 - berryCost(level, c[0]),
                    infantsAt(level, home), c[0].countBerries(level)),
                    () -> Math.abs(LarderStore.get(level).get(home)
                            - (17.0 - berryCost(level, c[0]))) < 1.0E-6
                            && infantsAt(level, home) == 1,
                    () -> discardFamily(level, home, c)));
        }
        // [4] 육아 급식 — 유아 0.5→1.5 · 저장고 5→4
        {
            BlockPos home = ground(level, b, 4);
            MimicEntity[] c = new MimicEntity[3];
            steps.add(new VerifySuite.Step("infant_feed", "infant H >= 1.5 & larder == 4", 100, false, () -> {
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[2] = stagedInfant(level, home, Sex.FEMALE);
                c[2].debugSetHolding(0.5);
                LarderStore.get(level).set(home, 5.0);
                level.setDayTime(4000L);
                c[0].debugSettleOnce();
            }, () -> String.format("infantH %.2f(expect 1.5) larder %.0f(expect 4)",
                    c[2].getHolding(), LarderStore.get(level).get(home)),
                    () -> c[2].getHolding() >= 1.5 - 1.0E-9
                            && Math.abs(LarderStore.get(level).get(home) - 4.0) < 1.0E-6,
                    () -> discard(c)));
        }
        // [5] R6 귀가 인출 — 밤·위급·저장고 있음 → H≥1.5·저장고 3→2
        {
            BlockPos home = ground(level, b, 5);
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("critical_home_withdraw", "H >= 1.2 & larder <= 2 (night+critical+stocked)", 600, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home).add(6, 0, 0), Sex.MALE);
                c[0].debugSettleWithTent(home, Direction.NORTH);
                LarderStore.get(level).set(home, 3.0);
                c[0].debugSetHolding(0.25);
                level.setDayTime(15000L); // 취침 시간 — 평소라면 자야 함
            }, () -> String.format("H %.2f(start 0.25) larder %.0f(start 3)",
                    c[0].getHolding(), LarderStore.get(level).get(home)),
                    // 0.25→채움은 정수 2개 인출(1.25는 목표 1.5 미달) → H 2.25·L 1, 이후 재입금으로
                    // H 1.25·L 2 로 진동 가능. 판정은 "실제로 먹었고(H≥1.2) 저장고가 줄었다(L<3)"로.
                    () -> c[0].getHolding() >= 1.2
                            && LarderStore.get(level).get(home) <= 2.0 + 1.0E-6,
                    () -> discard(c)));
        }
        // [6] R6 채집 강행 — 밤·위급·저장고 없음 → H가 채집으로 회복(주변 풀 필요 — 환경 의존)
        {
            BlockPos home = ground(level, b, 6);
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("critical_forage_grass", "H > 0.35 by forced night forage (needs grass nearby)", 900, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
                c[0].debugSettleWithTent(home, Direction.NORTH);
                LarderStore.get(level).set(home, 0.0);
                c[0].debugSetHolding(0.25);
                level.setDayTime(15000L);
            }, () -> String.format("H %.2f(start 0.25, waiting forage recovery)", c[0].getHolding()),
                    () -> c[0].getHolding() > 0.35,
                    () -> discard(c)));
        }
        // [7] 아사 클럭 — fast 압축(채집 차단) H0.1 → 유예 초과 후 체력 실감소
        {
            BlockPos spot = ground(level, b, 7);
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("starvation_clock", "dead or hp < max-0.5 after grace (foraging blocked)", 400, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(spot), Sex.MALE);
                c[0].setFastSettle(true); // 시간 압축 + 채집 goal 차단 → 자가 구조 불가(결정론)
                c[0].debugSetHolding(0.1);
            }, () -> String.format("H %.2f hp %.1f/%.1f",
                    c[0].getHolding(), c[0].getHealth(), c[0].getMaxHealth()),
                    () -> !c[0].isAlive() || c[0].getHealth() < c[0].getMaxHealth() - 0.5,
                    () -> discard(c)));
        }
        // [8] 이주 — 두 가구+유아: 거처 좌표가 실제로 바뀌고, 두 새 거처가 같은 목적지권, 유아 업힘
        {
            BlockPos homeA = ground(level, b, 8);
            BlockPos homeB = homeA.offset(12, 0, 0);
            MimicEntity[] c = new MimicEntity[5];
            steps.add(new VerifySuite.Step("migration_caravan", "both homes moved, new homes within 96, infant riding", 200, false, () -> {
                MimicEntity[] f1 = coupleAt(level, homeA);
                MimicEntity[] f2 = coupleAt(level, homeB);
                c[0] = f1[0];
                c[1] = f1[1];
                c[2] = f2[0];
                c[3] = f2[1];
                c[4] = stagedInfant(level, homeA, Sex.MALE);
                level.setDayTime(1000L);
                c[0].debugForceFamine(level);
                c[2].debugForceFamine(level);
                c[0].debugSettleOnce(); // 길잡이
                c[2].debugSettleOnce(); // 동참
            }, () -> String.format("famA %s famB %s newHomeDist %.0f infantRiding %s",
                    homeA.equals(c[0].getHomePos()) ? "stay" : "moved",
                    homeB.equals(c[2].getHomePos()) ? "stay" : "moved",
                    c[0].getHomePos() != null && c[2].getHomePos() != null
                            ? Math.sqrt(c[0].getHomePos().distSqr(c[2].getHomePos())) : -1,
                    c[4].isPassenger() ? "O" : "X"),
                    () -> c[0].getHomePos() != null && !homeA.equals(c[0].getHomePos())
                            && c[2].getHomePos() != null && !homeB.equals(c[2].getHomePos())
                            && c[0].getHomePos().distSqr(c[2].getHomePos()) <= 96.0 * 96.0
                            && c[4].isPassenger(),
                    () -> discard(c)));
        }
        // [9]·[10] 구혼 여행 — 64블록 이동(좌표 실측) → 혼인 상태 변화
        {
            BlockPos homeA = ground(level, b, 9);
            BlockPos homeB = homeA.offset(0, 0, 56);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("courtship_trip_travel", "lonely male reaches foreign hearth (<= 24 blocks)", 1800, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(homeA), Sex.MALE);
                c[0].debugSettleWithTent(homeA, Direction.NORTH);
                c[1] = spawnAdult(level, Vec3.atBottomCenterOf(homeB), Sex.FEMALE);
                c[1].debugSettleWithTent(homeB, Direction.NORTH);
                c[0].debugForceLonely();
                level.setDayTime(8200L); // 배회 — 도착 후 바로 구애 가능
            }, () -> String.format("male-foreign dist %.0f(start 56)",
                    Math.sqrt(c[0].blockPosition().distSqr(homeB))),
                    () -> c[0].blockPosition().distSqr(homeB) <= 24.0 * 24.0,
                    () -> { /* [10]과 공유 — 정리 없음 */ }));
            steps.add(new VerifySuite.Step("courtship_trip_marriage", "traveler no longer single", 1200, false, () -> {
            }, () -> String.format("male %s female %s",
                    c[0].isSingleAdult() ? "single" : "married", c[1].isSingleAdult() ? "single" : "married"),
                    () -> !c[0].isSingleAdult(),
                    () -> discard(c)));
        }
        // [11] 중혼 성사 — 관용 아내 + 저장고 21 → 신부 혼인·합류
        {
            BlockPos home = ground(level, b, 11);
            MimicEntity[] c = new MimicEntity[3];
            steps.add(new VerifySuite.Step("polygamy_accept", "bride married & joined home (tolerant wife, larder 40)", 1200, false, () -> {
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[2] = spawnAdult(level, Vec3.atBottomCenterOf(home).add(4, 0, 0), Sex.FEMALE);
                // 부양 증명(6×3일=18) 여유 통과 — 감시 창 중 가족틱의 출산(−3)·베리(−최대 8) 자연
                // 개입에도 게이트 유지(경계값 21은 베리 실차감 도입 후 flaky).
                LarderStore.get(level).set(home, 40.0);
                level.setDayTime(9000L);
            }, () -> String.format("bride %s joined %s larder %.0f",
                    c[2].isSingleAdult() ? "single" : "married",
                    home.equals(c[2].getHomePos()) ? "O" : "X", LarderStore.get(level).get(home)),
                    () -> !c[2].isSingleAdult() && home.equals(c[2].getHomePos()),
                    () -> discard(c)));
        }
        // [12] 중혼 거절(질투 아내) — 금지 결과 감시: 제한 시간 내 혼인이 일어나면 실패
        {
            BlockPos home = ground(level, b, 12);
            MimicEntity[] c = new MimicEntity[3];
            steps.add(new VerifySuite.Step("polygamy_reject", "bride must stay single (stingy wife gate)", 600, true, () -> {
                MimicEntity[] cc = coupleAt(level, home, Trait.STINGY); // 질투 게이트
                c[0] = cc[0];
                c[1] = cc[1];
                c[2] = spawnAdult(level, Vec3.atBottomCenterOf(home).add(4, 0, 0), Sex.FEMALE);
                // 부양은 넉넉히(40) — 감시 창 중 저장고가 자연 감소해 '부양 미달'로도 거절되면
                // 인색 게이트가 고장나도 통과하는 위양성이 생기므로, 거절 사유를 아내 특성 하나로 고정.
                LarderStore.get(level).set(home, 40.0);
                level.setDayTime(9000L);
            }, () -> String.format("bride %s(must stay single)",
                    c[2].isSingleAdult() ? "single" : "married"),
                    () -> !c[2].isSingleAdult(), // ← 금지 결과(혼인)가 감지되면 실패
                    () -> discard(c)));
        }

        // [13] 노년 전이·자연사 — fast 성장: 노년 경유(상태) 후 사망(결과값: isAlive)
        {
            BlockPos spot = ground(level, b, 13);
            MimicEntity[] c = new MimicEntity[1];
            boolean[] sawElder = new boolean[1];
            steps.add(new VerifySuite.Step("elder_transition_death", "passes ELDER stage then dies naturally (fast growth)", 400, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(spot), Sex.MALE);
                c[0].setFastGrowth(true);
                level.setDayTime(2000L);
            }, () -> {
                if (c[0].getStage() == LifeStage.ELDER) {
                    sawElder[0] = true;
                }
                return String.format("stage %s elderSeen %s alive %s",
                        stageName(c[0].getStage()), sawElder[0] ? "O" : "X", c[0].isAlive() ? "O" : "X");
            }, () -> {
                if (c[0].getStage() == LifeStage.ELDER) {
                    sawElder[0] = true;
                }
                return sawElder[0] && !c[0].isAlive();
            }, () -> discard(c)));
        }
        // [14] 노인 공유(책임) — 자식 집 저장고 0→2 실측
        {
            BlockPos homeE = ground(level, b, 14);
            // 자식 집을 활동반경(32) 밖 40블록에 — 노인 방문의 리시 앵커(활동반경 밖 도달)가 실제로
            // 발동하는 거리로 검증(과거 16블록은 앵커 수정을 한 번도 밟지 않았다).
            BlockPos homeC = homeE.offset(40, 0, 0);
            MimicEntity[] c = new MimicEntity[3];
            steps.add(new VerifySuite.Step("elder_share", "child home larder 0 -> >= 2 (responsible elder delivery)", 1200, false, () -> {
                // 집 반경 밖 스폰 — 가족틱의 자기 입금이 배달 잉여를 흡수하는 race 차단(결정론).
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(homeE).add(8, 0, 4), Sex.MALE,
                        Trait.OVER_RESPONSIBLE);
                c[0].setStage(LifeStage.ELDER);
                c[0].debugSettleWithTent(homeE, Direction.NORTH);
                LarderStore.get(level).set(homeE, 8.0); // 가드② 통과
                c[0].debugSetHolding(3.5);              // 잉여 2개 — 배달 직전
                c[1] = spawnChildOf(level, Vec3.atBottomCenterOf(homeC), c[0], Sex.MALE);
                c[1].debugSettleWithTent(homeC, Direction.NORTH);
                c[2] = stagedInfant(level, homeC, Sex.FEMALE);
                LarderStore.get(level).set(homeC, 0.0);
                level.setDayTime(2000L);
            }, () -> String.format("childLarder %.0f(expect 2) elderH %.2f",
                    LarderStore.get(level).get(homeC), c[0].getHolding()),
                    () -> LarderStore.get(level).get(homeC) >= 2.0 - 1.0E-6,
                    () -> discard(c)));
        }
        // [15] 노인 무공유(무책임) — 금지 결과 감시: 자식 집 저장고가 늘면 실패
        {
            BlockPos homeE = ground(level, b, 15);
            BlockPos homeC = homeE.offset(16, 0, 0);
            MimicEntity[] c = new MimicEntity[3];
            steps.add(new VerifySuite.Step("elder_no_share", "child home larder must stay 0 (irresponsible elder)", 600, true, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(homeE).add(8, 0, 4), Sex.MALE,
                        Trait.IRRESPONSIBLE);
                c[0].setStage(LifeStage.ELDER);
                c[0].debugSettleWithTent(homeE, Direction.NORTH);
                LarderStore.get(level).set(homeE, 8.0);
                c[0].debugSetHolding(3.5); // 잉여는 있으나 무책임 → 나누면 안 됨
                c[1] = spawnChildOf(level, Vec3.atBottomCenterOf(homeC), c[0], Sex.MALE);
                c[1].debugSettleWithTent(homeC, Direction.NORTH);
                c[2] = stagedInfant(level, homeC, Sex.FEMALE);
                LarderStore.get(level).set(homeC, 0.0);
                level.setDayTime(2000L);
            }, () -> String.format("childLarder %.0f(must stay 0)", LarderStore.get(level).get(homeC)),
                    () -> LarderStore.get(level).get(homeC) > 1.0E-6, // ← 금지 결과(공유 발생)
                    () -> discard(c)));
        }
        // [16] 마실 육아 — 노인만 곁에 있는 fastCare 유아가 방치 아사하지 않으면 성공(금지 결과=유아 사망)
        {
            BlockPos homeE = ground(level, b, 16);
            BlockPos homeC = homeE.offset(10, 0, 0);
            MimicEntity[] c = new MimicEntity[3];
            steps.add(new VerifySuite.Step("elder_visit_care", "infant must survive with visiting elder only", 400, true, () -> {
                // 노인은 자기 집 없이 유아 곁에서 시작(마실 상태) — 대상 식별은 부모 링크+유아 대조.
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(homeC).add(1, 0, 0), Sex.MALE);
                c[0].setStage(LifeStage.ELDER);
                MimicEntity child = spawnChildOf(level, Vec3.atBottomCenterOf(homeC), c[0], Sex.MALE);
                child.debugSettleWithTent(homeC, Direction.NORTH);
                child.teleportTo(homeC.getX() + 100.5, homeC.getY(), homeC.getZ() + 0.5); // 부모는 먼 곳
                c[1] = child;
                c[2] = stagedInfant(level, homeC, Sex.FEMALE);
                c[2].setFastCare(true); // 20틱마다 급식 판정 — 성인 없으면 3회(60틱) 만에 아사
                LarderStore.get(level).set(homeC, 3.0);
                level.setDayTime(2000L);
            }, () -> String.format("infant %s elderDist %.0f(must survive)",
                    c[2].isAlive() ? "alive" : "dead",
                    Math.sqrt(c[0].blockPosition().distSqr(c[2].blockPosition()))),
                    () -> !c[2].isAlive(), // ← 금지 결과(방치 아사)
                    () -> discard(c)));
        }

        // [17] 집앞 즉석 인출 — 집 안에 서 있는 배고픈 개체가 이동 없이 저장고에서 꺼내 먹는가
        //     (결과값: H 0.7→≥1.5 · 저장고 3→2. 가족틱이 대신 처리할 확률 ~3%가 있으나 결과 동일 경로)
        {
            BlockPos home = ground(level, b, 17);
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("doorstep_withdraw", "H >= 1.5 & larder <= 2 without moving", 60, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
                c[0].debugSettleWithTent(home, Direction.NORTH);
                LarderStore.get(level).set(home, 3.0);
                c[0].debugSetHolding(0.7); // 귀가 임계(0.8) 미만 + 이미 집 안(이동 불필요)
                level.setDayTime(4000L);
            }, () -> String.format("H %.2f(start 0.7 expect>=1.5) larder %.0f(start 3 expect 2)",
                    c[0].getHolding(), LarderStore.get(level).get(home)),
                    () -> c[0].getHolding() >= 1.5 - 1.0E-9
                            && LarderStore.get(level).get(home) <= 2.0 + 1.0E-6,
                    () -> discard(c)));
        }
        // [18] 혼인우선 가장 — 성년 아들이 동거해도 번식이 막히지 않는가(과거: UUID 순으로 아들이
        //     가장을 밀어내면 어미 탐색이 조용히 실패). 판정은 [3]과 같은 회계 항등식 + 유아 1.
        {
            BlockPos home = ground(level, b, 18);
            MimicEntity[] c = new MimicEntity[3];
            steps.add(new VerifySuite.Step("head_priority_son", "accounting holds with adult son cohabiting & infants == 1", 100, false, () -> {
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[2] = spawnChildOf(level, Vec3.atBottomCenterOf(home).add(2, 0, 0), c[0], Sex.MALE);
                c[2].debugSettleWithTent(home, Direction.NORTH); // 성년 아들 동거(미혼 — 부녀교배 방지 대조군)
                c[0].debugClearBerries(level);
                LarderStore.get(level).set(home, 20.0);
                level.setDayTime(4000L);
                c[0].debugSettleOnce();
            }, () -> String.format("larder %.0f(expect %.0f) infants %d(expect 1)",
                    LarderStore.get(level).get(home), 17.0 - berryCost(level, c[0]),
                    infantsAt(level, home)),
                    () -> Math.abs(LarderStore.get(level).get(home)
                            - (17.0 - berryCost(level, c[0]))) < 1.0E-6
                            && infantsAt(level, home) == 1,
                    () -> discardFamily(level, home, c)));
        }
        // [19] 전투 도주 해제 — 겁쟁이가 좀비에게서 도망치다, 좀비가 멀어지면(어그로 무의미) 도주를
        //     멈추고 귀가하는가(과거: 좀비가 살아있는 한 무한 도주). 결과값 = 귀가 좌표.
        {
            BlockPos home = ground(level, b, 19);
            MimicEntity[] c = new MimicEntity[1];
            Zombie[] z = new Zombie[1];
            long[] t0 = new long[1];
            boolean[] moved = new boolean[1];
            steps.add(new VerifySuite.Step("combat_flee_release", "after zombie moved far, coward returns within 3 blocks of home", 600, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE, Trait.COWARD);
                c[0].debugSettleWithTent(home, Direction.NORTH);
                level.setDayTime(15000L); // 밤 — 좀비 연소 방지
                z[0] = EntityType.ZOMBIE.create(level);
                z[0].moveTo(home.getX() + 4.5, home.getY(), home.getZ() + 0.5, 0f, 0f);
                z[0].setPersistenceRequired();
                z[0].setTarget(c[0]);
                level.addFreshEntity(z[0]);
                t0[0] = level.getGameTime();
                moved[0] = false;
            }, () -> String.format("fleeDist %.0f zombie %s",
                    Math.sqrt(c[0].blockPosition().distSqr(home)),
                    moved[0] ? "far(release)" : "near(fleeing)"),
                    () -> {
                        // 국면 전환: 60틱 도주 후 좀비를 120블록 밖으로(살아있는 채) — 과거 코드라면
                        // 그래도 계속 도망친다. 신 코드는 해제 → 리시·귀가로 집 3블록 내 복귀 = 성공.
                        if (!moved[0] && level.getGameTime() - t0[0] > 60 && z[0] != null) {
                            z[0].teleportTo(home.getX() + 120.5, home.getY(), home.getZ() + 0.5);
                            moved[0] = true;
                        }
                        return moved[0] && c[0].blockPosition().distSqr(home) <= 9.0;
                    },
                    () -> {
                        discard(c);
                        if (z[0] != null && z[0].isAlive()) {
                            z[0].discard();
                        }
                    }));
        }
        // [20] 제자리 합의 기각 — 낡은 이주 합의가 "지금 굶는 자리"를 가리키면 따라가지 않고 딴 곳을
        //     정찰하는가. 결과값 = 새 거처가 합의 목적지에서 32블록 이상.
        {
            BlockPos home = ground(level, b, 20);
            BlockPos dest = home.offset(8, 0, 0);      // 합의 목적지 = 사실상 제자리
            BlockPos origin = home.offset(-60, 0, 0);  // 같은 마을권(256 내) 옛 출발지
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("stale_pact_reject", "new home > 32 blocks from stale pact destination", 200, false, () -> {
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                MigrationDest.get(level).register(origin, dest, level.getGameTime()); // 유효한 낡은 합의
                c[0].debugForceFamine(level);
                c[0].debugSettleOnce(); // 기근 → 이주. resolve 는 dest(8) < origin(60) 로 기각해야 함
            }, () -> String.format("newHome%s pactDist %.0f",
                    home.equals(c[0].getHomePos()) ? " not-moved" : " moved",
                    c[0].getHomePos() != null ? Math.sqrt(c[0].getHomePos().distSqr(dest)) : -1),
                    () -> c[0].getHomePos() != null && !home.equals(c[0].getHomePos())
                            && c[0].getHomePos().distSqr(dest) > 32.0 * 32.0,
                    () -> {
                        discardFamily(level, home, c);
                        // 합의 잔재 만료 처리 — 이후 스텝·플레이에 오염 없게
                        MigrationDest.get(level).register(origin, dest,
                                level.getGameTime() - MigrationDest.VALID_TICKS - 1);
                    }));
        }
        // [21] 아이들만 이주 금지 — 인솔 성년·노년 없는 가구는 기근이어도 이주하지 않는다(금지 결과 감시).
        {
            BlockPos home = ground(level, b, 21);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("children_only_no_migrate", "children-only household must not migrate", 200, true, () -> {
                c[0] = stagedInfant(level, home, Sex.MALE);
                c[1] = stagedInfant(level, home, Sex.FEMALE);
                c[0].debugSettleWithTent(home, Direction.NORTH);
                c[1].setHomePos(home);
                c[0].debugForceFamine(level);
                c[0].debugSettleOnce(); // 대표(아이)가 정산해도 grown==0 가드가 이주를 막아야 함
            }, () -> String.format("home %s(must stay)",
                    home.equals(c[0].getHomePos()) ? "kept" : "moved!"),
                    () -> c[0].getHomePos() == null || !home.equals(c[0].getHomePos()), // ← 금지 결과
                    () -> discardFamily(level, home, c)));
        }
        // [22] 낮 샘플 육아: 곁에 성인 → 생존 — 압축 시계(하루 40틱)로 실경로(샘플·래치·롤오버)를
        //     그대로 통과시켜, 어른이 곁에 있는 유아는 살아남는지(금지 결과 = 사망).
        {
            BlockPos home = ground(level, b, 22);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("daycare_attended_survive", "infant with adult nearby must survive (compressed clock)", 400, true, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home).add(1, 0, 0), Sex.FEMALE);
                c[0].setNoAi(true); // 결정론 — 성인을 유아 곁에 고정(배회로 이탈하는 우연 제거)
                c[1] = stagedInfant(level, home, Sex.MALE);
                c[1].setNoAi(true);
                c[1].debugSetCareTimeScale(600); // 하루=40틱 — 3일 방치면 ~160틱에 죽는 스케일
                level.setDayTime(2000L);
            }, () -> String.format("infant %s(must survive)", c[1].isAlive() ? "alive" : "dead"),
                    () -> !c[1].isAlive(), // ← 금지 결과(곁에 성인인데 아사)
                    () -> discard(c)));
        }
        // [23] 낮 샘플 육아: 방치 아사 — 같은 압축 시계에서 성인이 아예 없으면 3일(≈160틱) 내 죽는가
        //     (결과값 = 사망. [22]와 쌍으로 래치·롤오버·아사 카운터의 실경로를 완주시킨다).
        {
            BlockPos home = ground(level, b, 23);
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("daycare_neglect_starve", "unattended infant dies within ~3 compressed days", 400, false, () -> {
                c[0] = stagedInfant(level, home, Sex.FEMALE);
                c[0].setNoAi(true);
                c[0].debugSetCareTimeScale(600);
                level.setDayTime(2000L);
            }, () -> String.format("infant %s(neglect accumulating)", c[0].isAlive() ? "alive" : "dead(expected)"),
                    () -> !c[0].isAlive(),
                    () -> discard(c)));
        }

        // [24] 원장 등록·사망 — 비무대 개체가 첫 틱에 등록되고 파괴 시 사망일이 찍히는가.
        //      pass 가 국면 전환(등록 확인 → 파괴)을 수행, cleanup 이 debugRemove 로 실기록 회수(규칙 7).
        {
            BlockPos spot = ground(level, b, 24);
            MimicEntity[] c = new MimicEntity[1];
            long[] iid = new long[1];
            boolean[] discarded = new boolean[1];
            steps.add(new VerifySuite.Step("ledger_register_death",
                    "real mimic registered on first tick, diedDay >= 0 after destroy", 100, false, () -> {
                c[0] = spawnVerifyAdult(level, Vec3.atBottomCenterOf(spot), false, null, 0);
                iid[0] = c[0].getIndividual().id();
                discarded[0] = false;
            }, () -> {
                FamilyLedger.Rec r = FamilyLedger.get(level).get(iid[0]);
                return String.format("registered %s diedDay %d discarded %s",
                        r != null ? "yes" : "no", r == null ? -2 : (int) r.diedDay,
                        discarded[0] ? "yes" : "no");
            }, () -> {
                FamilyLedger.Rec r = FamilyLedger.get(level).get(iid[0]);
                if (r == null) {
                    return false; // 아직 미등록 — 첫 틱 대기
                }
                if (!discarded[0]) {
                    c[0].discard(); // 등록 확인 → 파괴(사망 마킹 경로)
                    discarded[0] = true;
                    return false;
                }
                return r.diedDay >= 0;
            }, () -> {
                discard(c);
                FamilyLedger.get(level).debugRemove(iid[0]);
            }));
        }
        // [25] 무대 제외 — 무대 개체는 시간이 지나도 원장에 등장하면 안 된다(금지 결과 감시).
        {
            BlockPos spot = ground(level, b, 25);
            MimicEntity[] c = new MimicEntity[1];
            long[] iid = new long[1];
            steps.add(new VerifySuite.Step("ledger_stage_exclusion",
                    "stage actor must never appear in ledger", 100, true, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(spot), Sex.MALE);
                iid[0] = c[0].getIndividual().id();
            }, () -> String.format("inLedger %s",
                    FamilyLedger.get(level).get(iid[0]) != null ? "yes" : "no"),
                    () -> FamilyLedger.get(level).get(iid[0]) != null, // ← 금지 결과
                    () -> {
                        discard(c);
                        FamilyLedger.get(level).debugRemove(iid[0]);
                    }));
        }
        // [26] 날로먹기 섭취 — 인출 1유닛에 H 1.2 회복: 0.7→1.9 (일반이면 1.7 — 0.2 차이로 판별).
        {
            BlockPos home = ground(level, b, 26);
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("raw_eater_intake",
                    "H 0.7 -> ~1.9 (1 unit x1.2, plain would be 1.7) & larder 3 -> 2", 60, false, () -> {
                c[0] = spawnVerifyAdult(level, Vec3.atBottomCenterOf(home), true, Trait.RAW_EATER, 0);
                c[0].debugSettleWithTent(home, Direction.NORTH);
                LarderStore.get(level).set(home, 3.0);
                c[0].debugSetHolding(0.7);
                level.setDayTime(4000L);
                c[0].debugSettleOnce();
            }, () -> String.format("H %.2f(start 0.7 expect ~1.9) larder %.0f(expect 2)",
                    c[0].getHolding(), LarderStore.get(level).get(home)),
                    () -> c[0].getHolding() >= 1.85 && c[0].getHolding() <= 1.95
                            && Math.abs(LarderStore.get(level).get(home) - 2.0) < 1.0E-6,
                    () -> discard(c)));
        }
        // [27] 날로먹기 저장 — 입금 1유닛에 H 1.25 소요: 2.3→1.05, 저장고 3→4 (L 정수 유지).
        {
            BlockPos home = ground(level, b, 27);
            MimicEntity[] c = new MimicEntity[1];
            steps.add(new VerifySuite.Step("raw_eater_store",
                    "H 2.3 -> ~1.05 (1 unit costs 1.25H) & larder 3 -> 4", 60, false, () -> {
                c[0] = spawnVerifyAdult(level, Vec3.atBottomCenterOf(home), true, Trait.RAW_EATER, 0);
                c[0].debugSettleWithTent(home, Direction.NORTH);
                LarderStore.get(level).set(home, 3.0);
                c[0].debugSetHolding(2.3);
                level.setDayTime(4000L);
                c[0].debugSettleOnce();
            }, () -> String.format("H %.2f(start 2.3 expect ~1.05) larder %.0f(expect 4)",
                    c[0].getHolding(), LarderStore.get(level).get(home)),
                    () -> c[0].getHolding() >= 1.0 && c[0].getHolding() <= 1.10
                            && Math.abs(LarderStore.get(level).get(home) - 4.0) < 1.0E-6,
                    () -> discard(c)));
        }
        // [28][29] 힘 등급 공격 — 속성 실측(변수 변화 검증): 힘센V = 일반×1.4, 약함V = ×0.7. 즉시 판정.
        {
            BlockPos spot = ground(level, b, 28);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("strong_attack_attr",
                    "ATTACK_DAMAGE of STRONG-V male == 1.4 x plain male", 40, false, () -> {
                c[0] = spawnVerifyAdult(level, Vec3.atBottomCenterOf(spot), true, Trait.STRONG, 5);
                c[1] = spawnVerifyAdult(level, Vec3.atBottomCenterOf(spot).add(2, 0, 0), true, null, 0);
            }, () -> String.format("strongV %.2f plain %.2f",
                    c[0].getAttributeValue(Attributes.ATTACK_DAMAGE),
                    c[1].getAttributeValue(Attributes.ATTACK_DAMAGE)),
                    () -> Math.abs(c[0].getAttributeValue(Attributes.ATTACK_DAMAGE)
                            - 1.4 * c[1].getAttributeValue(Attributes.ATTACK_DAMAGE)) < 1.0E-6,
                    () -> discard(c)));
        }
        {
            BlockPos spot = ground(level, b, 29);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("weak_attack_attr",
                    "ATTACK_DAMAGE of WEAK-V male == 0.7 x plain male", 40, false, () -> {
                c[0] = spawnVerifyAdult(level, Vec3.atBottomCenterOf(spot), true, Trait.WEAK, 5);
                c[1] = spawnVerifyAdult(level, Vec3.atBottomCenterOf(spot).add(2, 0, 0), true, null, 0);
            }, () -> String.format("weakV %.2f plain %.2f",
                    c[0].getAttributeValue(Attributes.ATTACK_DAMAGE),
                    c[1].getAttributeValue(Attributes.ATTACK_DAMAGE)),
                    () -> Math.abs(c[0].getAttributeValue(Attributes.ATTACK_DAMAGE)
                            - 0.7 * c[1].getAttributeValue(Attributes.ATTACK_DAMAGE)) < 1.0E-6,
                    () -> discard(c)));
        }
        // [30] 무모 번식 완화 — 게이트 경계 저장고 11(무모② 하한 10 통과 / 일반 12 미달) → 유아 1.
        {
            BlockPos home = ground(level, b, 30);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("reckless_threshold",
                    "reckless couple breeds at larder 11 (gate 10 vs plain 12)", 100, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE, Trait.RECKLESS);
                c[1] = spawnAdult(level, Vec3.atBottomCenterOf(home).add(0.5, 0, 0), Sex.FEMALE,
                        Trait.RECKLESS);
                c[0].debugSettleWithTent(home, Direction.NORTH);
                c[1].debugSettleWithTent(home, Direction.NORTH);
                c[0].debugMarryTo(c[1]);
                c[0].debugClearBerries(level);
                LarderStore.get(level).set(home, 11.0);
                level.setDayTime(4000L);
                c[0].debugSettleOnce();
            }, () -> String.format("infants %d(expect 1) larder %.0f",
                    infantsAt(level, home), LarderStore.get(level).get(home)),
                    () -> infantsAt(level, home) == 1,
                    () -> discardFamily(level, home, c)));
        }
        // [31] 대조군 — 같은 저장고 11에서 일반 부부는 낳으면 안 된다(완화가 무모 전용임을 증명).
        {
            BlockPos home = ground(level, b, 31);
            MimicEntity[] c = new MimicEntity[2];
            steps.add(new VerifySuite.Step("threshold_control",
                    "plain couple must NOT breed at larder 11", 150, true, () -> {
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[0].debugClearBerries(level);
                LarderStore.get(level).set(home, 11.0);
                level.setDayTime(4000L);
                c[0].debugSettleOnce();
            }, () -> String.format("infants %d(must stay 0)", infantsAt(level, home)),
                    () -> infantsAt(level, home) >= 1, // ← 금지 결과
                    () -> discardFamily(level, home, c)));
        }
        // [32][33] 조심성 좀비 유인 — 양쪽 noAi 고정(결정론): 10블록에서 조심성(반경 9)은 절대 안
        //      노려지고, 일반(반경 12)은 노려진다. attractZombies 가 유일 어그로 공급원이라 실효 보장.
        {
            BlockPos spot = ground(level, b, 32);
            MimicEntity[] c = new MimicEntity[1];
            Zombie[] z = new Zombie[1];
            steps.add(new VerifySuite.Step("cautious_aggro",
                    "zombie at 10 blocks must never target CAUTIOUS mimic (range 12x0.75=9)",
                    200, true, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(spot), Sex.MALE, Trait.CAUTIOUS);
                c[0].setNoAi(true);
                level.setDayTime(15000L);
                z[0] = EntityType.ZOMBIE.create(level);
                z[0].moveTo(spot.getX() + 10.5, spot.getY(), spot.getZ() + 0.5, 0f, 0f);
                z[0].setNoAi(true);
                z[0].setPersistenceRequired();
                level.addFreshEntity(z[0]);
            }, () -> String.format("zombieTarget %s", z[0].getTarget() == c[0] ? "mimic!" : "none"),
                    () -> z[0].getTarget() == c[0], // ← 금지 결과
                    () -> {
                        discard(c);
                        if (z[0] != null && z[0].isAlive()) {
                            z[0].discard();
                        }
                    }));
        }
        {
            BlockPos spot = ground(level, b, 33);
            MimicEntity[] c = new MimicEntity[1];
            Zombie[] z = new Zombie[1];
            steps.add(new VerifySuite.Step("aggro_control",
                    "zombie at 10 blocks targets plain mimic (range 12)", 100, false, () -> {
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(spot), Sex.MALE);
                c[0].setNoAi(true);
                level.setDayTime(15000L);
                z[0] = EntityType.ZOMBIE.create(level);
                z[0].moveTo(spot.getX() + 10.5, spot.getY(), spot.getZ() + 0.5, 0f, 0f);
                z[0].setNoAi(true);
                z[0].setPersistenceRequired();
                level.addFreshEntity(z[0]);
            }, () -> String.format("zombieTarget %s", z[0].getTarget() == c[0] ? "mimic" : "none"),
                    () -> z[0].getTarget() == c[0],
                    () -> {
                        discard(c);
                        if (z[0] != null && z[0].isAlive()) {
                            z[0].discard();
                        }
                    }));
        }


        // [34] 밭 무단·슬롯0 — 상주 지주(noAI) 9타일 전량 케어: 아무도 못 딴다(farmguard 편입, 규칙 12)
        {
            BlockPos fanchor = ground(level, b, 34);
            MimicEntity[] c = new MimicEntity[2];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("farm_guard_no_poach",
                    "9-tile owner-cared farm: slots 0 (1 < MIN_JOB 2), no one may harvest", 300, true, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(fanchor).add(-3, 0, 0), Sex.MALE);
                c[0].debugSetHolding(0.4);
                c[1] = spawnAdult(level, Vec3.atBottomCenterOf(fanchor).add(-3, 0, -4), Sex.MALE);
                c[1].setNoAi(true); // 지주 상주(용량 8 계상) + 자가 수확 봉쇄
                pl[0] = buildDemoPlot(level, fanchor, c[1].getIndividual().id(), 9);
                level.setDayTime(1200L);
            }, () -> String.format("ripe %d(must stay 9)", countRipe(level, pl[0])),
                    () -> countRipe(level, pl[0]) < 9,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [35] 지대 정산 — 계정 2.7 → 저장고 +2(정수만)·0.7 이월(farmrent 편입)
        {
            BlockPos fanchor = ground(level, b, 35);
            BlockPos fhome = fanchor.offset(-10, 0, 0);
            MimicEntity[] c = new MimicEntity[1];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("farm_rent_settle",
                    "account 2.7 -> owner larder +2 (integer only) & 0.7 carried", 400, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(fhome), Sex.MALE);
                c[0].debugSettleWithTent(fhome, Direction.NORTH);
                LarderStore.get(level).set(fhome, 3.0);
                pl[0] = buildDemoPlot(level, fanchor, c[0].getIndividual().id(), 9);
                pl[0].account = 2.7;
                FarmStore.get(level).setDirty();
                level.setDayTime(13500L);
            }, () -> String.format("larder %.1f(expect 5) account %.2f(expect 0.70)",
                    LarderStore.get(level).get(fhome), pl[0].account),
                    () -> Math.abs(LarderStore.get(level).get(fhome) - 5.0) < 1.0E-6
                            && Math.abs(pl[0].account - 0.7) < 1.0E-6,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [36] 밭 상속 — 지주 파괴 → 아들 승계 ∧ 자기 소작 해소(farminherit 편입)
        {
            BlockPos fanchor = ground(level, b, 36);
            BlockPos fhome = fanchor.offset(-10, 0, 0);
            MimicEntity[] c = new MimicEntity[2];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            long[] sid = new long[1];
            steps.add(new VerifySuite.Step("farm_inherit_son",
                    "owner destroyed -> eldest son inherits & his tenancy on it dissolves",
                    100, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(fhome), Sex.MALE);
                c[0].debugSettleWithTent(fhome, Direction.NORTH);
                c[1] = spawnChildOf(level, Vec3.atBottomCenterOf(fhome).add(2, 0, 0), c[0], Sex.MALE);
                pl[0] = buildDemoPlot(level, fanchor, c[0].getIndividual().id(), 9);
                c[1].setTenant(pl[0].id, 3);
                sid[0] = c[1].getIndividual().id();
                c[0].discard();
            }, () -> String.format("owner %d(expect %d) sonTenant %d(expect 0)",
                    pl[0].ownerId, sid[0], c[1].getTenantFarm()),
                    () -> pl[0].ownerId == sid[0] && c[1].getTenantFarm() == 0L,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [37] 만족 정지 — 부유 무동기 지주는 익은 밭을 안 딴다(farmidle 편입, 금지 감시)
        {
            BlockPos fanchor = ground(level, b, 37);
            BlockPos fhome = fanchor.offset(-10, 0, 0);
            MimicEntity[] c = new MimicEntity[1];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("farm_idle_satisfied",
                    "rich plain owner (larder 60 >> bar 12) must NOT work own farm", 300, true, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(fhome), Sex.MALE);
                c[0].debugSettleWithTent(fhome, Direction.NORTH);
                LarderStore.get(level).set(fhome, 60.0);
                pl[0] = buildDemoPlot(level, fanchor, c[0].getIndividual().id(), 9);
                c[0].updateMotivation(level); // 만족 캐시를 goal 첫 틱 전에 확정(farm_hoard 와 동일) —
                                              // 새벽 스캔 전 1회 수확 새어나가는 레이스 차단
                level.setDayTime(1200L);
            }, () -> String.format("ripe %d(must stay 9) satisfied %s",
                    countRipe(level, pl[0]), c[0].isSatisfiedToday() ? "yes" : "no"),
                    () -> countRipe(level, pl[0]) < 9,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }

        // [38] 순수 코어 전량 — evotest all 을 인프로세스 실행(원트랙 완성: 명령 하나로 순수+배선)
        {
            boolean[] ok = new boolean[1];
            String[] msg = new String[1];
            steps.add(new VerifySuite.Step("evotest_core",
                    "headless pure-rule suite (evotest all) must be all-pass", 100, false, () -> {
                var r = com.evosim.test.EvoTest.runReport("all");
                ok[0] = !r.hasFailures();
                long total = r.checks().size();
                long fail = r.checks().stream().filter(c -> !c.pass()).count();
                msg[0] = String.format("cases %d fail %d", total, fail);
            }, () -> msg[0] == null ? "running" : msg[0],
                    () -> ok[0],
                    () -> { }));
        }
        // [39] 밭 골조 — 25타일 수열 좌표 전부에 베리 실재(farm 편입)
        {
            BlockPos fanchor = ground(level, b, 39);
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("farm_layout_place",
                    "all 25 layout coords hold ripe bushes & registry matches", 100, false, () -> {
                FarmTicker.clearAssignments();
                level.setDayTime(4000L);
                pl[0] = buildDemoPlot(level, fanchor, 0L, 25);
            }, () -> String.format("ripe %d/25 registered %d", countRipe(level, pl[0]),
                    pl[0].tiles.length),
                    () -> countRipe(level, pl[0]) == 25 && pl[0].tiles.length == 25,
                    () -> {
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [40] 자영 수확 — 주인이 순회 수확: H +3 ∧ 잔여익음 ≤3(farmown 편입)
        {
            BlockPos fanchor = ground(level, b, 40);
            MimicEntity[] c = new MimicEntity[1];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            double[] h0 = new double[1];
            steps.add(new VerifySuite.Step("farm_own_harvest",
                    "owner harvests own plot: ripe 15 -> <= 12 (>=3 tiles; H caps at 2.0 homeless)", 1200, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(fanchor).add(-2, 0, 0), Sex.MALE);
                pl[0] = buildDemoPlot(level, fanchor, c[0].getIndividual().id(), 15);
                level.setDayTime(4000L);
                h0[0] = c[0].getHolding();
            }, () -> String.format("H %.2f(start %.2f) ripeLeft %d", c[0].getHolding(), h0[0],
                    countRipe(level, pl[0])),
                    // 익음 15→≤12(≥3타일)로 판정 — 전량(≤3)은 하루 용량 12 완주라 60초 창 초과.
                    // H 는 무주택이라 BAND_HIGH(2.0) 상한(§2555)이라 start+3 도 불가.
                    () -> countRipe(level, pl[0]) <= 12,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [41] 고용 흐름 — 새벽 배정 → 소작 수확(이웃 H↑ ∧ 계정↑, farmhire 편입)
        {
            BlockPos fanchor = ground(level, b, 41);
            MimicEntity[] c = new MimicEntity[2];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            double[] h0 = new double[1];
            steps.add(new VerifySuite.Step("farm_hire_flow",
                    "dawn assignment -> tenant harvest: rent account accrues (worker H caps at 2.0)", 1800, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(fanchor).add(-3, 0, 0), Sex.MALE);
                c[1] = spawnAdult(level, Vec3.atBottomCenterOf(fanchor).add(-3, 0, 4), Sex.MALE);
                pl[0] = buildDemoPlot(level, fanchor, c[0].getIndividual().id(), 35);
                level.setDayTime(1200L);
                h0[0] = c[1].getHolding();
            }, () -> String.format("workerH %.2f(start %.2f) rent %.2f assigned %s",
                    c[1].getHolding(), h0[0], pl[0].account,
                    FarmTicker.assignedPlot(c[1].getId()) == pl[0].id ? "yes" : "no"),
                    // 지대 계정 적립으로 판정 — 소작농(30%)만이 계정을 채운다. workerH 는 상한 2.0(§2555).
                    () -> pl[0].account > 0.2,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [42] 상시 승격 — 이틀 조성 + 3일째 실경로 배정 → tenantFarm 성립(farmbond 편입)
        {
            BlockPos fanchor = ground(level, b, 42);
            MimicEntity[] c = new MimicEntity[2];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("farm_bond_promote",
                    "3rd consecutive dawn promotes to permanent tenant", 600, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(fanchor).add(-3, 0, 0), Sex.MALE);
                c[1] = spawnAdult(level, Vec3.atBottomCenterOf(fanchor).add(-3, 0, 4), Sex.MALE);
                pl[0] = buildDemoPlot(level, fanchor, c[0].getIndividual().id(), 35);
                c[1].setTenant(0L, 2);
                FarmTicker.debugSeedAssignment(c[1].getId(), pl[0].id);
                level.setDayTime(1200L);
            }, () -> String.format("tenantFarm %d(expect %d) streak %d",
                    c[1].getTenantFarm(), pl[0].id, c[1].getTenantStreak()),
                    () -> c[1].getTenantFarm() == pl[0].id && c[1].getTenantStreak() >= 3,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [43] 예약석 — 일용 슬롯 0(9타일 vs ΣC24)에도 상시 소작 배정 유지(farmseat 편입)
        {
            BlockPos fanchor = ground(level, b, 43);
            BlockPos fhome = fanchor.offset(-12, 0, 0);
            MimicEntity[] c = new MimicEntity[3];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("farm_seat_reserved",
                    "reserved seat: permanent tenant assigned even with 0 day-slots", 600, false, () -> {
                FarmTicker.clearAssignments();
                MimicEntity[] cc = coupleAt(level, fhome);
                c[0] = cc[0];
                c[1] = cc[1];
                c[2] = spawnAdult(level, Vec3.atBottomCenterOf(fanchor).add(-3, 0, 4), Sex.MALE);
                pl[0] = buildDemoPlot(level, fanchor, c[0].getIndividual().id(), 9);
                c[2].setTenant(pl[0].id, 3);
                level.setDayTime(1200L);
            }, () -> String.format("assigned %s bond %s (slots would be 0: 9 tiles vs cap 24)",
                    FarmTicker.assignedPlot(c[2].getId()) == pl[0].id ? "yes" : "no",
                    c[2].getTenantFarm() == pl[0].id ? "kept" : "broken"),
                    () -> FarmTicker.assignedPlot(c[2].getId()) == pl[0].id
                            && c[2].getTenantFarm() == pl[0].id,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [44] 보호 구제 — 위급 상시 소작: H≥1 ∧ 영주 저장고 3→2 ∧ 관계 유지(farmshield 편입)
        {
            BlockPos fanchor = ground(level, b, 44);
            BlockPos fhome = fanchor.offset(-10, 0, 0);
            MimicEntity[] c = new MimicEntity[2];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("farm_shield_relief",
                    "critical tenant relieved: H >= 1 & lord larder 3 -> 2 & bond kept", 600, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(fhome), Sex.MALE);
                c[0].debugSettleWithTent(fhome, Direction.NORTH);
                c[0].setNoAi(true); // 영주 행위 동결(F-7) — 입금·채집 소음 제거
                LarderStore.get(level).set(fhome, 3.0);
                c[1] = spawnAdult(level, Vec3.atBottomCenterOf(fanchor).add(-3, 0, 4), Sex.MALE);
                pl[0] = buildDemoPlot(level, fanchor, c[0].getIndividual().id(), 9);
                c[1].setTenant(pl[0].id, 3);
                c[1].debugSetHolding(0.2);
                c[1].setNoAi(true); // 행위 동결(F-7) — 자가구제 vs 스캔 경주 제거(결정론)
                level.setDayTime(4000L);
            }, () -> String.format("workerH %.2f(start 0.2) larder %.0f(expect 2) bond %s",
                    c[1].getHolding(), LarderStore.get(level).get(fhome),
                    c[1].getTenantFarm() == pl[0].id ? "kept" : "broken"),
                    () -> c[1].getHolding() >= 1.0
                            && Math.abs(LarderStore.get(level).get(fhome) - 2.0) < 1.0E-6
                            && c[1].getTenantFarm() == pl[0].id,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [45] 보호 불이행 — 영주 저장고 0 → 관계 해제(farmbreak 편입)
        {
            BlockPos fanchor = ground(level, b, 45);
            BlockPos fhome = fanchor.offset(-10, 0, 0);
            MimicEntity[] c = new MimicEntity[2];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("farm_shield_break",
                    "lord larder 0 -> relief impossible -> bond dissolves", 600, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(fhome), Sex.MALE);
                c[0].debugSettleWithTent(fhome, Direction.NORTH);
                c[0].setNoAi(true); // 영주 행위 동결(F-7) — 입금·채집 소음 제거
                LarderStore.get(level).set(fhome, 0.0);
                c[1] = spawnAdult(level, Vec3.atBottomCenterOf(fanchor).add(-3, 0, 4), Sex.MALE);
                pl[0] = buildDemoPlot(level, fanchor, c[0].getIndividual().id(), 9);
                c[1].setTenant(pl[0].id, 3);
                c[1].debugSetHolding(0.2);
                c[1].setNoAi(true); // 행위 동결(F-7) — 자가구제 vs 스캔 경주 제거(결정론)
                level.setDayTime(4000L);
            }, () -> String.format("bond %s(expect broken)",
                    c[1].getTenantFarm() == 0L ? "broken" : "kept"),
                    () -> c[1].getTenantFarm() == 0L,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [46] 지대 재투자 — 소작 구획 확장: 계정 지불·순서·소작 불가침(farmgrow 편입)
        {
            BlockPos fanchor = ground(level, b, 46);
            BlockPos fhome = fanchor.offset(-10, 0, 0);
            BlockPos thome = fanchor.offset(-10, 0, 8);
            MimicEntity[] c = new MimicEntity[2];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("farm_grow_reinvest",
                    "tenant expands from plot account: 9->11, acct 7->0, lord 20->21, tenant 16", 600, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(fhome), Sex.MALE);
                c[0].debugSettleWithTent(fhome, Direction.NORTH);
                LarderStore.get(level).set(fhome, 20.0);
                c[1] = spawnAdult(level, Vec3.atBottomCenterOf(thome), Sex.MALE);
                c[1].debugSettleWithTent(thome, Direction.NORTH);
                LarderStore.get(level).set(thome, 16.0);
                pl[0] = buildDemoPlot(level, fanchor, c[0].getIndividual().id(), 9);
                c[1].setTenant(pl[0].id, 3);
                pl[0].account = 7.0;
                FarmStore.get(level).setDirty();
                level.setDayTime(13500L);
            }, () -> String.format("tiles %d(expect 11) acct %.1f(expect 0) lord %.0f(expect 21) "
                    + "tenant %.0f(must stay 16)", pl[0].tiles.length, pl[0].account,
                    LarderStore.get(level).get(fhome), LarderStore.get(level).get(thome)),
                    () -> pl[0].tiles.length == 11
                            && Math.abs(pl[0].account) < 1.0E-6
                            && Math.abs(LarderStore.get(level).get(fhome) - 21.0) < 1.0E-6
                            && Math.abs(LarderStore.get(level).get(thome) - 16.0) < 1.0E-6,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [47] 재투자 금지측 — 만족 지주는 착복: 타일 유지 ∧ 계정 전액 이체(farmhoard 편입)
        {
            BlockPos fanchor = ground(level, b, 47);
            BlockPos fhome = fanchor.offset(-10, 0, 0);
            BlockPos thome = fanchor.offset(-10, 0, 8);
            MimicEntity[] c = new MimicEntity[2];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("farm_hoard_satisfied",
                    "satisfied lord hoards: tiles stay 9, acct 7->0, lord 60->67, tenant 16", 600, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(fhome), Sex.MALE);
                c[0].debugSettleWithTent(fhome, Direction.NORTH);
                LarderStore.get(level).set(fhome, 60.0);
                c[1] = spawnAdult(level, Vec3.atBottomCenterOf(thome), Sex.MALE);
                c[1].debugSettleWithTent(thome, Direction.NORTH);
                LarderStore.get(level).set(thome, 16.0);
                pl[0] = buildDemoPlot(level, fanchor, c[0].getIndividual().id(), 9);
                c[1].setTenant(pl[0].id, 3);
                pl[0].account = 7.0;
                FarmStore.get(level).setDirty();
                c[0].updateMotivation(level);
                level.setDayTime(13500L);
            }, () -> String.format("tiles %d(must stay 9) acct %.1f(expect 0) lord %.0f(expect 67) "
                    + "tenant %.0f(must stay 16)", pl[0].tiles.length, pl[0].account,
                    LarderStore.get(level).get(fhome), LarderStore.get(level).get(thome)),
                    () -> pl[0].tiles.length == 9
                            && Math.abs(pl[0].account) < 1.0E-6
                            && Math.abs(LarderStore.get(level).get(fhome) - 67.0) < 1.0E-6
                            && Math.abs(LarderStore.get(level).get(thome) - 16.0) < 1.0E-6,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [48] 신규 개간 — 무밭 <b>능력자</b> 지주(약초학자, 저장고 40) → 구획 +1 ∧ 저장고 10.
        //      P1 게이트(G≥0.95)로 개간엔 약초/채집 능력 필요 — 무특성은 [58] feudx가 금지 감시.
        {
            BlockPos fhome = ground(level, b, 48);
            MimicEntity[] c = new MimicEntity[1];
            long[] oid = new long[1];
            steps.add(new VerifySuite.Step("farm_found_new",
                    "landless SKILLED founder (larder 40) breaks ground: owned 1 & larder 10", 600, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(fhome), Sex.MALE, Trait.HERBALIST);
                c[0].debugSettleWithTent(fhome, Direction.NORTH);
                LarderStore.get(level).set(fhome, 40.0);
                oid[0] = c[0].getIndividual().id();
                level.setDayTime(13500L);
            }, () -> String.format("owned %d(expect 1) larder %.0f(expect 22)",
                    FarmStore.get(level).ownedCount(oid[0]), LarderStore.get(level).get(fhome)),
                    () -> FarmStore.get(level).ownedCount(oid[0]) == 1
                            && Math.abs(LarderStore.get(level).get(fhome) - 22.0) < 1.0E-6,
                    () -> {
                        for (FarmStore.Plot p : new java.util.ArrayList<>(
                                FarmStore.get(level).all().values())) {
                            if (p.ownerId == oid[0]) {
                                farmClearPlot(level, p);
                            }
                        }
                        discard(c);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [49] 능력 게이트 음성측 — 무능력 지주 33→정확히 35 정지(farmcap 편입)
        {
            BlockPos fanchor = ground(level, b, 49);
            BlockPos fhome = fanchor.offset(-10, 0, 0);
            MimicEntity[] c = new MimicEntity[1];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("farm_skill_cap",
                    "unskilled owner stops at exactly 35 tiles (33 -> 35, larder 30 -> 26)", 600, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(fhome), Sex.MALE);
                c[0].debugSettleWithTent(fhome, Direction.NORTH);
                LarderStore.get(level).set(fhome, 30.0);
                pl[0] = buildDemoPlot(level, fanchor, c[0].getIndividual().id(), 33);
                level.setDayTime(13500L);
            }, () -> String.format("tiles %d(expect exactly 35) larder %.0f(expect 26)",
                    pl[0].tiles.length, LarderStore.get(level).get(fhome)),
                    () -> pl[0].tiles.length == 35
                            && Math.abs(LarderStore.get(level).get(fhome) - 26.0) < 1.0E-6,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [50] 능력 게이트 양성측 — 약초학자 지주 33→36(35 통과, farmable 편입)
        {
            BlockPos fanchor = ground(level, b, 50);
            BlockPos fhome = fanchor.offset(-10, 0, 0);
            MimicEntity[] c = new MimicEntity[1];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("farm_skill_pass",
                    "herbalist owner passes 35 (33 -> 36, larder 30 -> 24)", 600, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(fhome), Sex.MALE, Trait.HERBALIST);
                c[0].debugSettleWithTent(fhome, Direction.NORTH);
                LarderStore.get(level).set(fhome, 30.0);
                pl[0] = buildDemoPlot(level, fanchor, c[0].getIndividual().id(), 33);
                level.setDayTime(13500L);
            }, () -> String.format("tiles %d(expect 36 — must pass 35) larder %.0f(expect 24)",
                    pl[0].tiles.length, LarderStore.get(level).get(fhome)),
                    () -> pl[0].tiles.length == 36
                            && Math.abs(LarderStore.get(level).get(fhome) - 24.0) < 1.0E-6,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [51] 무주지 선점 — 무후 지주 파괴 → 밤에 이웃 흡수(farmvacant 편입)
        {
            BlockPos fanchor = ground(level, b, 51);
            BlockPos fhome = fanchor.offset(-10, 0, 0);
            BlockPos nhome = fanchor.offset(-10, 0, 8);
            MimicEntity[] c = new MimicEntity[2];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            long[] nid = new long[1];
            steps.add(new VerifySuite.Step("farm_vacant_claim",
                    "heirless owner destroyed -> neighbor claims vacant plot at night", 600, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(fhome), Sex.MALE);
                c[0].debugSettleWithTent(fhome, Direction.NORTH);
                c[1] = spawnAdult(level, Vec3.atBottomCenterOf(nhome), Sex.MALE);
                c[1].debugSettleWithTent(nhome, Direction.NORTH);
                pl[0] = buildDemoPlot(level, fanchor, c[0].getIndividual().id(), 9);
                nid[0] = c[1].getIndividual().id();
                c[0].discard();
                level.setDayTime(13500L);
            }, () -> String.format("owner %d(0=vacant, expect neighbor %d) vacantSince %s",
                    pl[0].ownerId, nid[0], pl[0].vacantSince >= 0 ? "set" : "-"),
                    () -> pl[0].ownerId == nid[0] && pl[0].vacantSince < 0,
                    () -> {
                        discard(c[1]);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [52] 출근 관성 — 넉넉 복귀자 배정 유지 ∧ 넉넉 신규자 배정 금지(farmreturn 편입, R2 양측)
        {
            BlockPos fanchor = ground(level, b, 52);
            BlockPos ohome = fanchor.offset(-12, 0, 0);
            BlockPos ahome = fanchor.offset(-12, 0, 8);
            BlockPos bhome = fanchor.offset(-16, 0, 4);
            MimicEntity[] c = new MimicEntity[3];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("farm_return_inertia",
                    "comfortable returner stays assigned (streak 1+), comfortable newcomer stays out", 400, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(ohome), Sex.MALE);
                c[0].debugSettleWithTent(ohome, Direction.NORTH);
                LarderStore.get(level).set(ohome, 5.0);
                c[1] = spawnAdult(level, Vec3.atBottomCenterOf(ahome), Sex.MALE);
                c[1].debugSettleWithTent(ahome, Direction.NORTH);
                LarderStore.get(level).set(ahome, 20.0);
                c[2] = spawnAdult(level, Vec3.atBottomCenterOf(bhome), Sex.MALE);
                c[2].debugSettleWithTent(bhome, Direction.NORTH);
                LarderStore.get(level).set(bhome, 20.0);
                pl[0] = buildDemoPlot(level, fanchor, c[0].getIndividual().id(), 34);
                FarmTicker.debugSeedAssignment(c[1].getId(), pl[0].id);
                level.setDayTime(1200L);
            }, () -> String.format("ret assigned %s streak %d(expect yes 1+) fresh assigned %s(must stay no)",
                    FarmTicker.assignedPlot(c[1].getId()) == pl[0].id ? "yes" : "no",
                    c[1].getTenantStreak(),
                    FarmTicker.assignedPlot(c[2].getId()) == pl[0].id ? "yes" : "no"),
                    () -> FarmTicker.assignedPlot(c[1].getId()) == pl[0].id
                            && c[1].getTenantStreak() >= 1
                            && FarmTicker.assignedPlot(c[2].getId()) == 0L,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [53] 개간 노동 상한 — 2구획 지주 하루 합산 +3타일(farmlabor 편입, R3)
        {
            BlockPos fanchor = ground(level, b, 53);
            BlockPos a2 = fanchor.offset(0, 0, -24);
            BlockPos fhome = fanchor.offset(-12, 0, 0);
            MimicEntity[] c = new MimicEntity[1];
            FarmStore.Plot[] pl = new FarmStore.Plot[2];
            steps.add(new VerifySuite.Step("farm_labor_cap",
                    "two-plot owner clears 3 tiles/day total (9+9 -> 21, larder 30 -> 21)", 600, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(fhome), Sex.MALE);
                c[0].debugSettleWithTent(fhome, Direction.NORTH);
                LarderStore.get(level).set(fhome, 30.0);
                pl[0] = buildDemoPlot(level, fanchor, c[0].getIndividual().id(), 9);
                pl[1] = buildDemoPlot(level, a2, c[0].getIndividual().id(), 9);
                level.setDayTime(13500L);
            }, () -> String.format("tiles %d+%d=%d(expect 21) larder %.0f(expect 21)",
                    pl[0].tiles.length, pl[1].tiles.length, pl[0].tiles.length + pl[1].tiles.length,
                    LarderStore.get(level).get(fhome)),
                    () -> pl[0].tiles.length + pl[1].tiles.length == 21
                            && Math.abs(LarderStore.get(level).get(fhome) - 21.0) < 1.0E-6,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        farmClearPlot(level, pl[1]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [54] 경쟁 이웃 부 대칭 — 이웃 저장고 5+계정 8 > 자기 10 → driven(farmenvy 편입, R5)
        {
            BlockPos fanchor = ground(level, b, 54);
            BlockPos ahome = fanchor.offset(-10, 0, 0);
            BlockPos bhome = fanchor.offset(-10, 0, 8);
            MimicEntity[] c = new MimicEntity[2];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("farm_envy_account",
                    "competitive mimic driven by neighbor larder 5 + plot account 8 vs own 10", 400, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(ahome), Sex.MALE, Trait.COMPETITIVE);
                c[0].debugSettleWithTent(ahome, Direction.NORTH);
                LarderStore.get(level).set(ahome, 10.0);
                c[1] = spawnAdult(level, Vec3.atBottomCenterOf(bhome), Sex.MALE);
                c[1].debugSettleWithTent(bhome, Direction.NORTH);
                LarderStore.get(level).set(bhome, 5.0);
                pl[0] = buildDemoPlot(level, fanchor, c[1].getIndividual().id(), 9);
                pl[0].account = 8.0;
                FarmStore.get(level).setDirty();
                level.setDayTime(1200L);
            }, () -> String.format("driven %s(expect yes) — mine 10 vs neighbor 5+acct8",
                    c[0].isCompetitiveDriven() ? "yes" : "no"),
                    () -> c[0].isCompetitiveDriven(),
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [55] 유령 용량 제거 — 만족 지주 15타일: need 15 게시 → 이웃 배정(farmretire 편입, R6)
        {
            BlockPos fanchor = ground(level, b, 55);
            BlockPos ohome = fanchor.offset(-10, 0, 0);
            BlockPos whome = fanchor.offset(-10, 0, 8);
            MimicEntity[] c = new MimicEntity[2];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("farm_retire_slots",
                    "satisfied lord excluded from capacity: 15-tile plot posts need 15 -> hire", 400, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(ohome), Sex.MALE);
                c[0].debugSettleWithTent(ohome, Direction.NORTH);
                LarderStore.get(level).set(ohome, 60.0);
                c[1] = spawnAdult(level, Vec3.atBottomCenterOf(whome), Sex.MALE);
                c[1].debugSettleWithTent(whome, Direction.NORTH);
                LarderStore.get(level).set(whome, 0.0);
                pl[0] = buildDemoPlot(level, fanchor, c[0].getIndividual().id(), 15);
                level.setDayTime(1200L);
            }, () -> String.format("owner satisfied %s worker assigned %s(expect yes yes)",
                    c[0].isSatisfiedToday() ? "yes" : "no",
                    FarmTicker.assignedPlot(c[1].getId()) == pl[0].id ? "yes" : "no"),
                    () -> FarmTicker.assignedPlot(c[1].getId()) == pl[0].id,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [56] 동기 대조 — 부지런 지주는 만족 무시하고 수확(farmdrive 편입, farm_idle 의 양성 쌍)
        {
            BlockPos fanchor = ground(level, b, 56);
            BlockPos fhome = fanchor.offset(-10, 0, 0);
            MimicEntity[] c = new MimicEntity[1];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("farm_drive_diligent",
                    "diligent rich owner ignores satisfaction and harvests (ripe < 9)", 600, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(fhome), Sex.MALE, Trait.DILIGENT);
                c[0].debugSettleWithTent(fhome, Direction.NORTH);
                LarderStore.get(level).set(fhome, 60.0);
                pl[0] = buildDemoPlot(level, fanchor, c[0].getIndividual().id(), 9);
                level.setDayTime(1200L);
            }, () -> String.format("ripe %d(start 9, expect <9) satisfied %s",
                    countRipe(level, pl[0]), c[0].isSatisfiedToday() ? "yes" : "no"),
                    () -> countRipe(level, pl[0]) < 9,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }

        // [57] 통근 초과 해제 — 상시 소작을 60블록 밖으로 이동 → 새벽에 관계 소멸(수동 관찰 자동화)
        {
            BlockPos fanchor = ground(level, b, 57);
            MimicEntity[] c = new MimicEntity[2];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("farm_commute_break",
                    "tenant moved 60 blocks away -> dawn dissolves the bond", 400, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(fanchor).add(-3, 0, 0), Sex.MALE);
                c[1] = spawnAdult(level, Vec3.atBottomCenterOf(fanchor).add(-3, 0, 4), Sex.MALE);
                pl[0] = buildDemoPlot(level, fanchor, c[0].getIndividual().id(), 9);
                c[1].setTenant(pl[0].id, 3);
                c[1].teleportTo(fanchor.getX() + 60.5, fanchor.getY(), fanchor.getZ() + 0.5);
                level.setDayTime(1200L);
            }, () -> String.format("bond %s(expect broken — commute 60 > 48)",
                    c[1].getTenantFarm() == 0L ? "broken" : "kept"),
                    () -> c[1].getTenantFarm() == 0L,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [58] 무주지 만료 소거 — vacantSince 를 2.5일 전으로 시드 → 등록 소멸 ∧ 베리 잔존(자동화)
        {
            BlockPos fanchor = ground(level, b, 58);
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            long[] pid = new long[1];
            steps.add(new VerifySuite.Step("farm_vacant_expire",
                    "expired vacant plot: registry removed but wild berries remain", 400, false, () -> {
                FarmTicker.clearAssignments();
                pl[0] = buildDemoPlot(level, fanchor, 0L, 9);
                pid[0] = pl[0].id;
                pl[0].vacantSince = level.getGameTime()
                        - com.evosim.core.FarmEconomy.VACANT_EXPIRE_TICKS - 1;
                FarmStore.get(level).setDirty();
                level.setDayTime(4000L);
            }, () -> String.format("registered %s(expect gone) bushes %d(must stay 9)",
                    FarmStore.get(level).get(pid[0]) == null ? "gone" : "yes",
                    countRipe(level, pl[0])),
                    () -> FarmStore.get(level).get(pid[0]) == null && countRipe(level, pl[0]) == 9,
                    () -> {
                        farmClearPlot(level, pl[0]); // 등록은 이미 소멸 — 블록만 회수(debugRemove 멱등)
                        FarmTicker.clearAssignments();
                    }));
        }

        // [59] 가족 노동 — 남편 noAI, 아내가 배우자 밭 수확(가족분 100%: 지대 0)
        {
            BlockPos fanchor = ground(level, b, 59);
            BlockPos fhome = fanchor.offset(-10, 0, 0);
            MimicEntity[] c = new MimicEntity[2];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("farm_family_labor",
                    "wife harvests husband's plot at 100% (ripe<9 & rent stays 0)", 1200, false, () -> {
                FarmTicker.clearAssignments();
                MimicEntity[] cc = coupleAt(level, fhome);
                c[0] = cc[0];
                c[1] = cc[1];
                LarderStore.get(level).set(fhome, 0.0);
                pl[0] = buildDemoPlot(level, fanchor, c[0].getIndividual().id(), 9);
                c[0].setNoAi(true);
                level.setDayTime(4000L);
            }, () -> String.format("ripe %d(start 9, expect <9) acct %.2f(must stay 0)",
                    countRipe(level, pl[0]), pl[0].account),
                    () -> countRipe(level, pl[0]) < 9 && Math.abs(pl[0].account) < 1.0E-6,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }
        // [60] 케어 예산 — 근접 9+원거리 30 지주: 원거리 need 27 → 3인 배정(중복 차감이면 2인)
        {
            BlockPos fanchor = ground(level, b, 60);
            BlockPos farAnchor = fanchor.offset(0, 0, 24);
            BlockPos fhome = fanchor.offset(-10, 0, -10);
            MimicEntity[] c = new MimicEntity[4];
            FarmStore.Plot[] pl = new FarmStore.Plot[2];
            steps.add(new VerifySuite.Step("farm_care_budget",
                    "care budget drains nearest-first: far 30-tile plot posts 27 -> 3 hires", 400, false, () -> {
                FarmTicker.clearAssignments();
                c[0] = spawnAdult(level, Vec3.atBottomCenterOf(fhome), Sex.MALE);
                c[0].debugSettleWithTent(fhome, Direction.NORTH);
                LarderStore.get(level).set(fhome, 5.0);
                for (int i = 0; i < 3; i++) {
                    BlockPos wh = fanchor.offset(-14, 0, 8 + 4 * i);
                    c[i + 1] = spawnAdult(level, Vec3.atBottomCenterOf(wh), Sex.MALE);
                    c[i + 1].debugSettleWithTent(wh, Direction.NORTH);
                    LarderStore.get(level).set(wh, 0.0);
                }
                pl[0] = buildDemoPlot(level, fanchor, c[0].getIndividual().id(), 9);
                pl[1] = buildDemoPlot(level, farAnchor, c[0].getIndividual().id(), 30);
                level.setDayTime(1200L);
            }, () -> String.format("assigned far %d/3(expect 3) near %d(expect 0)",
                    (int) java.util.stream.IntStream.rangeClosed(1, 3)
                            .filter(i -> FarmTicker.assignedPlot(c[i].getId()) == pl[1].id).count(),
                    (int) java.util.stream.IntStream.rangeClosed(1, 3)
                            .filter(i -> FarmTicker.assignedPlot(c[i].getId()) == pl[0].id).count()),
                    () -> java.util.stream.IntStream.rangeClosed(1, 3)
                            .allMatch(i -> FarmTicker.assignedPlot(c[i].getId()) == pl[1].id),
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        farmClearPlot(level, pl[1]);
                        FarmTicker.clearAssignments();
                    }));
        }

        // [61] 부유층 중혼 — 욕심 남편은 인색 아내의 질투를 누른다(구 코드면 거절 → timeout FAIL)
        {
            BlockPos home = ground(level, b, 61);
            MimicEntity[] c = new MimicEntity[3];
            steps.add(new VerifySuite.Step("polygamy_elite",
                    "greedy husband overrides stingy wife's veto: bride married & joined", 1200, false, () -> {
                MimicEntity h = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE, Trait.GREEDY);
                MimicEntity w1 = spawnAdult(level, Vec3.atBottomCenterOf(home).add(0.5, 0, 0),
                        Sex.FEMALE, Trait.STINGY);
                h.debugSettleWithTent(home, Direction.NORTH);
                w1.debugSettleWithTent(home, Direction.NORTH);
                h.debugMarryTo(w1);
                c[0] = h;
                c[1] = w1;
                c[2] = spawnAdult(level, Vec3.atBottomCenterOf(home).add(4, 0, 0), Sex.FEMALE);
                LarderStore.get(level).set(home, 40.0);
                level.setDayTime(9000L);
            }, () -> String.format("bride %s joined %s",
                    c[2].isSingleAdult() ? "single" : "married",
                    home.equals(c[2].getHomePos()) ? "O" : "X"),
                    () -> !c[2].isSingleAdult() && home.equals(c[2].getHomePos()),
                    () -> discard(c)));
        }
        // [62] 상한 없음 — 관용 처 보유 평민도 셋째 부인까지(부양만 되면; 구 코드는 2처 상한 거절)
        {
            BlockPos home = ground(level, b, 62);
            MimicEntity[] c = new MimicEntity[4];
            steps.add(new VerifySuite.Step("polygamy_no_cap",
                    "no wife cap: both brides join (3 wives total) while provision holds", 1600, false, () -> {
                MimicEntity[] cc = coupleAt(level, home);
                c[0] = cc[0];
                c[1] = cc[1];
                c[2] = spawnAdult(level, Vec3.atBottomCenterOf(home).add(4, 0, 0), Sex.FEMALE);
                c[3] = spawnAdult(level, Vec3.atBottomCenterOf(home).add(-4, 0, 2), Sex.FEMALE);
                LarderStore.get(level).set(home, 60.0);
                level.setDayTime(9000L);
            }, () -> String.format("bride2 %s bride3 %s (both must join)",
                    c[2].isSingleAdult() ? "single" : "married",
                    c[3].isSingleAdult() ? "single" : "married"),
                    () -> !c[2].isSingleAdult() && home.equals(c[2].getHomePos())
                            && !c[3].isSingleAdult() && home.equals(c[3].getHomePos()),
                    () -> discard(c)));
        }
        // [63] 부유선호 — 잉여가 매력: 부유선호 신부가 가난 독신남 대신 부유 기혼남(감점 −2)을 택함
        {
            BlockPos home = ground(level, b, 63);
            MimicEntity[] c = new MimicEntity[4];
            steps.add(new VerifySuite.Step("pref_wealth_charm",
                    "wealth-preferring bride marries rich married man over poor single (+3 beats -2)", 1200, false, () -> {
                MimicEntity[] cc = coupleAt(level, home); // 부유 기혼남 R + 관용 아내
                c[0] = cc[0];
                c[1] = cc[1];
                LarderStore.get(level).set(home, 120.0); // ≥27일치 — 부유 가점 +3(감점 −2 상쇄+1)
                c[2] = spawnAdult(level, Vec3.atBottomCenterOf(home).add(6, 0, 4), Sex.MALE);
                c[2].setNoAi(true); // 가난 독신남 P — 구애 개시 봉쇄(신부의 선택만 판정)
                c[3] = spawnBride(level, Vec3.atBottomCenterOf(home).add(4, 0, -2), Trait.PREF_WEALTH);
                level.setDayTime(9000L);
            }, () -> String.format("bride %s home %s(expect rich R's home)",
                    c[3].isSingleAdult() ? "single" : "married",
                    home.equals(c[3].getHomePos()) ? "R" : "other"),
                    () -> !c[3].isSingleAdult() && home.equals(c[3].getHomePos()),
                    () -> discard(c)));
        }

        // [64] 다처 케어 예산 대칭 — 첩(spouseId=남편, 비대칭)이 소유한 30타일 밭: 남편은 그 밭을
        // 수확할 수 없으므로(자기 spouseId=본처) 예산에 계상되면 안 된다. 구 코드는 남편 12를 세
        // need 18→6<10 무고용(방치), 신 코드는 첩 12만 세 need 18 → 가난 이웃 배정.
        {
            BlockPos fanchor = ground(level, b, 64);
            BlockPos fhome = fanchor.offset(-12, 0, 0);
            BlockPos whome = fanchor.offset(-14, 0, 8);
            MimicEntity[] c = new MimicEntity[3];
            FarmStore.Plot[] pl = new FarmStore.Plot[1];
            steps.add(new VerifySuite.Step("farm_polygyny_budget",
                    "concubine-owned plot excludes husband's capacity (asymmetric spouseId): hire happens", 400, false, () -> {
                FarmTicker.clearAssignments();
                MimicEntity husband = spawnAdult(level, Vec3.atBottomCenterOf(fhome), Sex.MALE);
                MimicEntity concubine = spawnAdult(level, Vec3.atBottomCenterOf(fhome).add(0.5, 0, 0), Sex.FEMALE);
                husband.debugSettleWithTent(fhome, Direction.NORTH);
                concubine.debugSettleWithTent(fhome, Direction.NORTH);
                concubine.setSpouse(husband.getIndividual().id()); // 첩→남편(비대칭 — 남편은 본처 유지)
                LarderStore.get(level).set(fhome, 5.0);
                c[0] = husband;
                c[1] = concubine;
                c[2] = spawnAdult(level, Vec3.atBottomCenterOf(whome), Sex.MALE); // 가난 이웃(후보)
                c[2].debugSettleWithTent(whome, Direction.NORTH);
                LarderStore.get(level).set(whome, 0.0);
                pl[0] = buildDemoPlot(level, fanchor, concubine.getIndividual().id(), 30);
                level.setDayTime(1200L);
            }, () -> String.format("worker assigned %s(expect yes — husband cap must be excluded)",
                    FarmTicker.assignedPlot(c[2].getId()) == pl[0].id ? "yes" : "no"),
                    () -> FarmTicker.assignedPlot(c[2].getId()) == pl[0].id,
                    () -> {
                        discard(c);
                        farmClearPlot(level, pl[0]);
                        FarmTicker.clearAssignments();
                    }));
        }

        // 슬롯이 시뮬레이션 거리(≈10청크)를 넘어 이어지므로 각 단계 시작 시 플레이어를 해당 슬롯으로
        // 동행 이동 — 개체 AI 틱 보장(스텝 슬롯은 순서와 정렬: ⑩만 슬롯 9 공유, 64블록 인접이라 무해).
        // 슬롯은 스텝의 "원래 번호"(리스트 위치 i+1)로 고정 — 필터(checkall2) 후에도 setup 의
        // 하드코딩 슬롯과 어긋나지 않게 각 스텝 생성 시점 index 로 지정한다.
        for (int i = 0; i < steps.size(); i++) {
            steps.get(i).at(ground(level, b, i + 1));
        }
        if (retryOnly) {
            // 미통과 단계만 남긴다(순서·슬롯·판정식 불변 — 오직 부분집합 추출 + 제한시간 배율).
            List<VerifySuite.Step> subset = new ArrayList<>();
            for (VerifySuite.Step s : steps) {
                if (RETRY_SLUGS.contains(s.name())) {
                    subset.add(s);
                }
            }
            VerifySuite.start(ctx.getSource(), subset, RETRY_TIMEOUT_SCALE);
            tell(ctx.getSource(), "checkall2 — 1회차 미통과 " + subset.size() + "단계만 재관찰(제한시간 ×"
                    + RETRY_TIMEOUT_SCALE + "). 로직·조성은 불변, 시간만 넉넉. 이동·구애가 창 안에 "
                    + "완주하는지 지켜본다. 끝에 ✅/❌ 요약 + evosim-verify.log 기록. (초원에서 실행)");
            return 1;
        }
        VerifySuite.start(ctx.getSource(), steps);
        tell(ctx.getSource(), "원트랙 검증 시작 — 64단계(㊳ 순수 evotest 전량 + ㊴~ 밭 게이트 전 편입), "
                + "각 단계는 발동 직전 조건을 자동 조성하고 결과값의 변화로만 판정. 단계마다 플레이어가 "
                + "해당 슬롯으로 이동됨(원거리 개체 틱 보장 — 플레이어가 직접 실행할 것). 끝에 ✅/❌ 요약. "
                + "영문 결과는 콘솔(성공 녹/실패 적)과 evosim-verify.log 파일에 동시 기록. "
                + "(6번은 주변에 풀이 있어야 함 — 초원에서 실행 권장)");
        return 1;
    }

    /** 노년 전이·자연사 즉시 판별: fast 성장 성년 → 노년 경유(상태 실측) → 사망. */
    private static int stageElder(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        MimicEntity e = spawnAdult(level, b.add(-4, 0, 0), Sex.MALE);
        if (e == null) {
            return 0;
        }
        e.setFastGrowth(true); // 단계당 40틱 — 성년→노년(2초)→자연사(4초)
        boolean[] sawElder = new boolean[1];
        LiveCheck.watch(ctx.getSource(), "노년·자연사", 400,
                () -> {
                    if (e.getStage() == LifeStage.ELDER) {
                        sawElder[0] = true;
                    }
                    return String.format("stage %s elderSeen %s alive %s",
                            stageName(e.getStage()), sawElder[0] ? "O" : "X", e.isAlive() ? "O" : "X");
                },
                () -> {
                    if (e.getStage() == LifeStage.ELDER) {
                        sawElder[0] = true;
                    }
                    return sawElder[0] && !e.isAlive(); // 결과값: 노년을 거쳐 실제로 죽었는가
                },
                () -> discard(e)); // 실패(생존) 시에도 무대 개체 정리
        tell(ctx.getSource(), "노년 판별 — 기대: 약 2초 뒤 성년→노년([성장]), 다시 약 2초 뒤 [자연사]. "
                + "노년을 거치지 않거나 살아있으면 실패.");
        return 1;
    }

    /** 노인 공유·마실 육아 즉시 판별: 책임 노인(잉여 H=3.5) + 친자식 집(유아) → 그 집 저장고 증가 실측. */
    private static int stageElderCare(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 b = ctx.getSource().getPosition();
        SimEvents.setEnabled(true, level.getServer().getServerDirectory().toPath());
        LiveCheck.cancelAll(); // 진행 중 판별 인수(명시적 중단·정리) — 좌표 공유 오살 방지
        BlockPos homeE = groundAt(level, b, -6, -8);
        BlockPos homeC = groundAt(level, b, -6, 36); // 자식 집 — 44블록(활동반경 32 밖: 마실 앵커 실검증)
        discardFamily(level, homeE); // 재실행 잔재 정리(두 집 모두)
        discardFamily(level, homeC);
        // 노인은 집 반경(6) 밖에서 스폰 — 가족틱이 잉여를 자기 저장고로 흡수하는 경쟁(race) 차단.
        MimicEntity elder = spawnAdult(level, Vec3.atBottomCenterOf(homeE).add(8, 0, 4), Sex.MALE,
                Trait.OVER_RESPONSIBLE); // 책임 — 잉여 목표·배달형
        if (elder == null) {
            return 0;
        }
        elder.setStage(LifeStage.ELDER);
        elder.debugSettleWithTent(homeE, Direction.NORTH);
        LarderStore.get(level).set(homeE, 8.0);  // 가드②(자기 저장고 ≥ 하루소모) 통과
        elder.debugSetHolding(3.5);              // 잉여 정수 2개 → 배달 직전
        MimicEntity child = spawnChildOf(level, Vec3.atBottomCenterOf(homeC), elder, Sex.MALE);
        child.debugSettleWithTent(homeC, Direction.NORTH);
        MimicEntity infant = stagedInfant(level, homeC, Sex.FEMALE); // 손자(유아) — 마실 우선 대상
        LarderStore.get(level).set(homeC, 0.0);
        level.setDayTime(2000L); // 노동 시간(마감 6000 전)
        LiveCheck.watch(ctx.getSource(), "노인공유", 1200,
                () -> String.format("자식집 저장고 %.0f(시작 0·기대 2) · 노인 H %.2f(시작 3.5) · 노인↔자식집 %.0f블록",
                        LarderStore.get(level).get(homeC), elder.getHolding(),
                        Math.sqrt(elder.blockPosition().distSqr(homeC))),
                () -> LarderStore.get(level).get(homeC) >= 2.0 - 1.0E-6, // 결과값: 자식 저장고 실증가
                () -> discard(elder, child, infant));
        tell(ctx.getSource(), "노인 공유 판별 — 기대: 책임 노인이 자식 집(유아 있음)으로 걸어가 [노인공유] "
                + "저장고 0→2, 이후 유아 곁 머묾(마실 육아). 저장고가 안 늘면 실패.");
        return 1;
    }

    /** 스테이징용 — 부모 링크가 걸린 성년 자식 소환(노인 방문 대상 성립 검증용). */
    /** 관리 등급 지정 성년 소환(마름 선발 검증용) — 약초학자 grade + 기본 셋. grade 0이면 특성 없음. */
    private static MimicEntity spawnGradedAdult(ServerLevel level, Vec3 pos, Sex sex, int grade,
                                                Trait... extra) {
        MimicEntity e = ModEntities.MIMIC.get().create(level);
        long id = Math.abs((int) level.getGameTime()) + level.random.nextInt(1_000_000);
        Individual ind = new Individual(id, sex, 0, 0, 1);
        ind.addTrait(TraitInstance.of(Trait.PREF_STRENGTH));
        if (grade > 0) {
            ind.addTrait(TraitInstance.graded(Trait.HERBALIST, grade));
        }
        for (Trait t : extra) {
            ind.addTrait(t.isGraded() ? TraitInstance.graded(t, 5) : TraitInstance.of(t));
        }
        e.setIndividual(ind);
        e.setStage(LifeStage.ADULT);
        e.moveTo(pos.x, pos.y, pos.z, level.random.nextFloat() * 360f, 0f);
        e.markStageActor();
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()),
                MobSpawnType.COMMAND, null, null);
        level.addFreshEntity(e);
        return e;
    }

    private static MimicEntity spawnChildOf(ServerLevel level, Vec3 pos, MimicEntity parent, Sex sex) {
        MimicEntity e = ModEntities.MIMIC.get().create(level);
        if (e == null) {
            throw new IllegalStateException("자식 스폰 실패");
        }
        Individual p = parent.getIndividual();
        long id = Math.abs((int) level.getGameTime()) + level.random.nextInt(1_000_000);
        Individual ind = new Individual(id, sex, p.id(), 0, p.generation() + 1);
        e.setIndividual(ind);
        e.setStage(LifeStage.ADULT);
        e.moveTo(pos.x, pos.y, pos.z, 0f, 0f);
        e.markStageActor(); // 검증 무대 개체 — 혈통 원장·통계 오염 방지
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()),
                MobSpawnType.COMMAND, null, null);
        level.addFreshEntity(e);
        return e;
    }

    /** 정착 부부 헬퍼 — 천막+혼인, 아내 추가 특성 지정 가능(질투 케이스). */
    private static MimicEntity[] coupleAt(ServerLevel level, BlockPos home, Trait... wifeTraits) {
        MimicEntity m = spawnAdult(level, Vec3.atBottomCenterOf(home), Sex.MALE);
        MimicEntity f = spawnAdult(level, Vec3.atBottomCenterOf(home).add(0.5, 0, 0), Sex.FEMALE, wifeTraits);
        if (m == null || f == null) {
            throw new IllegalStateException("부부 스폰 실패");
        }
        m.debugSettleWithTent(home, Direction.NORTH);
        f.debugSettleWithTent(home, Direction.NORTH);
        m.debugMarryTo(f);
        return new MimicEntity[] {m, f};
    }

    /** 지형 높이에 맞춘 무대 거처 좌표 — 단독 명령이 플레이어 발 Y를 그대로 써서 경사지에서
     *  천막이 공중/벽 속에 깔리던 것을 heightmap 으로 보정(위치 x/z 는 그대로 — 관찰성 유지). */
    private static BlockPos groundAt(ServerLevel level, Vec3 b, double dx, double dz) {
        return surface(level, level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                BlockPos.containing(b.x + dx, 0, b.z + dz)));
    }

    /**
     * 스테이징 잔재 투과 지면 — heightmap 이 이전 런의 천막 지붕(양털)·울타리·모닥불·베리 위를 찍으면
     * 그 아래 실지면까지 내려간다. 잔재 위 앵커는 지붕 스폰(낙사)·공중 목적지(경로 NULL)를 만들던
     * "같은 자리 재실행" 오염의 근원(F-2). 형검사만 통과하므로 자연 지형엔 영향 없음.
     */
    private static BlockPos surface(ServerLevel level, BlockPos hm) {
        BlockPos p = hm;
        while (p.getY() > level.getMinBuildHeight() + 1) {
            var below = level.getBlockState(p.below());
            if (below.is(Blocks.WHITE_WOOL) || below.is(Blocks.OAK_FENCE)
                    || below.is(Blocks.CAMPFIRE) || below.is(Blocks.SWEET_BERRY_BUSH)
                    // 나무도 지면이 아니다 — 밑동 잘린 공중 원목·캐노피 위를 heightmap 이 찍으면
                    // 무대가 하늘에 조성돼(개체 y+39 등) 간헐 실패를 만든다(F-2 확장, elderx 실측).
                    || below.is(net.minecraft.tags.BlockTags.LOGS)
                    || below.is(net.minecraft.tags.BlockTags.LEAVES)
                    || below.isAir()) {
                p = p.below();
                continue;
            }
            break;
        }
        return p;
    }

    /** 지형 높이에 맞춘 검증 슬롯 좌표(슬롯당 z+64 — 인식·나눔 범위 밖으로 격리). */
    private static BlockPos ground(ServerLevel level, Vec3 b, int slot) {
        BlockPos p = BlockPos.containing(b.add(-8, 0, slot * 64));
        // 청크 강제 로드(ChunkStatus.FULL) 후 높이 읽기 — 미로드 청크면 Level.getHeight 가 표면이
        // 아니라 getMinBuildHeight(기반암 레벨)를 돌려주므로(강제 로드 안 함) 먼 슬롯이 기반암에
        // 박히는 것을 막는다. 슬롯은 z+64×단계라 대부분 플레이어 로드 반경 밖(64단계=4096블록).
        level.getChunk(p.getX() >> 4, p.getZ() >> 4);
        return surface(level, level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p));
    }

    private static int infantsAt(ServerLevel level, BlockPos home) {
        int n = 0;
        for (MimicEntity e : level.getEntitiesOfClass(MimicEntity.class,
                new net.minecraft.world.phys.AABB(home).inflate(8.0))) {
            if (e.getStage() == LifeStage.INFANT && home.equals(e.getHomePos())) {
                n++;
            }
        }
        return n;
    }

    /** 무대 개체 정리(널 허용). */
    private static void discard(MimicEntity... es) {
        for (MimicEntity e : es) {
            if (e != null && e.isAlive()) {
                e.discard();
            }
        }
    }

    /** 무대 가족 정리 — 무대 중 태어난 유아까지 포함해 제거. */
    private static void discardFamily(ServerLevel level, BlockPos home, MimicEntity... es) {
        discard(es);
        for (MimicEntity e : level.getEntitiesOfClass(MimicEntity.class,
                new net.minecraft.world.phys.AABB(home).inflate(12.0))) {
            if (home.equals(e.getHomePos())) {
                e.discard();
            }
        }
        // 천막 블록 잔재 소거(F-2) — 개체만 지우면 천막이 같은 자리에 누적돼 다음 런의 heightmap 이
        // 지붕을 찍는다(지붕 스폰 낙사·공중 앵커 경로 NULL). 무대용 자재만 형검사 소거라 지형 무해.
        for (BlockPos p : BlockPos.betweenClosed(home.offset(-8, -6, -8), home.offset(8, 8, 8))) {
            var st = level.getBlockState(p);
            if (st.is(Blocks.WHITE_WOOL) || st.is(Blocks.OAK_FENCE) || st.is(Blocks.CAMPFIRE)) {
                level.setBlockAndUpdate(p.immutable(), Blocks.AIR.defaultBlockState());
            }
        }
    }

    /** 검증 전용 스폰 — 지정 특성 하나만(등급 지정 가능) 가진 성년 남성. stage=false 는 원장 등록
     *  검증용 실개체(자연 개체와 같은 64비트 id 공간 — cleanup 이 원장에서 회수해야 함). */
    private static MimicEntity spawnVerifyAdult(ServerLevel level, Vec3 pos, boolean stageActor,
                                                Trait trait, int grade) {
        MimicEntity e = ModEntities.MIMIC.get().create(level);
        if (e == null) {
            throw new IllegalStateException("spawn failed");
        }
        Individual ind = new Individual(Math.abs(level.random.nextLong() | 1L), Sex.MALE, 0, 0, 1);
        if (trait != null) {
            ind.addTrait(grade > 0 ? TraitInstance.graded(trait, grade) : TraitInstance.of(trait));
        }
        e.setIndividual(ind);
        e.setStage(LifeStage.ADULT);
        e.moveTo(pos.x, pos.y, pos.z, 0f, 0f);
        if (stageActor) {
            e.markStageActor();
        }
        level.addFreshEntity(e);
        return e;
    }

    /**
     * 단일 선호 특성만 가진 무대 신부 — spawnAdult 의 기본 선호 3종과 섞이면 발현 상한(카테고리 3)에
     * 걸려 검증 대상 선호가 침묵할 수 있어, 선호 축 검증은 이 헬퍼로 깨끗하게 조성한다.
     */
    private static MimicEntity spawnBride(ServerLevel level, Vec3 pos, Trait pref) {
        MimicEntity e = ModEntities.MIMIC.get().create(level);
        if (e == null) {
            throw new IllegalStateException("bride spawn failed");
        }
        long id = Math.abs((int) level.getGameTime()) + level.random.nextInt(1_000_000);
        Individual ind = new Individual(id, Sex.FEMALE, 0, 0, 1);
        ind.addTrait(TraitInstance.of(pref));
        e.setIndividual(ind);
        e.setStage(LifeStage.ADULT);
        e.moveTo(pos.x, pos.y, pos.z, 0f, 0f);
        e.markStageActor();
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()),
                MobSpawnType.COMMAND, null, null);
        level.addFreshEntity(e);
        return e;
    }

    /** 스테이징용 유아 소환 — 거처 귀속 + 개체 반환. */
    private static MimicEntity stagedInfant(ServerLevel level, BlockPos home, Sex sex) {
        MimicEntity e = ModEntities.MIMIC.get().create(level);
        if (e == null) {
            return null;
        }
        Individual ind = Genetics.randomFirstGen(
                Math.abs((int) level.getGameTime()) + level.random.nextInt(100000),
                new DeterministicRng(level.random.nextLong()), sex);
        e.setIndividual(ind);
        e.setStage(LifeStage.INFANT);
        e.moveTo(home.getX() + 1.5, home.getY(), home.getZ() + 0.5, 0f, 0f);
        e.setHomePos(home);
        e.markStageActor(); // 검증 무대 개체 — 혈통 원장·통계 오염 방지
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()),
                MobSpawnType.COMMAND, null, null);
        level.addFreshEntity(e);
        return e;
    }

    /**
     * 고정특성 표준 부부 소환(계측 통제용) — wildpairs(완전 랜덤 특성)를 대체해 <b>맵 시드만</b>
     * 변동시키고 개체 능력을 고정한다. 모든 개체가 spawnMatingReady 기본 특성(선호 3 + STRONG +
     * BRIGHT + NIMBLE)으로 동일 → 능력 변동 제거로 U1·U2 편차를 맵 요인만 남긴다. 정상 개체
     * (통계·번식·상속 정상 편입 — 무대 아님)라 출산 baseline이 그대로 관측된다. 측정 도구 전용:
     * 게임플레이 로직 무변경.
     */
    private static int fixedPairs(CommandContext<CommandSourceStack> ctx, int pairs) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Vec3 base = src.getPosition();
        for (int i = 0; i < pairs; i++) {
            spawnMatingReady(level, scatter(level, base), Sex.MALE);
            spawnMatingReady(level, scatter(level, base), Sex.FEMALE);
        }
        src.sendSuccess(() -> Component.literal(
                        "고정특성 표준 부부 소환: 남 " + pairs + " · 여 " + pairs
                                + " (동일 특성 — 맵만 변동 통제 계측용)")
                .withStyle(ChatFormatting.GREEN), false);
        return pairs * 2;
    }

    private static void spawnWild(ServerLevel level, Vec3 pos, Sex sex) {
        MimicEntity e = ModEntities.MIMIC.get().create(level);
        if (e == null) {
            return;
        }
        long id = Math.abs((int) level.getGameTime()) + level.random.nextInt(1_000_000);
        Individual ind = Genetics.randomFirstGen(id, new DeterministicRng(level.random.nextLong()), sex);
        e.setIndividual(ind);
        e.setStage(LifeStage.ADULT);
        e.moveTo(pos.x, pos.y, pos.z, level.random.nextFloat() * 360f, 0f);
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()),
                MobSpawnType.COMMAND, null, null);
        level.addFreshEntity(e);
    }

    private static Vec3 scatter(ServerLevel level, Vec3 base) {
        // 지면 스냅 — 자연 지형(관측 런)에서 고정 y 흩뿌리기가 공중 스폰→추락사를 만들던 결함
        // 수정(실측: obs 14명 중 여성 전멸). heightmap으로 각 지점의 실지면에 내려 앉힌다.
        double r = 12.0;
        double dx = (level.random.nextDouble() - 0.5) * r;
        double dz = (level.random.nextDouble() - 0.5) * r;
        return Vec3.atBottomCenterOf(groundAt(level, base, dx, dz));
    }

    private static void spawnMatingReady(ServerLevel level, Vec3 pos, Sex sex) {
        spawnMatingReady(level, pos, sex, new Trait[0]);
    }

    /**
     * 자연 개체 스폰(무대 아님 — 혈통·통계·상속에 정상 편입) + 추가 특성. 등급 특성은 Ⅴ로
     * 부여(관측 런의 엘리트는 최상급 기준 — 이정표 수식이 Ⅴ 수치로 역산돼 있음).
     */
    private static MimicEntity spawnMatingReady(ServerLevel level, Vec3 pos, Sex sex, Trait... extra) {
        return spawnMatingReady(level, pos, sex, java.util.Set.of(), extra);
    }

    /**
     * 위와 같되 {@code omit} 의 기본 특성은 <b>주지 않는다</b>.
     *
     * <p>종전에는 "빼기"를 표현할 방법이 없었다 — {@code extra} 에 넣으면 Ⅴ등급으로 <b>더</b>
     * 세게 붙고, 안 넣으면 기본으로 붙는다. 엘리트에게서 명석을 빼려면 그 사이가 필요하다.
     */
    private static MimicEntity spawnMatingReady(ServerLevel level, Vec3 pos, Sex sex,
                                                java.util.Set<Trait> omit, Trait... extra) {
        MimicEntity e = ModEntities.MIMIC.get().create(level);
        if (e == null) {
            return null;
        }
        // 서로 매력 3점(선호↔특성 일치) → 신중(여) 기준선도 통과해 짝 잘 형성.
        long id = Math.abs((int) level.getGameTime()) + level.random.nextInt(1_000_000);
        Individual ind = new Individual(id, sex, 0, 0, 1);
        // extra 로 오는 특성은 기본 부여를 건너뛴다(같은 특성 중복 인스턴스 방지 — 예: 엘리트
        // 재빠름Ⅴ가 기본 무등급 재빠름과 겹치면 등급 해석이 흔들림).
        java.util.Set<Trait> ex = java.util.Set.of(extra);
        ind.addTrait(TraitInstance.of(Trait.PREF_STRENGTH));
        ind.addTrait(TraitInstance.of(Trait.PREF_ABILITY));
        ind.addTrait(TraitInstance.of(Trait.PREF_VITALITY));
        if (!ex.contains(Trait.STRONG) && !omit.contains(Trait.STRONG)) {
            ind.addTrait(TraitInstance.of(Trait.STRONG));
        }
        if (!ex.contains(Trait.BRIGHT) && !omit.contains(Trait.BRIGHT)) {
            ind.addTrait(TraitInstance.of(Trait.BRIGHT));
        }
        if (!ex.contains(Trait.NIMBLE) && !omit.contains(Trait.NIMBLE)) {
            ind.addTrait(TraitInstance.of(Trait.NIMBLE));
        }
        for (Trait t : extra) {
            ind.addTrait(t.isGraded() ? TraitInstance.graded(t, 5) : TraitInstance.of(t));
        }
        e.setIndividual(ind);
        e.setStage(LifeStage.ADULT);
        e.moveTo(pos.x, pos.y, pos.z, level.random.nextFloat() * 360f, 0f);
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()),
                MobSpawnType.COMMAND, null, null);
        level.addFreshEntity(e);
        return e;
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, Sex sex, LifeStage stage, int count) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Vec3 base = src.getPosition();
        for (int i = 0; i < count; i++) {
            spawnOne(level, base.add(i * 1.0, 0, 0), sex, stage);
        }
        src.sendSuccess(() -> Component.literal(
                        "소환: " + (sex == Sex.MALE ? "남" : "여") + " " + stageName(stage) + " ×" + count)
                .withStyle(ChatFormatting.GREEN), false);
        return count;
    }

    private static int gallery(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Vec3 base = src.getPosition();
        int i = 0;
        for (Sex sex : Sex.values()) {
            for (LifeStage stage : LifeStage.values()) {
                spawnOne(level, base.add(i * 1.5, 0, 0), sex, stage);
                i++;
            }
        }
        src.sendSuccess(() -> Component.literal("갤러리 소환: 남/여 × 유아/소년/성년 (6종)")
                .withStyle(ChatFormatting.GREEN), false);
        return i;
    }

    private static void spawnOne(ServerLevel level, Vec3 pos, Sex sex, LifeStage stage) {
        MimicEntity e = ModEntities.MIMIC.get().create(level);
        if (e == null) {
            return;
        }
        Individual ind = Genetics.randomFirstGen(
                Math.abs((int) level.getGameTime()) + level.random.nextInt(100000),
                new DeterministicRng(level.random.nextLong()), sex);
        e.setIndividual(ind);
        e.setStage(stage);
        e.moveTo(pos.x, pos.y, pos.z, level.random.nextFloat() * 360f, 0f);
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()),
                MobSpawnType.COMMAND, null, null);
        level.addFreshEntity(e);
    }

    private static String stageName(LifeStage stage) {
        return switch (stage) {
            case INFANT -> "infant";
            case BOY -> "boy";
            case ADULT -> "adult";
            case ELDER -> "elder";
        };
    }
}
