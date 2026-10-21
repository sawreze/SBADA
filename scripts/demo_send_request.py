"""시연 도우미(맥·리눅스·윈도우 공통, 파이썬 표준 라이브러리만 사용).
준호(junho)가 민지(minji)의 물건에 '바로 바꾸기' 요청을 보낸다. 이미 대기 중인 조합(409)은 건너뛴다."""
import json, math, os, sys, urllib.error, urllib.request

BASE = os.environ.get("BASE", "http://127.0.0.1:8097")
FROM, TO = (sys.argv[1:3] + ["junho", "minji"][len(sys.argv[1:3]):])[:2]


def api(method, path, token=None, body=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read().decode("utf-8"))


def login(u):
    return api("POST", "/api/auth/login", body={"username": u, "password": "sbada1234"})


sender, receiver = login(FROM), login(TO)
tok = sender["accessToken"]
targets = [i for i in api("GET", "/api/items?limit=100")["items"]
           if i["owner"]["id"] == receiver["user"]["id"] and i["status"] == "AVAILABLE"]
mine = [i for i in api("GET", "/api/me/items", tok)["items"] if i["status"] == "AVAILABLE"]
if not targets or not mine:
    sys.exit("교환할 물건이 없습니다.")
pairs = sorted(((t, m) for t in targets for m in mine), key=lambda p: abs(math.log(p[1]["price"] / p[0]["price"])))
for t, m in pairs:
    try:
        r = api("POST", "/api/exchanges", tok, {"targetItemId": t["id"], "offeredItemId": m["id"], "message": "시연용 요청이에요!"})
        print(f"완료: {sender['user']['nickname']}의 '{m['title']}'({m['price']:,}원) → "
              f"{receiver['user']['nickname']}의 '{t['title']}'({t['price']:,}원) 교환 요청 #{r['id']} {r['status']}")
        print(f"{receiver['user']['nickname']}의 에뮬레이터에 알림이 갑니다.")
        sys.exit(0)
    except urllib.error.HTTPError as e:
        print(f"건너뜀: '{m['title']}' → '{t['title']}' : {e.read().decode('utf-8')}")
sys.exit("보낼 수 있는 조합이 없습니다(모두 대기 중).")
