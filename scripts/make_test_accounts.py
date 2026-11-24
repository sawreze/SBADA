"""직접 손으로 확인하는 테스트 계정 만들기 (2026-11-25 사용자 요청, 파이썬 표준 라이브러리만)

켜져 있는 서버(메인 8097 → 물품 8099 · 결제 8098)에 실제 API 로 계정 3개와 물건을 만든다.
  tester1 · tester2 · tester3  /  비밀번호 sbada1234  (다른 시연 계정과 같은 로컬 전용 공개 비밀번호)
  - 셋 다 '서울 강남구 역삼동' → 서로 동네지도 · AI 추천에 보인다
  - admin 과 달리 '바로 성사' 가 아니다 → 요청 · 수락 · 결제 · 수령 확인을 내 손으로 끝까지 해 볼 수 있다
  - 온보딩(관심 카테고리)도 미리 끝내 둔다 → 로그인하면 바로 홈
  - 지갑 잔액은 실행할 때마다 100,000원(가상 돈)으로 되돌린다

다시 실행하면: 계정은 그대로 쓰고, 물건이 2개보다 적게 남은 계정만 채운다.
  --새로 : 테스트 계정의 남은 물건을 모두 내리고 처음 상태로 다시 만든다(거래 완료된 물건 정리용)
"""
import json
import os
import struct
import sys
import urllib.error
import urllib.request
import uuid
import zlib

BASE = os.environ.get("BASE", "http://127.0.0.1:8097")
PASSWORD = "sbada1234"  # 로컬 시연 전용(README 에도 공개된 시연 비밀번호와 같음)
REGION = "서울 강남구 역삼동"
FRESH = any(a in ("--새로", "--fresh") for a in sys.argv[1:])

# 계정 · 닉네임 · 관심 카테고리 · 물건(이름, 카테고리, 상태, 바라는 카테고리, 사진 색)
ACCOUNTS = [
    ("tester1", "테스트하나", ["DIGITAL", "HOBBY"], [
        ("[T1] 블루투스 스피커", "DIGITAL", "GOOD", ["HOBBY", "SPORTS"], (52, 120, 220)),
        ("[T1] 캠핑 의자", "SPORTS", "LIKE_NEW", ["DIGITAL"], (40, 160, 90)),
        ("[T1] 소설책 세트", "BOOK", "GOOD", ["HOBBY"], (200, 140, 60)),
    ]),
    ("tester2", "테스트둘", ["SPORTS", "BOOK"], [
        ("[T2] 무선 이어폰", "DIGITAL", "LIKE_NEW", ["SPORTS"], (230, 90, 120)),
        ("[T2] 보드게임", "HOBBY", "GOOD", ["DIGITAL", "BOOK"], (150, 80, 200)),
        ("[T2] 요가 매트", "SPORTS", "NEW", ["DIGITAL"], (60, 190, 180)),
    ]),
    ("tester3", "테스트셋", ["DIGITAL"], [
        ("[T3] 전기 포트", "APPLIANCE", "GOOD", ["DIGITAL"], (240, 180, 40)),
        ("[T3] 스탠드 조명", "FURNITURE", "LIKE_NEW", ["DIGITAL"], (110, 110, 120)),
    ]),
]


def api(method, path, token=None, body=None, raw=None, ctype="application/json; charset=utf-8"):
    data = raw if raw is not None else (None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8"))
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", ctype)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            text = r.read().decode("utf-8")
            return r.status, (json.loads(text) if text else {})
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(text)
        except ValueError:
            return e.code, {"raw": text[:200]}
    except urllib.error.URLError as e:
        sys.exit(f"서버에 연결하지 못했어요({BASE}): {e.reason}\n→ '스바다_실행.command' 로 서버를 먼저 켜 주세요.")


def png(rgb):
    """256×256 PNG: 색 바탕 + 가운데 흰 네모(목록에서 물건 사진처럼 보이게). 외부 라이브러리 없이"""
    w = h = 256
    rows = []
    for y in range(h):
        row = bytearray(b"\x00")
        for x in range(w):
            inner = 72 <= x < 184 and 72 <= y < 184
            row += bytes((255, 255, 255) if inner else rgb)
        rows.append(bytes(row))

    def chunk(kind, data):
        c = kind + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b""))


