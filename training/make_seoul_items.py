"""
서울 상품 200개 추가(2026-10-22) — 서울 25개 구에 고르게(구마다 새 동네 2곳 × 4개 = 8개)
  python -I training/make_seoul_items.py
하는 일
  1) 기존 전국 상품(V9) 의 이름 · 카테고리 · 시세(상태별 가격)를 읽어 200개를 고르고, 상태 · 설명 · 가격을 새로 정한다
  2) 사진 200장을 PC GPU 의 SDXL-Turbo 로 만든다(AI 생성 이미지 — 실제 그 상품을 찍은 사진이 아님, 시연용)
     sbada-server/src/main/resources/seed/r001.jpg ~ r200.jpg (이미 있으면 건너뜀 → 직접 찍은 사진으로 바꿔 넣어도 됨)
  3) V11__seoul_items.sql 작성(판매자 50명 sw001~ · 상품 200 · 희망 카테고리 · 사진 행). 이미 있으면 새로 쓰지 않는다
  4) 앱 지도 좌표용 목록을 training/data/seoul_items_200.json 에 남긴다
모델: stabilityai/sdxl-turbo (Stability AI 비상업 연구 라이선스 — 수업 · 시연용)
"""
import json
import random
import re
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "sbada-server/src/main/resources"
SEED = RES / "seed"
V9 = RES / "db/migration/V9__nationwide_items.sql"
V11 = RES / "db/migration/V11__seoul_items.sql"
CATALOG = Path(__file__).resolve().parent / "data/items500_photo.json"
OUT = Path(__file__).resolve().parent / "data/seoul_items_200.json"

# 구마다 새 동네 2곳(V9 에 이미 있는 동네와 겹치지 않게) — 좌표는 앱 지도(lib/map/regions.dart)에도 같은 값
DONGS = [
    ("종로구", "평창동", 37.6130, 126.9740), ("종로구", "사직동", 37.5760, 126.9690),
    ("중구", "명동", 37.5636, 126.9850), ("중구", "회현동", 37.5580, 126.9780),
    ("용산구", "한남동", 37.5340, 127.0020), ("용산구", "이태원동", 37.5345, 126.9940),
    ("성동구", "행당동", 37.5580, 127.0330), ("성동구", "옥수동", 37.5410, 127.0170),
    ("광진구", "구의동", 37.5440, 127.0880), ("광진구", "화양동", 37.5460, 127.0710),
    ("동대문구", "회기동", 37.5900, 127.0560), ("동대문구", "전농동", 37.5800, 127.0530),
    ("중랑구", "상봉동", 37.5960, 127.0860), ("중랑구", "묵동", 37.6130, 127.0780),
    ("성북구", "정릉동", 37.6060, 127.0050), ("성북구", "안암동", 37.5860, 127.0290),
    ("강북구", "수유동", 37.6380, 127.0230), ("강북구", "번동", 37.6330, 127.0370),
    ("도봉구", "쌍문동", 37.6480, 127.0310), ("도봉구", "방학동", 37.6640, 127.0360),
    ("노원구", "중계동", 37.6450, 127.0750), ("노원구", "공릉동", 37.6250, 127.0730),
    ("은평구", "응암동", 37.5990, 126.9180), ("은평구", "진관동", 37.6390, 126.9220),
    ("서대문구", "신촌동", 37.5600, 126.9420), ("서대문구", "홍제동", 37.5880, 126.9450),
    ("마포구", "합정동", 37.5490, 126.9130), ("마포구", "상암동", 37.5770, 126.8900),
    ("양천구", "신정동", 37.5180, 126.8580), ("양천구", "신월동", 37.5280, 126.8320),
    ("강서구", "등촌동", 37.5500, 126.8630), ("강서구", "발산동", 37.5580, 126.8380),
    ("구로구", "신도림동", 37.5090, 126.8910), ("구로구", "개봉동", 37.4940, 126.8550),
    ("금천구", "독산동", 37.4700, 126.8970), ("금천구", "시흥동", 37.4510, 126.9020),
    ("영등포구", "당산동", 37.5340, 126.8990), ("영등포구", "문래동", 37.5180, 126.8950),
    ("동작구", "노량진동", 37.5130, 126.9410), ("동작구", "사당동", 37.4850, 126.9790),
    ("관악구", "봉천동", 37.4820, 126.9520), ("관악구", "남현동", 37.4740, 126.9780),
    ("서초구", "서초동", 37.4910, 127.0180), ("서초구", "방배동", 37.4820, 126.9970),
    ("강남구", "삼성동", 37.5140, 127.0560), ("강남구", "대치동", 37.4990, 127.0630),
    ("송파구", "문정동", 37.4860, 127.1220), ("송파구", "가락동", 37.4980, 127.1180),
    ("강동구", "길동", 37.5370, 127.1430), ("강동구", "고덕동", 37.5550, 127.1540),
]
PER_DONG = 4
FACTOR = {"LIKE_NEW": 1.1, "GOOD": 1.0, "FAIR": 0.8}
COND_LABEL = {"LIKE_NEW": "거의 새것", "GOOD": "사용감 적음", "FAIR": "사용감 많음"}
CATS = ["DIGITAL", "APPLIANCE", "FURNITURE", "KIDS", "FASHION", "ACCESSORY", "BEAUTY", "SPORTS", "HOBBY", "BOOK",
        "TICKET", "PET", "PLANT", "ETC"]
