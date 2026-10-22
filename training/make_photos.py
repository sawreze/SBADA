"""
전국 상품 500개의 대표 사진을 PC GPU 로 생성(SDXL-Turbo, AI 생성 이미지 — 실제 그 상품을 찍은 사진이 아님)
  python training/make_photos.py          (scripts\\apply-nationwide.bat 이 부른다)
결과
  sbada-server/src/main/resources/seed/q001.jpg ~ q500.jpg  (512×512, 이미 있으면 건너뜀 → 직접 찍은 사진으로 바꿔 넣어도 됨)
  sbada-server/src/main/resources/db/migration/V10__nationwide_photos.sql  (사진 행 · 파일 크기)
모델: stabilityai/sdxl-turbo (Stability AI 비상업 연구 라이선스 — 수업 · 시연용)
"""
import json
import random
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SEED = ROOT / "sbada-server/src/main/resources/seed"
MIG = ROOT / "sbada-server/src/main/resources/db/migration/V10__nationwide_photos.sql"
DATA = Path(__file__).resolve().parent / "data/items500_photo.json"

SURFACES = ["a light wooden table", "a white desk", "a living room floor", "a gray fabric sofa", "a kitchen counter",
            "a beige carpet", "a dark wooden floor", "a white bed sheet"]
STATE = {"LIKE_NEW": "clean and almost new", "GOOD": "in good used condition",
         "FAIR": "used with visible signs of wear"}


def log(m):
    print(f"[{time.strftime('%H:%M:%S')}] {m}", flush=True)


def main():
    items = json.loads(DATA.read_text(encoding="utf-8"))
    todo = [it for it in items if not (SEED / f"{it['img']}.jpg").exists()]
    log(f"사진 {len(items)}개 중 새로 만들 것 {len(todo)}개")
    if todo:
        import torch
        from diffusers import AutoPipelineForText2Image
        log("SDXL-Turbo 불러오는 중(처음이면 약 7GB 다운로드)…")
        pipe = AutoPipelineForText2Image.from_pretrained("stabilityai/sdxl-turbo", torch_dtype=torch.float16,
                                                         variant="fp16").to("cuda")
        pipe.set_progress_bar_config(disable=True)
        t0 = time.time()
        for n, it in enumerate(todo, 1):
            rnd = random.Random(it["img"])
            prompt = (f"a smartphone photo of {it['en']}, {STATE[it['condition']]}, secondhand item for sale, "
                      f"placed on {rnd.choice(SURFACES)}, natural daylight, realistic, sharp focus, no people, no text")
            g = torch.Generator("cuda").manual_seed(int(it["img"][1:]) * 7919)
            img = pipe(prompt=prompt, num_inference_steps=2, guidance_scale=0.0, width=512, height=512,
                       generator=g).images[0]
            img.save(SEED / f"{it['img']}.jpg", "JPEG", quality=85)
            if n % 25 == 0 or n == len(todo):
                left = (time.time() - t0) / n * (len(todo) - n)
                log(f"  {n}/{len(todo)} 완료 (남은 시간 약 {left / 60:.0f}분)")
        del pipe
        torch.cuda.empty_cache()

    # V10: 사진 행(파일이 있는 것만). 물건은 (주인 username, 이름)으로 찾는다
    rows = []
    for it in items:
        f = SEED / f"{it['img']}.jpg"
        if f.exists():
            t = it["title"].replace("'", "''")
            rows.append(f"    ('{it['img']}', '{it['user']}', '{t}', {f.stat().st_size})")
    if MIG.exists():
        log(f"{MIG.name} 이 이미 있어서 새로 쓰지 않음(이미 적용된 마이그레이션은 고치면 안 됨)")
        return
    sql = f"""-- 전국 상품(V9) 500개의 대표 사진 {len(rows)}장 (2026-10-21)
-- 사진 = PC GPU 의 SDXL-Turbo 로 상품 이름(영어 설명)을 보고 만든 AI 생성 이미지(실제 그 상품을 찍은 사진이 아님, 시연용)
-- 파일 classpath:seed/qNNN.jpg → NationwideSeedImages 가 업로드 폴더 seed-qNNN.jpg 로 복사
-- 이미 적용된 뒤에는 이 파일을 고치지 말고 V11__ 으로 새로 만든다.
create local temporary table seed_photos_v10 (img varchar(4), username varchar(20), title varchar(60), file_size int);
insert into seed_photos_v10 values
{(',' + chr(10)).join(rows)};

insert into item_images (uploader_id, item_id, stored_name, original_name, content_type, size, width, height, sort_order, created_at)
select i.owner_id, i.id, 'seed-' || s.img || '.jpg', s.img || '.jpg', 'image/jpeg', s.file_size, 512, 512, 0, i.created_at
from seed_photos_v10 s
join app_users u on u.username = s.username
join items i on i.owner_id = u.id and i.title = s.title
order by s.img;

drop table seed_photos_v10;
"""
    MIG.write_text(sql, encoding="utf-8")
    log(f"{MIG.name} 작성: 사진 행 {len(rows)}개")


if __name__ == "__main__":
    sys.exit(main())
