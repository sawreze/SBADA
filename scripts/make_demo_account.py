"""시연용 계정 만들기 — 만든 기능을 모든 경우(상태)까지 한 계정에서 보여 주기 (2026-11-25 사용자 요청, 파이썬 표준 라이브러리만)

켜져 있는 서버(메인 8097 → 물품 8099 · 결제 8098, Kafka)에 실제 API 로 만든다. 서버 규칙을 그대로 거치므로 화면에 보이는 상태가 진짜다.
  시연 계정 : demo@sbada.test       / sbada1234  (닉네임 '쓰바다 시연', 서울 강남구 역삼동)
  상대 계정 : neighbor1~3@sbada.test / sbada1234  (이웃 하늘 · 이웃 바다 · 이웃 숲)
  (로컬 시연 전용 공개 비밀번호, .test 는 실제로 안 쓰이는 예약 도메인)

다시 실행하면 예전 시연 계정은 '지난 시연' 으로 바꿔 두고(남은 물건은 내림) 같은 이메일로 깨끗하게 새로 만든다.
  → 메인 서버가 dev 프로필(스바다_실행.command)로 켜져 있어야 한다(시연 도구 /api/demo/** 는 dev 에서만 켜짐)

만드는 경우(교환 20건, demo 기준) — 채팅 탭에서 하나씩 열어 보면 된다
  [받은 요청]   A1 수락 대기(내 물건이 더 쌈 → '추가요금 요청' 가능)   A2 수락 대기(내 물건이 더 비쌈 → '추가요금 요청' 흐림)
  [보낸 요청]   B  상대 수락 대기(요청 취소 가능)
  [추가요금]    C1 내가 요청 → 상대 답 기다림     C2 상대가 요청 → 내가 '수락하고 결제' / '거절'
                C3 내 요청이 거절됨 → '다시 선택'  C4 상대 요청을 수락함 → 내가 '결제하기'   C5 상대가 수락 → 상대 결제 대기
  [배송]        D1 막 성사(운송장 둘 다 없음 → 내가 입력해 보기)   D2 내 운송장만 입력   D3 상대 운송장만 입력(내가 입력하면 배송 시작)
                D5 추가요금 결제 확인 · 보관 중 + 배송 시작
  [완료]        E1 거래 완료 · 상대만 후기(내가 후기 쓰기)   E2 서로 후기 완료   E3 추가요금 정산(내 지갑 +) · 서로 후기
  [기간 만료]   F1 7일 지나 취소(물건 다시 교환 가능)        F2 추가요금 결제 후 만료 → 전액 환불(내 지갑)
  [끝난 요청]   G1 내가 거절   G2 상대가 취소   G3 상대가 거절
  + 받은 후기 3개(프로필 ★), 찜(받은 찜 알림 · 내 관심목록), 채팅 대화, 지갑 잔액, 교환 가능한 물건 2개(새로 요청 받아 보기용)
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("BASE", "http://127.0.0.1:8097")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PASSWORD = "sbada1234"  # 로컬 시연 전용(README 에도 공개된 시연 비밀번호와 같음)
REGION = "서울 강남구 역삼동"
SEED_SQL = os.path.join(ROOT, "sbada-server/src/main/resources/db/migration/V11__seoul_items.sql")
SEED_DIR = os.path.join(ROOT, "sbada-server/src/main/resources/seed")

DEMO = ("demo", "쓰바다 시연", ["DIGITAL", "HOBBY", "FASHION"])
NEIGHBORS = [("neighbor1", "이웃 하늘", ["DIGITAL"]), ("neighbor2", "이웃 바다", ["HOBBY"]),
             ("neighbor3", "이웃 숲", ["FURNITURE"])]

# 경우 · 상대(0~2) · 내 물건 가격대 · 상대 물건 가격대 · 누가 요청하나(P=상대가 나에게, D=내가 상대에게)
#   cheap/pricey: 추가요금 규칙(싼 물건 주인만 요청)이 맞게 되도록 고른다. free: 아무거나
SCENARIOS = [
    ("A1", 0, "cheap", "pricey", "P"), ("A2", 1, "pricey", "cheap", "P"), ("B", 2, "free", "free", "D"),
    ("C1", 0, "cheap", "pricey", "P"), ("C2", 1, "pricey", "cheap", "D"), ("C3", 2, "cheap", "pricey", "P"),
    ("C4", 0, "pricey", "cheap", "D"), ("C5", 1, "cheap", "pricey", "P"),
    ("D1", 2, "free", "free", "P"), ("D2", 0, "free", "free", "D"), ("D3", 1, "free", "free", "P"),
    ("D5", 2, "cheap", "pricey", "P"),
    ("E1", 0, "free", "free", "D"), ("E2", 1, "free", "free", "P"), ("E3", 2, "cheap", "pricey", "P"),
    ("F1", 0, "free", "free", "P"), ("F2", 1, "pricey", "cheap", "D"),
    ("G1", 2, "free", "free", "P"), ("G2", 0, "free", "free", "P"), ("G3", 1, "free", "free", "D"),
]
LABEL = {
    "A1": "받은 요청 · 추가요금 요청 가능", "A2": "받은 요청 · 추가요금 요청 불가", "B": "보낸 요청 · 수락 대기",
    "C1": "추가요금 요청함 · 답 대기", "C2": "추가요금 요청 받음 · 수락/거절", "C3": "추가요금 거절됨 · 다시 선택",
    "C4": "추가요금 수락함 · 내가 결제", "C5": "상대가 추가요금 수락 · 상대 결제 대기",
    "D1": "성사 · 운송장 입력 전", "D2": "내 운송장만 입력", "D3": "상대 운송장만 입력", "D5": "추가요금 보관 중 · 배송 시작",
    "E1": "거래 완료 · 내가 후기 쓸 차례", "E2": "거래 완료 · 서로 후기", "E3": "추가요금 정산 · 거래 완료",
    "F1": "기간 만료 · 거래 취소", "F2": "추가요금 결제 후 기간 만료 · 전액 환불",
    "G1": "내가 거절", "G2": "상대가 취소", "G3": "상대가 거절",
}


# ───────────────────────── HTTP ─────────────────────────
def api(method, path, token=None, body=None, raw=None, ctype="application/json; charset=utf-8", timeout=120):
    data = raw if raw is not None else (None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8"))
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", ctype)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
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


class Fail(Exception):
    pass


def must(expect, result, what):
    s, d = result
    if s != expect:
        raise Fail(f"{what} 실패({s}): {d.get('message', d) if isinstance(d, dict) else d}")
    return d


# ───────────────────────── 계정 ─────────────────────────
class User:
    def __init__(self, username, nickname, wanted):
        self.username, self.nickname, self.wanted = username, nickname, wanted
        self.email = f"{username}@sbada.test"
        self.token = None
        self.id = None

    def login(self):
        s, d = api("POST", "/api/auth/login", body={"email": self.email, "password": PASSWORD, "rememberMe": True})
        if s != 200:
            return False
        self.token, self.id = d["accessToken"], d["user"]["id"]
        return True

    def retire_if_exists(self):
        """예전 시연 계정이 있으면: 남은(교환 가능한) 물건을 내리고 '지난 시연' 계정으로 바꿔 이메일을 비운다"""
        if not self.login():
            return False
        s, d = api("GET", "/api/me/items", self.token)
        for it in (d.get("items", []) if s == 200 else []):
            if it.get("status") == "AVAILABLE":
                api("DELETE", f"/api/items/{it['id']}", self.token)
        s, d = api("POST", "/api/demo/retire-me", self.token)
        if s == 404:
            sys.exit("메인 서버에 시연 도구가 없어요(404).\n→ 새 코드 + dev 프로필로 켜야 해요: '스바다_실행.command' 를 다시 실행한 뒤 이 스크립트를 다시 실행해 주세요.")
        if s != 200:
            sys.exit(f"예전 시연 계정({self.email}) 정리 실패({s}): {d.get('message', d)}")
        return True

    def signup(self):
        must(201, api("POST", "/api/auth/signup", body={"email": self.email, "username": None, "password": PASSWORD,
                                                       "nickname": self.nickname, "region": REGION}), f"{self.email} 가입")
        if not self.login():
            raise Fail(f"{self.email} 로그인 실패")
        api("PUT", "/api/me/region", self.token, {"region": REGION})
        api("PUT", "/api/me/preferences", self.token,
            {"wantedCategories": self.wanted, "keywords": [], "priceMin": None, "priceMax": None})
        s, w = api("POST", "/api/wallet/sandbox/reset", self.token)
        if s != 200:
            print(f"  ⚠️ {self.nickname} 지갑 초기화 실패({s}) — 결제 서버(8098) 확인")


# ───────────────────────── 물건 ─────────────────────────
def catalog():
    """V11 서울 상품 200개(사진 seed/r001~r200.jpg)에서 이름 · 설명 · 카테고리 · 상태 · 시세를 읽는다"""
    text = open(SEED_SQL, encoding="utf-8").read()
    pat = re.compile(r"\('(r\d{3})', '[^']*', '((?:[^']|'')*)', '((?:[^']|'')*)', '([A-Z_]+)', '([A-Z_]+)', (\d+)")
    seen, out = set(), []
    for img, title, desc, cat, cond, price in pat.findall(text):
        title = title.replace("''", "'")
        if title in seen or not os.path.exists(os.path.join(SEED_DIR, img + ".jpg")):
            continue
        seen.add(title)
        out.append({"img": img, "title": title, "desc": desc.replace("''", "'"), "cat": cat, "cond": cond, "price": int(price)})
    return sorted(out, key=lambda x: x["price"])


def new_item(user, spec):
    with open(os.path.join(SEED_DIR, spec["img"] + ".jpg"), "rb") as f:
        photo = f.read()
    boundary = "----demo" + uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{spec['img']}.jpg\"\r\n"
            f"Content-Type: image/jpeg\r\n\r\n").encode() + photo + f"\r\n--{boundary}--\r\n".encode()
    img = must(201, api("POST", "/api/images", user.token, raw=body, ctype="multipart/form-data; boundary=" + boundary),
               "사진 올리기")
    # analysisId 없이 보내면 물품 서버가 같은 사진으로 AI 가격 분석을 한 뒤 등록한다
    item = must(201, api("POST", "/api/items", user.token, {
        "title": spec["title"], "description": spec["desc"] + " (시연용 물건)", "category": spec["cat"],
        "condition": spec["cond"], "imageIds": [img["id"]], "wishCategories": []}, timeout=180), "물건 등록")
    return item


# ───────────────────────── 교환 동작 ─────────────────────────
def request(by, target, offered, msg):
    return must(201, api("POST", "/api/exchanges", by.token,
                         {"targetItemId": target["id"], "offeredItemId": offered["id"], "message": msg}), "교환 요청")


def act(user, ex, path, body=None, what=None):
    return must(200, api("POST", f"/api/exchanges/{ex['id']}{path}", user.token, body if body is not None else {}),
                what or path)


def get_ex(user, ex):
    return must(200, api("GET", f"/api/exchanges/{ex['id']}", user.token), "교환 조회")


def say(user, ex, text):
    room = ex.get("chatRoomId")
    if room:
        api("POST", f"/api/chats/{room}/messages", user.token, {"content": text})


def ship(user, ex, invoice):
    return act(user, ex, "/shipment", {"carrierCode": "TEST", "invoiceNo": invoice}, "운송장 입력")


def review(user, ex, rating, text):
    return must(201, api("POST", f"/api/exchanges/{ex['id']}/review", user.token, {"rating": rating, "content": text}),
                "후기")


def demo_tool(user, ex, what):
    return must(200, api("POST", f"/api/demo/exchanges/{ex['id']}/{what}", user.token, {}), "시연 도구 " + what)


def extra_amount(cheap_item, pricey_item):
    diff = pricey_item["price"] - cheap_item["price"]
    return max(1000, min(1_000_000, (diff // 2 // 1000) * 1000 or 1000))


def pay_and_wait(payer, ex, amount):
    """시연 결제(가상 돈, 지갑에서) → 결제 서버가 Kafka 로 알림 → 메인 서버가 교환을 성사시킨다"""
    must(200, api("POST", "/api/payments/escrow", payer.token,
                  {"exchangeId": ex["id"], "amount": amount, "method": "WALLET"}), "추가요금 시연 결제")
    for _ in range(40):
        cur = get_ex(payer, ex)
        if cur["status"] == "ACCEPTED":
            return cur
        time.sleep(1)
    raise Fail("결제는 됐는데 40초 안에 교환이 성사되지 않았어요 → 허브(Kafka)가 켜져 있는지 확인(Kafka_설치_실행.command)")


def run_scenario(key, demo, nb, mine, theirs):
    """mine: 시연 계정 물건, theirs: 상대 물건. 경우마다 마지막 상태까지 만든다"""
    p_req = dict((s[0], s[4]) for s in SCENARIOS)[key] == "P"
    if p_req:
        ex = request(nb, mine, theirs, f"안녕하세요! {theirs['title']}(이)랑 {mine['title']} 바꾸실래요?")
        receiver, requester = demo, nb
    else:
        ex = request(demo, theirs, mine, f"{theirs['title']} 아직 있나요? 제 {mine['title']}(이)랑 바꾸고 싶어요")
        receiver, requester = nb, demo
    cheap, pricey = (mine, theirs) if mine["price"] < theirs["price"] else (theirs, mine)
    amt = extra_amount(cheap, pricey)

    if key in ("A1", "A2", "B"):
        say(requester, ex, "사진보다 실물이 더 깨끗해요 🙂")
        if key == "A1":
            say(demo, ex, "오 좋네요! 조금만 생각해 볼게요.")
    elif key in ("C1", "C3", "C5", "D5", "E3"):          # 내가 받은 사람 · 내 물건이 더 쌈 → 내가 추가요금 요청
        act(demo, ex, "/extra-request", {"amount": amt}, "추가요금 요청")
        if key == "C3":
            act(nb, ex, "/extra-request/decline", what="추가요금 거절")
        elif key in ("C5", "D5", "E3"):
            act(nb, ex, "/extra-request/accept", what="추가요금 수락")
        if key in ("D5", "E3"):
            pay_and_wait(nb, ex, amt)
        if key == "E3":
            ship(demo, ex, "3030303031")
            ship(nb, ex, "3030303032")
            demo_tool(demo, ex, "deliver-now")
            review(nb, ex, 5, "추가요금까지 깔끔하게 정산됐어요. 물건도 설명 그대로예요!")
            review(demo, ex, 5, "빠르게 보내 주셔서 감사해요 😊")
    elif key in ("C2", "C4", "F2"):                       # 상대가 받은 사람 · 상대 물건이 더 쌈 → 상대가 추가요금 요청
        act(nb, ex, "/extra-request", {"amount": amt}, "추가요금 요청")
        if key in ("C4", "F2"):
            act(demo, ex, "/extra-request/accept", what="추가요금 수락")
        if key == "F2":
            pay_and_wait(demo, ex, amt)
            demo_tool(demo, ex, "expire-now")
    elif key in ("D1", "D2", "D3", "E1", "E2", "F1"):     # 바로 수락 → 배송 단계
        act(receiver, ex, "/accept", what="수락")
        say(nb, ex, "수락 감사해요! 오늘 택배 보낼게요 📦")
        if key == "D2":
            ship(demo, ex, "2020202021")
        elif key == "D3":
            ship(nb, ex, "2020202031")
        elif key in ("E1", "E2"):
            ship(demo, ex, "1010101011" if key == "E1" else "1010101021")
            ship(nb, ex, "1010101012" if key == "E1" else "1010101022")
            demo_tool(demo, ex, "deliver-now")
            review(nb, ex, 5 if key == "E1" else 4,
                   "약속을 잘 지키셨어요. 또 거래하고 싶어요!" if key == "E1" else "포장이 꼼꼼했어요. 조금 늦었지만 만족해요.")
            if key == "E2":
                review(demo, ex, 5, "친절하게 답해 주셨어요 👍")
        elif key == "F1":
            demo_tool(demo, ex, "expire-now")
    elif key == "G1":
        act(demo, ex, "/reject", what="거절")
    elif key == "G2":
        act(nb, ex, "/cancel", what="요청 취소")
    elif key == "G3":
        act(nb, ex, "/reject", what="거절")
    return get_ex(demo, ex)


# ───────────────────────── 실행 ─────────────────────────
def main():
    print(f"=== 시연용 계정 만들기 ({BASE}) ===")
    s, h = api("GET", "/api/health")
    if s != 200 or h.get("itemService") != "UP":
        sys.exit(f"서버 상태가 이상해요: {h}\n→ '스바다_실행.command' 로 메인 · 물품 · 결제 서버를 켜 주세요.")

    demo = User(*DEMO)
    nbs = [User(*n) for n in NEIGHBORS]
    retired = [u.nickname for u in [demo] + nbs if u.retire_if_exists()]
    if retired:
        print("예전 시연 계정을 '지난 시연' 으로 정리했어요: " + ", ".join(retired))
    for u in [demo] + nbs:
        u.signup()
        print(f"  + {u.email} ({u.nickname}, id {u.id})")

    # 물건: 싼 카테고리(책 · 뷰티 …) / 비싼 카테고리(디지털 · 가전 …) / 중간을 나눠 올린 뒤,
    #       추가요금 규칙(싼 물건 주인만 요청)이 필요한 경우는 'AI 가 매긴 실제 가격' 으로 짝을 고른다
    cat = catalog()
    if len(cat) < 60:
        sys.exit(f"사진 상품 목록이 부족해요({len(cat)}개): {SEED_SQL}")
    cheap_cats = {"BOOK", "BEAUTY", "PLANT", "FASHION", "PET", "KIDS"}
    pricey_cats = {"DIGITAL", "APPLIANCE", "FURNITURE"}
    low = [c for c in cat if c["cat"] in cheap_cats][:12]
    high = [c for c in reversed(cat) if c["cat"] in pricey_cats][:12]
    taken = {c["title"] for c in low + high}
    rest = [c for c in cat if c["title"] not in taken]
    mid = rest[len(rest) // 2 - 15: len(rest) // 2 + 15]
    free_keys = [k for k, _, dk, _, _ in SCENARIOS if dk == "free"]
    need_demo_cheap = sum(1 for s in SCENARIOS if s[2] == "cheap")
    need_demo_pricey = sum(1 for s in SCENARIOS if s[2] == "pricey")

    jobs = []                                  # (누구, 사진 정보, 용도)
    for i, c in enumerate(low):                # 싼 물건: 시연 7 · 이웃 5
        jobs.append((demo, c, "low") if i % 12 < need_demo_cheap + 1 else (nbs[i % 3], c, "low"))
    for i, c in enumerate(high):               # 비싼 물건: 시연 5 · 이웃 7
        jobs.append((demo, c, "high") if i % 12 < need_demo_pricey + 1 else (nbs[i % 3], c, "high"))
    mid_iter = iter(mid)
    for key in free_keys:                      # 가격 상관없는 경우: 그 경우의 이웃 물건 + 내 물건
        p = next(s[1] for s in SCENARIOS if s[0] == key)
        jobs.append((demo, next(mid_iter), "free:" + key))
        jobs.append((nbs[p], next(mid_iter), "free:" + key))
    for _ in range(2):
        jobs.append((demo, next(mid_iter), "spare"))
    for u in nbs:
        jobs.append((u, next(mid_iter), "spare"))

    print(f"\n물건 {len(jobs)}개 올리는 중(사진 + AI 가격 분석, 몇 분 걸려요)…")
    made = []
    for n, (u, spec, use) in enumerate(jobs, 1):
        it = new_item(u, spec)
        made.append((u, it, use))
        print(f"  [{n:>2}/{len(jobs)}] {u.nickname}: {it['title']} — {it.get('price', 0):,}원", flush=True)

    def pool(who_demo, use):
        return [(u, it) for u, it, x in made if x == use and (u is demo) == who_demo]

    demo_low, demo_high = [it for _, it in pool(True, "low")], [it for _, it in pool(True, "high")]
    nb_low, nb_high = pool(False, "low"), pool(False, "high")

    def pick(demo_cheaper):
        """AI 가 매긴 실제 가격으로 규칙에 맞는 짝(내 물건, 이웃, 이웃 물건)을 고른다. 차이가 큰 짝부터"""
        mine_pool, their_pool = (demo_low, nb_high) if demo_cheaper else (demo_high, nb_low)
        best = None
        for d in mine_pool:
            for u, n in their_pool:
                gap = (n["price"] - d["price"]) if demo_cheaper else (d["price"] - n["price"])
                if gap > 0 and (best is None or gap > best[0]):
                    best = (gap, d, u, n)
        if best is None:
            return None
        mine_pool.remove(best[1])
        their_pool.remove((best[2], best[3]))
        return best[1], best[2], best[3]

    items = []
    for key, p, dk, nk, _ in SCENARIOS:
        if dk == "free":
            mine = next(it for u, it, x in made if x == "free:" + key and u is demo)
            theirs = next(it for u, it, x in made if x == "free:" + key and u is not demo)
            items.append((key, nbs[p], mine, theirs))
            continue
        got = pick(dk == "cheap")
        if got is None:
            print(f"  ⚠️ {key}: 가격 조건에 맞는 물건 짝이 없어요(AI 가격이 예상과 달라요) — 건너뜀")
            continue
        items.append((key, got[1], got[0], got[2]))
    my_spares = [it for u, it, x in made if x == "spare" and u is demo]
    nb_spares = [it for u, it, x in made if x == "spare" and u is not demo]

    print("\n교환 20건 만드는 중…")
    ok, failed = 0, []
    for key, nb, mine, theirs in items:
        try:
            ex = run_scenario(key, demo, nb, mine, theirs)
            ok += 1
            print(f"  ✅ {key:<3} {LABEL[key]:<26} {nb.nickname} · 내 '{mine['title']}' ↔ '{theirs['title']}'"
                  f"{' · 추가 ' + format(ex['extraAmount'], ',') + '원' if ex.get('extraAmount') else ''}", flush=True)
        except Fail as e:
            failed.append(key)
            print(f"  ⚠️ {key:<3} {LABEL[key]} — {e}", flush=True)

    # 찜: 이웃이 내 물건을 찜(→ 내 알림), 내가 이웃 물건을 찜(→ 관심목록)
    for u, it in zip(nbs[:2], my_spares):
        api("POST", f"/api/items/{it['id']}/like", u.token)
    for it in nb_spares[:2]:
        api("POST", f"/api/items/{it['id']}/like", demo.token)

    s, w = api("GET", "/api/wallet", demo.token)
    s2, rv = api("GET", f"/api/users/{demo.id}/reviews")
    print("\n완료!")
    print(f"  교환 {ok}/{len(SCENARIOS)}건{' (실패: ' + ', '.join(failed) + ')' if failed else ''}")
    if s2 == 200:
        print(f"  받은 후기 {rv.get('count')}개 · 평균 ★{rv.get('average')}")
    if s == 200 and isinstance(w.get("balance"), int):
        print(f"  지갑 {w['balance']:,}원(가상 돈)")
    print("\n시뮬레이터에서 demo@sbada.test / sbada1234 로 로그인 → 채팅 탭에서 경우별 카드를 확인하세요.")
    print("상대 화면도 보려면 neighbor1@sbada.test · neighbor2@sbada.test · neighbor3@sbada.test (같은 비밀번호)")
    print("설명: docs/handoff/10_시연계정.md")
    if failed or ok < len(SCENARIOS):
        sys.exit(1)


if __name__ == "__main__":
    main()