PREFS = [(5000, 150000), (10000, 300000), (20000, 600000)]
# V9 와 같은 비밀번호(sbada1234) 해시 — 로컬 시연 전용 판매자 계정
PW_HASH = "$2a$10$jVhnHJVAGjJ8h.q4KJH7D.cJBmkgkBlH8U8RqrUR4gH75an0VrgA2"
SURFACES = ["a light wooden table", "a white desk", "a living room floor", "a gray fabric sofa", "a kitchen counter",
            "a beige carpet", "a dark wooden floor", "a white bed sheet", "a cafe table", "a bookshelf"]
STATE = {"LIKE_NEW": "clean and almost new", "GOOD": "in good used condition",
         "FAIR": "used with visible signs of wear"}


def log(m):
    print(f"[{time.strftime('%H:%M:%S')}] {m}", flush=True)


def sql_tuples(text):
    """V9 의 "    ('q001', ...)," 줄을 값 목록으로 읽는다('' = 따옴표 하나)"""
    rows = []
    for line in text.splitlines():
        if not line.startswith("    ('q") or line.count(",") < 10:
            continue
        body = line.strip().rstrip(",;").removeprefix("(").removesuffix(")")
        vals, cur, i, q = [], "", 0, False
        while i < len(body):
            c = body[i]
            if q:
                if c == "'" and i + 1 < len(body) and body[i + 1] == "'":
                    cur += "'"; i += 1
                elif c == "'":
                    q = False
                else:
                    cur += c
            elif c == "'":
                q = True
            elif c == ",":
                vals.append(cur.strip()); cur = ""
            else:
                cur += c
            i += 1
        vals.append(cur.strip())
        if len(vals) == 14:
            rows.append(vals)
    return rows


def won(x, step=500):
    return max(step, int(round(x / step)) * step)


def q(s):
    return s.replace("'", "''")


