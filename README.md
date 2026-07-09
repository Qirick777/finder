# evosim — 마인크래프트 진화 시뮬레이션 (ABM)

마크 껍데기를 쓴 **행위자 기반 진화 시뮬(ABM)**. 엔진 = 유전 + 선택압 + 측정.
설계 원칙과 로드맵은 코딩 지침서(설계서)를 따른다. 핵심 규칙은 **마크에 안 얽힌 순수 함수**로
구현해(§18) 헤드리스 검증·시뮬이 공짜로 따라오게 한다.

> 개발 규칙: **한 페이즈씩.** 각 페이즈 완성 시 그 `/evotest` 검증을 함께 만들고,
> `/evotest all`로 회귀 확인 후 다음으로 넘어간다.

## 모드 정보

- **모드로더**: Forge 1.20.1 (버전 `47.3.0`, `[47,)`)
- **Minecraft**: 1.20.1
- **매핑**: official 1.20.1
- **JDK**: 17 (Forge 1.20.1 요구)
- 모드 ID: `evosim` — 진입점 `com.evosim.mod.EvoSimMod`

Gradle wrapper(`gradlew`, 8.1.1)가 포함되어 있으니 로컬에 gradle 설치 없이 바로 쓸 수 있다.

> ⚠️ **JDK 17 로 실행할 것.** Forge 1.20.1 은 Gradle 8.1.1(래퍼에 고정)을 쓰는데, 이건
> Java 21 에서 안 돈다(`Unsupported class file major version 65`). `java -version` 이 17 인지 확인.
>
> ⚠️ 첫 `./gradlew` 실행 시 ForgeGradle 이 Minecraft/Forge/매핑을 인터넷에서 받는다
> (maven.minecraftforge.net, libraries.minecraft.net). 방화벽으로 막힌 환경에선 셋업이 안 된다.

## 게임에서 실행 (`runClient`)

```bash
./gradlew runClient      # 개발용 클라이언트 실행 (모드 로드됨)
```

인게임 콘솔/채팅에서(오퍼레이터 권한):

```
/evotest all         # 전체 회귀
/evotest genetics    # Phase 0 유전 검증
```

## 검증 실행 — 헤드리스 (`/evotest`, Minecraft 없이)

Phase 0/1 핵심 로직은 마크에 안 얽힌 순수 함수(§18)라 클라이언트 없이 CLI 로 검증된다.
게임 내 `/evotest`와 <b>같은 로직</b>을 호출하므로 결과가 일치한다.

```bash
./gradlew evotest --args="genetics"     # Phase 0 유전 검증
./gradlew evotest --args="traits"       # Phase 1 특성 발동(성별발현/흔적/반발)
./gradlew evotest --args="multiplier"   # Phase 1 배율/매력 손계산 대조
./gradlew evotest --args="simulate"     # Phase 2 헤드리스 다세대 안정성 + 시간대/행동
./gradlew evotest --args="trace"        # Phase 2 하루 행동 타임라인(진단, /evodebug trace)
./gradlew evotest --args="combat"       # Phase 3 전투 3층위 판정(진입/퇴각/복귀)
./gradlew evotest --args="feeding"      # Phase 3 밤 배치 정산(분배/굶주림/사망)
./gradlew evotest --args="lifecycle"    # Phase 3 생애단계 능력 + 여성 페널티
./gradlew evotest --args="lifespan"     # Phase 3 세대 수명 + 상속
./gradlew evotest --args="mating"       # Phase 4 조우·기준선·매력·근친회피
./gradlew evotest --args="settlement"   # Phase 4 거처 배치(거리·비겹침)
./gradlew evotest --args="all"          # 전체 회귀 테스트
```

출력 예 (게임 채팅과 동일):

```
=== 검증 요약 ===
총 5 · ✅ 5 · ❌ 0
=================
[genetics/반발] 기대 반발쌍 동시보유 0 / 실제 0건  ✅
[genetics/개수] 기대 카테고리≤3 · 총≤9 / 실제 최대 카테고리 3 · 최대 총 9  ✅
[genetics/우성] 기대 75±2% / 실제 74.96% (표본 73593)  ✅
[genetics/돌연변이] 기대 2±0.5% / 실제 2.08% (표본 30000)  ✅
[genetics/결정론] 기대 동일 시드 → 동일 결과 / 실제 재현 일치  ✅
```

실패가 있으면 요약 헤더에 `❌:`로 강조되고 CLI 는 종료 코드 1로 끝난다(CI 친화).
게임 내 `/evotest`는 명령 결과값으로 성공 1 / 실패 0을 돌려준다.

