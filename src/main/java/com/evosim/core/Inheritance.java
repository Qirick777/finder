package com.evosim.core;

/**
 * 유산 분배 순수 산식 (봉건 집중 P4). 가구 해체(양친 사망·거주자 0) 시 저장고 식량을 분가한
 * 자식들에게 나눈다 — <b>상속인(장남→장녀)이 대다수(2/3)</b>, 나머지를 타 분가 자식이 균등.
 * 정수 유닛만 이체(L 정수성). 밭은 별도(FarmStore.inherit — 상속인 단독 전부 승계).
 *
 * <p>{@code /evotest caregiving}이 아니라 farm 계열에서 항등식(분배합+잔여=원액)을 대조한다.
 */
public final class Inheritance {

    /** 상속인 몫 비율 — "대다수". */
    public static final double HEIR_FRACTION = 2.0 / 3.0;

    /** 분배 결과 — heir(상속인 몫)·perOther(타 자식 1인 몫)·remainder(폐가 잔여). 합=larder(내림 정수). */
    public record Split(int heir, int perOther, int remainder) {
        public int total(int otherCount) {
            return heir + perOther * otherCount + remainder;
        }
    }

    /**
     * @param larder      해체 가구 저장고(정수 취급 — floor)
     * @param otherCount  상속인 외 분가 생존 자식 수(0이면 상속인 단독)
     */
    public static Split split(double larder, int otherCount) {
        int total = (int) Math.floor(Math.max(0.0, larder));
        int heir = (int) Math.floor(total * HEIR_FRACTION);
        int rest = total - heir;
        if (otherCount <= 0) {
            return new Split(total, 0, 0); // 타 자식 없음 — 상속인 전액
        }
        int perOther = rest / otherCount;
        int remainder = rest - perOther * otherCount;
        return new Split(heir, perOther, remainder);
    }

    private Inheritance() {
    }
}