def login_or_signup(username, nickname):
    s, d = api("POST", "/api/auth/login", body={"username": username, "password": PASSWORD, "rememberMe": True})
    if s == 200:
        return d["accessToken"], d["user"]["id"], False
    s, d = api("POST", "/api/auth/signup",
               body={"username": username, "password": PASSWORD, "nickname": nickname, "region": REGION})
    if s != 201:
        sys.exit(f"{username} 가입 실패({s}): {d.get('message', d)}")
    s, d = api("POST", "/api/auth/login", body={"username": username, "password": PASSWORD, "rememberMe": True})
    if s != 200:
        sys.exit(f"{username} 로그인 실패({s}): {d.get('message', d)}")
    return d["accessToken"], d["user"]["id"], True


def my_items(token):
    s, d = api("GET", "/api/me/items", token)
    if s != 200:
        sys.exit(f"내 물건 목록을 못 받았어요({s}): {d.get('message', d)} → 물품 서버(8099)가 켜져 있는지 확인")
    return d.get("items", [])


def new_item(token, title, category, condition, wish, rgb):
    boundary = "----tester" + uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo.png\"\r\n"
            f"Content-Type: image/png\r\n\r\n").encode() + png(rgb) + f"\r\n--{boundary}--\r\n".encode()
    s, img = api("POST", "/api/images", token, raw=body, ctype="multipart/form-data; boundary=" + boundary)
    if s != 201:
        sys.exit(f"사진 올리기 실패({s}): {img.get('message', img)}")
    s, an = api("POST", "/api/ai/analyze", token, {"imageId": img["id"], "category": category, "condition": condition})
    if s != 200:
        sys.exit(f"AI 가격 분석 실패({s}): {an.get('message', an)}")
    s, item = api("POST", "/api/items", token, {
        "title": title, "description": "직접 확인용 테스트 물건이에요. (테스트계정_만들기.command 가 만듦)",
        "category": category, "condition": condition, "imageIds": [img["id"]],
        "analysisId": an["analysisId"], "wishCategories": wish})
    if s != 201:
        sys.exit(f"물건 등록 실패({s}): {item.get('message', item)}")
    return item


def main():
    print(f"=== 직접 확인용 테스트 계정 ({BASE}){' — 새로 만들기' if FRESH else ''} ===")
    s, h = api("GET", "/api/health")
    if s != 200 or h.get("itemService") != "UP":
        sys.exit(f"서버 상태가 이상해요: {h}\n→ '스바다_실행.command' 로 메인 · 물품 · 결제 서버를 켜 주세요.")

    for username, nickname, wanted, items in ACCOUNTS:
        token, uid, created = login_or_signup(username, nickname)
        print(f"\n■ {username} ({nickname}, id {uid}) — {'새로 가입' if created else '이미 있음'}")
        # 동네 · 관심 카테고리(온보딩 완료 처리)
        api("PUT", "/api/me/region", token, {"region": REGION})
        s, _ = api("PUT", "/api/me/preferences", token, {"wantedCategories": wanted, "keywords": [], "priceMin": None, "priceMax": None})
        print(f"  동네 {REGION} · 관심 {', '.join(wanted)} {'✅' if s == 200 else '⚠️ ' + str(s)}")
        # 지갑(가상 돈) 100,000원으로
        s, w = api("POST", "/api/wallet/sandbox/reset", token)
        if s == 200 and isinstance(w.get("balance"), int):
            print(f"  지갑 {w['balance']:,}원(가상)")
        else:
            print(f"  ⚠️ 지갑 초기화 실패({s}) — 결제 서버(8098) 확인")
        # 물건
        mine = [it for it in my_items(token) if it.get("status") == "AVAILABLE"]
        if FRESH:
            for it in my_items(token):
                if it.get("status") == "AVAILABLE":
                    api("DELETE", f"/api/items/{it['id']}", token)
            mine = []
            print("  남은 물건을 모두 내렸어요(--새로)")
        if len(mine) >= 2:
            print(f"  물건 {len(mine)}개 그대로: " + ", ".join(f"#{it['id']} {it['title']}" for it in mine))
            continue
        have = {it["title"] for it in mine}
        for title, cat, cond, wish, rgb in items:
            if title in have:
                continue
            it = new_item(token, title, cat, cond, wish, rgb)
            print(f"  + #{it['id']} {it['title']} — AI 가격 {it.get('price', 0):,}원")

    print("\n완료! 시뮬레이터에서 tester1 / tester2 / tester3 (비밀번호 sbada1234) 로 로그인해 확인하세요.")
    print("확인 순서: docs/handoff/09_직접_확인_체크리스트.md")


if __name__ == "__main__":
    main()