## 현재 구조 (Phase 0 — 뼈대)

마크 의존성이 없는 순수 로직(`com.evosim.core`, `com.evosim.test`)과, 그걸 게임에서 호출하는
얇은 표현층(`com.evosim.mod`)으로 분리돼 있다(§18). 표현층만 Minecraft/Forge 를 임포트한다.

순수 로직 (`com.evosim.core`):

| 파일 | 역할 |
|---|---|
| `Sex`, `Category`, `Tag` | 성별 · 특성 카테고리(성향/신체/선호) · 태그(우성/성별발현) |
| `Axis` | 특성 축 + 반발(exclusive) 규칙. 성향·신체는 반발, 선호는 독립(익숙함/다양성만 예외) |
| `Trait` | 특성 마스터 목록(설계서 §14). 축·반발 판정 |
| `TraitInstance` | 개체 보유 특성 = 특성 값 + 태그. `expressedFor(sex)` 성별발현 판정 |
| `Individual` | 개체 데이터(특성·성별·부모ID·homePos·세대·굶주림 카운트) |
| `DeterministicRng` | 시드 고정 결정론 난수 1개 (§17 필수요소 ①) |
| `Genetics` | `breed()` 유전 + 1세대 랜덤 부여 (순수 함수, §18) |
| `BreedStats` | breed() 내부 확률 이벤트 누적(검증용) |
| `ExpressionResolver` | 발현 판정(성별발현 → 반발 카드 무력화) — 저장 안 하고 성별로 재판정 (§2) |
| `Multipliers` | `gather/hunt/storage/charmScore` — 발동 특성만 합연산 (§15) |
| `Schedule` | 하루 시간대(기상→일→배회→밤) — 특성별 기상/취침 오프셋 (§16) |
| `BehaviorDecision` | 행동 결정 = 우선순위 목록(시간대+특성 → 행동, §18) |
| `Simulation` | 헤드리스 다세대 시뮬 — 안정성+분포+결정론 (§17) |
| `Combat` | 전투 3층위 판정(진입/퇴각/복귀) + 감지 범위 (§13-B) |
| `Feeding` | 밤 배치 정산 — 분배(남편>자식>아내)/굶주림/사망 (§4) |
| `SurvivalRules` | 생애단계 능력(전투·채집·이동)·여성 40% 페널티 (§7 §1) |
| `Lifespan` | 세대 기반 수명(손자+자식성년→사망) + 상속(1명분) (§9) |
| `Mating` | 조우 판정(매력≥기준선)·기준선 하락·사별녀 하향 (§10) |
| `Settlement` | 거처 배치 — 이주/애향 거리·비겹침 링 탐색 (§13-D) |
| `Kinship` | 근친 회피(형제·부모자식, 사촌 허용) (§13-E) |

검증 하니스: `com.evosim.test.EvoTest`.

Minecraft 표현층 (`com.evosim.mod`):

| 파일 | 역할 |
|---|---|
| `EvoSimMod` | `@Mod` 진입점. 개체·아이템·명령어 등록. |
| `EvoTestCommand` | 게임 내 `/evotest` — `EvoTest.runReport()` 를 호출해 채팅 출력(§17). |
| `EvoDebugCommand` | 게임 내 `/evodebug trace` — `EvoDebug.trace()` 하루 행동 타임라인(§17). |
| `EvoSimCommand` | 게임 내 `/evosim spawn`·`gallery` — 개체 소환(무대 세팅, §17). |
| `entity.MimicEntity` | 미믹 개체(플레이어 형태) — 성별·생애단계 동기화, 기본 배회 AI. |
| `reg.ModEntities`·`ModItems` | 개체 타입·스폰에그 등록. |
| `client.MimicRenderer`·`MimicClient` | 플레이어 모델 렌더(스티브/알렉스·단계별 크기·유아 비율). |

### 게임에서 개체 소환 (Phase 2b, `runClient` 눈 확인)

- **미믹 스폰 알**: 크리에이티브 "스폰 알" 탭 → 성년 랜덤 성별 소환.
- **미믹 특성 검사봉**("도구" 탭): 미믹 우클릭 → 정보 표시. **쉬프트+스크롤로 모드 전환**:
  특성(발현/흔적/반발/우성 색 구분) / 짝(기준선·선호·가족) / 거처(좌표·동거) / 가족 인벤토리 (§14).
