package com.evosim.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 밤 배치 정산 (설계서 §4). 낮엔 수확 카운트만 쌓고, 밤에 가족 단위로 한 번에 정산.
 *
 * <p>순수 함수(§18): 창고 합산(요리 배율) → 우선순위 분배(남편 → 자식(태어난 순서) → 아내) →
 * 굶주림 카운트 → 임계 초과 사망. 실시간 인벤토리·이월 없음.
 */
public final class Feeding {

    /** 굶주림 연속 임계 → 사망 (설계서 §4, 흉년 완충: 즉사 아님). */
    public static final int HUNGER_DEATH = 3;

    private Feeding() {
    }

    /** 생애단계별 1일 기본 소모 (밸런싱 초기값: 성인 1.0 기준). */
    static double baseConsumption(LifeStage stage) {
        return switch (stage) {
            case ADULT -> 1.0;
            case ELDER -> 0.7; // 레거시(구 정산) — 실사용은 FoodEconomy(2.0)
            case BOY -> 0.5;
            case INFANT -> 0.3;
        };
    }

    /** 정산 단위 구성원 — 개체 + 단계 + 오늘 수확/활동량. */
    public static final class Member {
        public final Individual ind;
        public final LifeStage stage;
        public double harvest;   // 오늘 수확량
        public double activity;  // 오늘 활동 가산 소모 (이동·노동·전투)
        public boolean dead;

        public Member(Individual ind, LifeStage stage, double harvest, double activity) {
            this.ind = ind;
            this.stage = stage;
            this.harvest = harvest;
            this.activity = activity;
        }

        /** 하루 소모량 — 기본 + 활동 + 특성(힘센 많이 먹음, 약함 적게 등). */
        public double consumption() {
            double c = baseConsumption(stage) + activity;
            var t = ExpressionResolver.expressedTraits(ind);
            c *= Physique.appetite(ind); // 힘/약 등급 비례(±4%/등급) — v2 costMult와 동일 함수
            if (t.contains(Trait.DILIGENT)) c += 0.1;
            if (t.contains(Trait.LAZY)) c -= 0.1;
            // v2 costMult와 대칭(페널티 특성의 반대급부) — 두 정산계가 같은 방향을 보게 유지
            if (t.contains(Trait.CHILD_AVERSE)) c *= 0.95;
            if (t.contains(Trait.REPRODUCTION_AVERSE)) c *= 0.95;
            if (t.contains(Trait.FRAIL)) c *= 0.95;
            if (t.contains(Trait.SICKLY)) c *= 0.9;
            return Math.max(0.1, c);
        }
    }

    /** 한 가정 (같은 homePos). 분배 순서: 남편 → 자식(태어난 순서) → 아내들. */
    public static final class Household {
        public Member father;
        public final List<Member> children = new ArrayList<>(); // 태어난 순서
        public final List<Member> wives = new ArrayList<>();
    }

    /** 정산 결과. */
    public static final class Result {
        public double storageLeft;
        public final List<Member> fed = new ArrayList<>();
        public final List<Member> starved = new ArrayList<>();
        public final List<Member> died = new ArrayList<>();
    }

    /** 밤 배치 정산 실행 (설계서 §4). */
    public static Result settle(Household h) {
        Result r = new Result();

        // 1) 오늘 수확량 전부 → 가족 창고 합산 (요리사 ×1.2 / 요리치 ×0.8 여기서 적용).
        double storage = 0.0;
        for (Member m : order(h)) {
            storage += m.harvest * Multipliers.storage(m.ind);
        }

        // 2~4) 우선순위 분배: 남편 → 자식(순서) → 아내. 못 먹으면 굶주림+1, 먹으면 0. 임계 초과 사망.
        for (Member m : order(h)) {
            if (m.dead) {
                continue;
            }
            double need = m.consumption();
            if (storage >= need) {
                storage -= need;
                m.ind.setHungerCount(0);
                r.fed.add(m);
            } else {
                m.ind.setHungerCount(m.ind.hungerCount() + 1);
                r.starved.add(m);
                if (m.ind.hungerCount() >= HUNGER_DEATH) {
                    m.dead = true;
                    r.died.add(m);
                }
            }
        }
        r.storageLeft = storage;
        return r;
    }

    /** 분배/합산 순서대로 나열 (남편 → 자식 → 아내). */
    private static List<Member> order(Household h) {
        List<Member> order = new ArrayList<>();
        if (h.father != null) {
            order.add(h.father);
        }
        order.addAll(h.children);
        order.addAll(h.wives);
        return order;
    }
}