def plan():
    v9 = sql_tuples(V9.read_text(encoding="utf-8"))
    en = {it["title"]: it["en"] for it in json.loads(CATALOG.read_text(encoding="utf-8"))}
    by_title = {}
    phrases = {k: set() for k in FACTOR}
    for img, user, title, desc, cat, cond, price, pmin, pmax, conf, reason, hours, views, _ in v9:
        base = int(price) / FACTOR[cond]
        by_title.setdefault(title, {"title": title, "cat": cat, "base": base, "first": desc.split(". ")[0] + ".",
                                    "en": en.get(title, title)})
        parts = desc.split(". ")
        if len(parts) >= 3:
            phrases[cond].add(parts[1].strip() + ".")
    phrases = {k: sorted(v) for k, v in phrases.items()}
    rnd = random.Random(20261022)
    titles = sorted(by_title)
    picked = rnd.sample(titles, len(DONGS) * PER_DONG)
    sellers, items = [], []
    for d, (gu, dong, lat, lng) in enumerate(DONGS, 1):
        user = f"sw{d:03d}"
        pmin, pmax = rnd.choice(PREFS)
        sellers.append({"user": user, "nick": f"{dong} 이웃", "region": f"서울 {gu} {dong}", "lat": lat, "lng": lng,
                        "pmin": pmin, "pmax": pmax, "days": rnd.randint(30, 400)})
        for k in range(PER_DONG):
            n = len(items) + 1
            t = by_title[picked[n - 1]]
            cond = rnd.choice(list(FACTOR))
            price = won(t["base"] * FACTOR[cond], 1000 if t["base"] >= 20000 else 500)
            wish = rnd.sample([c for c in CATS if c != t["cat"]], rnd.choice([1, 2]))
            items.append({
                "img": f"r{n:03d}", "user": user, "title": t["title"], "category": t["cat"], "condition": cond,
                "description": f"{t['first']} {rnd.choice(phrases[cond])} 물물교환 환영해요.",
                "price": price, "price_min": won(price * 0.82), "price_max": won(price * 1.18),
                "confidence": round(rnd.uniform(0.62, 0.83), 2),
                "reason": f"'{t['title']}'의 국내 중고 거래가와 {COND_LABEL[cond]} 상태를 반영한 가격이에요.",
                "hours": rnd.randint(1, 720), "views": rnd.randint(3, 60), "wish": wish, "en": t["en"]})
    return sellers, items


def photos(items):
    todo = [it for it in items if not (SEED / f"{it['img']}.jpg").exists()]
    log(f"사진 {len(items)}장 중 새로 만들 것 {len(todo)}장")
    if not todo:
        return
    import torch
    from diffusers import AutoPipelineForText2Image
    log("SDXL-Turbo 불러오는 중…")
    pipe = AutoPipelineForText2Image.from_pretrained("stabilityai/sdxl-turbo", torch_dtype=torch.float16,
                                                     variant="fp16").to("cuda")
    pipe.set_progress_bar_config(disable=True)
    t0 = time.time()
    for n, it in enumerate(todo, 1):
        rnd = random.Random("seoul-" + it["img"])
        prompt = (f"a smartphone photo of {it['en']}, {STATE[it['condition']]}, secondhand item for sale, "
                  f"placed on {rnd.choice(SURFACES)}, natural daylight, realistic, sharp focus, no people, no text")
        g = torch.Generator("cuda").manual_seed(int(it["img"][1:]) * 104729 + 17)
        img = pipe(prompt=prompt, num_inference_steps=2, guidance_scale=0.0, width=512, height=512,
                   generator=g).images[0]
        img.save(SEED / f"{it['img']}.jpg", "JPEG", quality=85)
        if n % 25 == 0 or n == len(todo):
            left = (time.time() - t0) / n * (len(todo) - n)
            log(f"  {n}/{len(todo)} 완료 (남은 시간 약 {left / 60:.1f}분)")
    del pipe
    torch.cuda.empty_cache()


