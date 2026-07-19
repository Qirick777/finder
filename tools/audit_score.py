#!/usr/bin/env python3
"""AUDIT 이정표 채점기 — evosim-events.log의 AUDIT 라인을 파싱해 일차별 목표표와 대조.

사용: python3 audit_score.py <이벤트로그 경로>
출력: 일차별 실측값 표 + 이정표 판정(도달/미달/조기) — AI 검수 루프의 채점 단계.
"""
import sys, re, collections

# 2배속 압축판 창(대기 상수 반감: 성장 0.75/1.25일·쿨다운 1일·승격 2일·확장 6/일·수명 7+3).
# 소득·소모율은 불변이므로 흐름 지배 관문(착공 저축·풀 붕괴)은 원창 유지 — 정직한 혼합 압축.
MILESTONES = [
    # (판정일 하한, 상한, 이름, 판정 함수: rows dict{day: fields} -> True/False/None(미판정))
    (0, 1,  "풀 러시(grass>garden)",
     lambda r, d: r[d]["grass"] > r[d]["garden"] if d in r else None),
    (2, 4,  "1호 밭 착공(plots>=1)",   # 흐름 지배(저축 30 도달 실측 3.2일 — 런1·런3 2회) → 상한 d4
     lambda r, d: r[d]["plots"] >= 1 if d in r else None),
    (1, 3,  "첫 출산 물결(births 누적>=가구 0.6)",
     lambda r, d: sum(r[x]["births"] for x in r if x <= d) >= 0.6 * r[d]["homes"]
     if d in r and r[d]["homes"] > 0 else None),
    (3, 7,  "풀 붕괴(grass<30 & garden 비중>60%)",  # 결정론 풀밭 950그루 표준: 붕괴 실측 d6~7(런3)
     lambda r, d: r[d]["grass"] < 30
     and r[d]["garden"] > 0.6 * max(1e-9, r[d]["grass"] + r[d]["garden"] + r[d]["hunt"])
     if d in r else None),
    (4, 6,  "첫 소작(고용 성립 & 평민 한계 전: landless 저장고>4)",  # 확장 6/일(자금 캡)·성인화 2일 → d5~6
     lambda r, d: (r[d]["tenants_today"] >= 1 or r[d]["tenants_perm"] >= 1)
     and r[d].get("larder_landless", 99) > 4 if d in r else None),
    (3, 6,  "완만한 굶주림(평민 저장고 -0.3~-3/일 & critical<10%)",
     lambda r, d: (d - 1) in r and d in r
     and -3.0 <= r[d].get("larder_landless", 99) - r[d - 1].get("larder_landless", 0) <= -0.3
     and r[d]["critical"] < 0.1 * max(1, r[d]["pop"]) or None),
    (5, 8,  "상시 소작+확장(perm>=1 & top_tiles>=20)",  # 첫 소작 +2일(승격 2일)
     lambda r, d: r[d]["tenants_perm"] >= 1 and r[d]["top_tiles"] >= 20
     if d in r else None),
    (5, 8,  "2호 밭(top_plots>=2)",  # 24성숙(확장 6/일) + 상시 성립 직후
     lambda r, d: r[d]["top_plots"] >= 2 if d in r else None),
    (6, 9,  "출산 재개(당일 births>=1, 소작 존재)",
     lambda r, d: r[d]["births"] >= 1 and r[d]["tenants_perm"] >= 1 if d in r else None),
    (5, 10, "여성당 출산율 1.5+ 진입(누적births/adult_f)",  # 쿨다운 1일·성인화 2일
     lambda r, d: sum(r[x]["births"] for x in r if x <= d)
     >= 1.5 * r[d]["adult_f"] if d in r and r[d].get("adult_f", 0) > 0 else None),
    (7, 10, "왕조 집중(top_tiles>=전체 60% & tenants>=8)",
     lambda r, d: r[d]["tiles"] > 0 and r[d]["top_tiles"] >= 0.6 * r[d]["tiles"]
     and (r[d]["tenants_perm"] + r[d]["tenants_today"]) >= 8 if d in r else None),
    (8, 11, "100명 의존(dyn_deps>=100)",  # 최종 관문 — 목표 d10 중심(±1 창)
     lambda r, d: r[d]["dyn_deps"] >= 100 if d in r else None),
]


def parse(path):
    rows = {}
    for line in open(path, encoding="utf8", errors="replace"):
        if "AUDIT" not in line:
            continue
        kv = dict(re.findall(r"(\w+)=([^\s]+)", line))
        if "day" not in kv:
            continue
        d = int(kv["day"])
        rows[d] = {k: (float(v) if re.match(r"^-?[\d.]+$", v) else v)
                   for k, v in kv.items()}
        rows[d]["day"] = d
    return rows


def main():
    rows = parse(sys.argv[1])
    if not rows:
        print("AUDIT 라인 없음 — 로그 경로/evolog on 확인")
        return
    days = sorted(rows)
    print(f"{'d':>3} {'pop':>4} {'grass':>7} {'garden':>7} {'farmT':>6} {'rent':>6} "
          f"{'plots':>5} {'tiles':>5} {'perm':>4} {'today':>5} {'top':>4} {'deps':>4} "
          f"{'birth':>5} {'crit':>4} {'avgL':>6} {'L무밭':>6} {'L소작':>6} {'L지주':>6}")
    for d in days:
        r = rows[d]
        print(f"d{d:>2} {r['pop']:4.0f} {r['grass']:7.1f} {r['garden']:7.1f} "
              f"{r['farm_tenant']:6.1f} {r['rent']:6.1f} {r['plots']:5.0f} {r['tiles']:5.0f} "
              f"{r['tenants_perm']:4.0f} {r['tenants_today']:5.0f} {r['top_tiles']:4.0f} "
              f"{r['dyn_deps']:4.0f} {r['births']:5.0f} {r['critical']:4.0f} {r['larder_avg']:6.1f} "
              f"{r.get('larder_landless', 0):6.1f} {r.get('larder_tenant', 0):6.1f} "
              f"{r.get('larder_owner', 0):6.1f}")
    print("\n── 이정표 판정 ──")
    last = days[-1]
    for lo, hi, name, judge in MILESTONES:
        verdict = "관측범위 밖"
        if last >= lo:
            hit = None
            for d in range(lo, min(hi, last) + 1):
                v = judge(rows, d)
                if v:
                    hit = d
                    break
            early = None
            for d in range(0, lo):
                v = judge(rows, d)
                if v:
                    early = d
                    break
            if hit is not None:
                verdict = f"도달 d{hit} ✓"
            elif early is not None:
                verdict = f"조기 d{early} (예정 d{lo}~{hi}) ⚠"
            elif last >= hi:
                verdict = f"미달 (d{lo}~{hi} 창 종료) ✗"
            else:
                verdict = f"진행 중 (~d{hi})"
        print(f"  [{'d%d~%d' % (lo, hi):>7}] {name:38} {verdict}")


def leak_check(rows):
    last = max(rows)
    r = rows[last]
    others = r["plots"] - r.get("top_plots", 0)
    if others >= 3:
        print(f"\n⚠ 독립 누수 의심: 왕조 외 밭 {others:.0f}개 — 이벤트 로그의 밭개간 G값 확인 요망")


if __name__ == "__main__":
    try:
        main()
        leak_check(parse(sys.argv[1]))
    except BrokenPipeError:
        pass  # head 등 파이프 조기 종료 — 정상
