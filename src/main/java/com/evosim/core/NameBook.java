package com.evosim.core;

/**
 * 성명 사전 (서양식 First·Middle·Last, 한글 표기). 조합 수 = 100×99×50 ≈ 495,000/성별.
 *
 * <p>규칙: 1세대는 id 시드 결정론 추첨(재로드 불변) · 자식 성씨는 부계 · 미들네임은 25% 확률로
 * honor naming(아들=아버지 first, 딸=어머니 first 계승), 나머지는 랜덤(first와 중복 금지) ·
 * 아내는 혼인 시 남편 성으로 개성(改姓). 순수 함수 — evotest 손계산 대조.
 */
public final class NameBook {

    public static final String[] SURNAMES = {
            "스미스", "밀러", "카터", "리드", "헤이즈", "브룩스", "애쉬포드", "휘틀록", "펜윅", "헤일",
            "베넷", "하퍼", "콜린스", "그레이", "모건", "딕슨", "워커", "페이지", "램버트", "노리스",
            "바클리", "체임버스", "도일", "엘리엇", "포스터", "깁슨", "하딩", "어빙", "젠킨스", "키건",
            "로렌스", "머서", "나이틀리", "오스본", "파커", "퀸시", "램지", "셔우드", "태너", "언더우드",
            "밴스", "월튼", "요크", "지글러", "블랙우드", "크로프트", "던모어", "에버렛", "폴리", "그리핀"};

    public static final String[] MALE = {
            "윌리엄", "헨리", "아서", "에드먼드", "사일러스", "재스퍼", "로완", "펠릭스", "오스카", "휴고",
            "제임스", "존", "로버트", "마이클", "데이비드", "리처드", "조지프", "토머스", "찰스", "대니얼",
            "매튜", "앤서니", "마크", "스티븐", "폴", "앤드루", "조슈아", "케네스", "케빈", "브라이언",
            "에드워드", "로널드", "티모시", "제이슨", "제프리", "라이언", "제이컵", "게리", "니컬러스", "에릭",
            "조너선", "루이스", "래리", "저스틴", "스콧", "브랜던", "벤저민", "새뮤얼", "그레고리", "프랭크",
            "알렉산더", "패트릭", "잭", "데니스", "제리", "타일러", "에런", "머독", "더글러스", "네이선",
            "피터", "재커리", "카일", "노아", "이선", "루커스", "메이슨", "로건", "오언", "리엄",
            "케일럽", "아이작", "리바이", "마일스", "그레이슨", "콜린", "브로디", "핀리", "데클런", "로리",
            "이언", "셰인", "코너", "트리스탄", "어거스트", "바질", "세드릭", "다리우스", "에머리", "플로이드",
            "길버트", "해럴드", "아이버", "줄리언", "클라크", "레너드", "몬티", "나이절", "올리버", "퍼시"};

    public static final String[] FEMALE = {
            "엠마", "앨리스", "클라라", "바이올렛", "헤이즐", "노라", "아이비", "애들린", "마고", "실비",
            "메리", "퍼트리샤", "제니퍼", "린다", "엘리자베스", "바버라", "수전", "제시카", "세라", "캐런",
            "낸시", "리사", "베티", "마거릿", "샌드라", "애슐리", "킴벌리", "에밀리", "도나", "미셸",
            "캐럴", "어맨다", "도러시", "멀리사", "데버라", "스테퍼니", "리베카", "셜리", "신시아", "앤절라",
            "루비", "그레이스", "스칼릿", "오로라", "데이지", "릴리", "로즈", "포피", "아멜리아", "샬럿",
            "소피아", "이저벨라", "미아", "테스", "에벌린", "애비게일", "엘라", "매디슨", "루나", "카밀라",
            "페넬로피", "레일라", "빅토리아", "매들린", "오드리", "브루클린", "베일리", "클레어", "스카이", "젬마",
            "프레야", "이모겐", "플로렌스", "베아트리스", "서맨사", "조지아", "해리엇", "로절린드", "실리아", "다프네",
            "엘로이즈", "페이스", "글로리아", "헬레나", "아이린", "조앤", "캐서린", "로레인", "미리엄", "나탈리",
            "오팔", "펄", "리타", "셀마", "테레사", "우나", "베라", "위니프레드", "이본", "젤다"};

    /** honor naming 확률(자식 미들 = 부모 first 계승) — 혈통 서사 발생 장치. */
    public static final double HONOR_RATE = 0.25;

    private NameBook() {
    }

    /** 시드 결정론 인덱스 — 음수 시드도 안전. 같은 시드는 언제나 같은 이름(재로드 불변). */
    private static int idx(long seed, int salt, int size) {
        long h = seed * 0x9E3779B97F4A7C15L + salt * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        return (int) Math.floorMod(h, size);
    }

    public static String surname(long seed) {
        return SURNAMES[idx(seed, 1, SURNAMES.length)];
    }

    public static String given(long seed, Sex sex, int salt) {
        String[] pool = sex == Sex.MALE ? MALE : FEMALE;
        return pool[idx(seed, salt, pool.length)];
    }

    /** first — salt 2. */
    public static String first(long seed, Sex sex) {
        return given(seed, sex, 2);
    }

    /** middle — salt 3, first와 겹치면 한 칸 옆(중복 금지). honor 판정(salt 4)은 호출부 규칙. */
    public static String middle(long seed, Sex sex, String firstName) {
        String[] pool = sex == Sex.MALE ? MALE : FEMALE;
        int i = idx(seed, 3, pool.length);
        if (pool[i].equals(firstName)) {
            i = (i + 1) % pool.length;
        }
        return pool[i];
    }

    /** honor naming 판정 — 시드 결정론(25%). */
    public static boolean honor(long seed) {
        return idx(seed, 4, 100) < (int) (HONOR_RATE * 100);
    }
}