- **`/evosim spawn <male|female> [infant|boy|adult] [수]`**: 지정 소환(단계 생략 시 성년).
- **`/evosim gallery`**: 남/여 × 유아/소년/성년 6종을 한 줄로 → 외형 비교.
- **`/evosim village [쌍수]`**: 매력 맞는 방랑자 남녀를 흩뿌려 소환 → 자기들끼리 짝 형성·거처 정착 관찰.

외형: 여성=알렉스(슬림)·남성=스티브, 유아=아기비율(머리 큼)+작게, 소년=키 작게, 성년=기본.

### 표현층 자동 검증 (`/evostage`, 눈으로 안 봐도 성공/실패)

명령 한 번 → 개체 자동 소환 → 실제 행동을 로그로 관측 → **모든 기대 행동 관측 시 성공, 아니면 실패**.
공간·물리가 필요해 순수 로직으로 못 잡는 표현층(성장·전투)을 명령으로 자동 검증(설계서 §17).

- **`/evostage all`** — 전체 시나리오 순차.
- **`/evostage growth`** — 유아→소년→성년 성장 전환 관측.
- **`/evostage combat_brave`** — 용감 개체가 몬스터에 진입(engage).
- **`/evostage combat_coward`** — 겁쟁이 개체가 몬스터에서 도망(flee).
- **`/evostage combat_retreat`** — 신중 저체력 개체가 퇴각(retreat).
- **`/evostage infant`** — 유아가 전투 불가(tooyoung) + 거의 안 움직임(slow) 관측.
- **`/evostage trait_audit`** — 소환 개체 30마리의 특성 부여 자동 감사(우성비율·발현수·반발위반) → 성공/실패 + 수치.
- **`/evostage mating`** — 매력 맞는 방랑자 남녀가 실제로 짝 성립(mating:pair)하나.
- **`/evostage settlement`** — 여러 쌍 정착 시 거처가 겹치지 않나(settlement:ok).

## 페이즈 진행 상황

- [x] **Phase 0 — 뼈대**: 데이터 구조, 결정론 난수, 특성 enum + 반발/태그, `/evotest genetics`
- [x] **Phase 1 — 유전 + 특성 발동**
  - [x] ① 발현 판정(성별발현/흔적) + 배율·매력 함수, `/evotest traits` `multiplier`
  - [x] ② 반발 카드(억제유전자) + 흔적 보상 — breed 확장 + 발현 반발 판정, 보유 반발 공존 허용
- [~] **Phase 2 — 마크 소환 + 기본 행동**
  - [x] a. 순수 로직: 시간대 스케줄·행동 우선순위·헤드리스 시뮬, `/evotest simulate` `/evodebug trace`
  - [x] b. 마크 표현층: 미믹 개체(플레이어 형태)·스폰에그·`/evosim spawn`·기본 배회 AI (runClient 눈 확인)
        · 외형 구분: 여성=알렉스(슬림)/남성=스티브, 유아=아기비율(머리 큼), 소년=키 작게, 성년=기본
  - [ ] c. (Phase 3와 함께) BehaviorDecision→실제 잔디채집·동물사냥 goal 연동
- [x] **Phase 3 — 생존 루프 (식량·정산·수명·전투)**
  - [x] 순수: 전투 3층위·밤 정산·생애단계 능력·여성 페널티·세대 수명·상속
        (`/evotest combat` `feeding` `lifecycle` `lifespan`)
  - [x] 표현층: 생애단계별 이동속도·전투 성년만·여성 40% 약함·전투 상호교전
  - [x] 자동검증 `/evostage`: growth·combat(진입/도망/퇴각)·infant(전투불가+느림)
  - [x] 특성 검사봉(§14) — 미믹 우클릭으로 특성 확인
  - [ ] (Phase 4와 함께) 밤 정산·수명·상속 in-world goal 연동, 몬스터 야간 사냥, 근친 회피
- [~] **Phase 4 — 사회 (거처·짝짓기·번식·이주)**
  - [x] 순수: 조우/기준선/매력·거처 배치·근친 회피, `/evotest mating` `settlement`
  - [x] 표현층: 방랑자 짝짓기 goal·거처(homePos)·귀환 goal·이주/애향 비겹침 정착
  - [x] 검사봉 스크롤 4모드(특성/짝/거처/가족인벤토리) + 스크롤 네트워크
  - [x] 자동검증 `/evostage mating` `settlement` + 관찰 `/evosim village`
  - [ ] 번식(밤·잉여 임계·쿨다운)·일부다처(상향혼)·경쟁/평화·밤 정산 in-world 연동
- [ ] Phase 5 — 관찰 + 밸런싱
- [ ] Phase 6 — 대규모 검증 + 실험