def write_sql(sellers, items):
    if V11.exists():
        log(f"{V11.name} 이 이미 있어서 새로 쓰지 않음(이미 적용된 마이그레이션은 고치면 안 됨)")
        return
    users = ",\n".join(
        f"    ('{s['user']}', '{PW_HASH}', '{q(s['nick'])}', '{q(s['region'])}', true, {s['pmin']}, {s['pmax']}, "
        f"dateadd('DAY', -{s['days']}, current_timestamp))" for s in sellers)
    rows = ",\n".join(
        f"    ('{it['img']}', '{it['user']}', '{q(it['title'])}', '{q(it['description'])}', '{it['category']}', "
        f"'{it['condition']}', {it['price']}, {it['price_min']}, {it['price_max']}, {it['confidence']}, "
        f"'{q(it['reason'])}', {it['hours']}, {it['views']}, {(SEED / (it['img'] + '.jpg')).stat().st_size})"
        for it in items)
    wish = ",\n".join(f"    ('{it['img']}', '{c}')" for it in items for c in it["wish"])
    sql = f"""-- 서울 상품 200개 추가 (2026-10-22) — 사용자 요청: 서울에만 고르게 200개 정도
--  - 서울 25개 구 × 새 동네 2곳 × 4개 = 200개(구마다 8개). V9 에 있던 동네와 겹치지 않게 골랐다(서대문구는 이번에 처음)
--  - 동네마다 판매자 계정 1개(sw001~sw050, 비밀번호 sbada1234, 로컬 시연 전용)
--  - 상품 이름 · 카테고리 · 시세는 V9 전국 상품에서 가져오고 상태 · 설명 · 가격(시세 × 상태 좋음 1.1 · 양호 1.0 · 사용감 0.8)은 새로 정함, ai_provider = 'seed'
--  - 사진 seed/r001~r200.jpg = PC GPU 의 SDXL-Turbo 로 만든 AI 생성 이미지(시연용) → NationwideSeedImages 가 업로드 폴더로 복사
--  - 만든 방법: training/make_seoul_items.py (데이터 training/data/seoul_items_200.json)
-- 이미 적용된 뒤에는 이 파일을 고치지 말고 V12__ 으로 새로 만든다.

insert into app_users (username, password_hash, nickname, region, onboarded, price_min, price_max, created_at) values
{users};

create local temporary table seed_items_v11 (
    img varchar(4), username varchar(20), title varchar(60), description varchar(1000),
    category varchar(20), item_condition varchar(20), price int, price_min int, price_max int,
    confidence double, reason varchar(600), hours_ago int, views int, file_size int
);

insert into seed_items_v11 values
{rows};

insert into items (owner_id, title, description, category, item_condition, status, ai_price, price_min, price_max,
                   ai_confidence, ai_reason, ai_provider, view_count, request_count, created_at, updated_at, version)
select u.id, s.title, s.description, s.category, s.item_condition, 'AVAILABLE', s.price, s.price_min, s.price_max,
       s.confidence, s.reason, 'seed', s.views, 0,
       dateadd('HOUR', -s.hours_ago, current_timestamp), dateadd('HOUR', -s.hours_ago, current_timestamp), 0
from seed_items_v11 s
join app_users u on u.username = s.username
order by s.img;

insert into item_wish_categories (item_id, category)
select distinct i.id, w.category
from (values
{wish}
     ) as w(img, category)
join seed_items_v11 s on s.img = w.img
join app_users u on u.username = s.username
join items i on i.owner_id = u.id and i.title = s.title;

insert into item_images (uploader_id, item_id, stored_name, original_name, content_type, size, width, height, sort_order, created_at)
select i.owner_id, i.id, 'seed-' || s.img || '.jpg', s.img || '.jpg', 'image/jpeg', s.file_size, 512, 512, 0, i.created_at
from seed_items_v11 s
join app_users u on u.username = s.username
join items i on i.owner_id = u.id and i.title = s.title
order by s.img;

drop table seed_items_v11;
"""
    V11.write_text(sql, encoding="utf-8")
    log(f"{V11.name} 작성: 판매자 {len(sellers)} · 상품 {len(items)} · 희망 카테고리 {sql.count(chr(10) + '    (' + chr(39) + 'r')}")


def main():
    sellers, items = plan()
    # 같은 판매자가 같은 이름의 물건을 두 개 가지면 (주인, 이름)으로 찾는 SQL 이 헷갈리므로 막는다
    assert len({(it["user"], it["title"]) for it in items}) == len(items)
    OUT.write_text(json.dumps({"sellers": sellers, "items": items}, ensure_ascii=False, indent=1), encoding="utf-8")
    log(f"계획: 판매자 {len(sellers)}명 · 상품 {len(items)}개 → {OUT.name}")
    if "--plan-only" in sys.argv:
        return
    photos(items)
    write_sql(sellers, items)


if __name__ == "__main__":
    sys.exit(main())
