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
./gradlew evotest --args="traits"       # Phase 1 특성 발동(성별발현/흔적)
./gradlew evotest --args="multiplier"   # Phase 1 배율/매력 손계산 대조
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

검증 하니스: `com.evosim.test.EvoTest`.

Minecraft 표현층 (`com.evosim.mod`):

| 파일 | 역할 |
|---|---|
| `EvoSimMod` | `@Mod` 진입점. `/evotest` 명령어 등록. |
| `EvoTestCommand` | 게임 내 `/evotest` — `EvoTest.runReport()` 를 호출해 채팅 출력(§17). |

## 페이즈 진행 상황

- [x] **Phase 0 — 뼈대**: 데이터 구조, 결정론 난수, 특성 enum + 반발/태그, `/evotest genetics`
- [x] **Phase 1 — 유전 + 특성 발동**
  - [x] ① 발현 판정(성별발현/흔적) + 배율·매력 함수, `/evotest traits` `multiplier`
  - [x] ② 반발 카드(억제유전자) + 흔적 보상 — breed 확장 + 발현 반발 판정, 보유 반발 공존 허용
- [ ] Phase 2 — 마크 소환 + 기본 행동
- [ ] Phase 3 — 생존 루프 (식량·정산·수명·전투)
- [ ] Phase 4 — 사회 (거처·짝짓기·번식·이주)
- [ ] Phase 5 — 관찰 + 밸런싱
- [ ] Phase 6 — 대규모 검증 + 실험
