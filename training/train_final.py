"""
스바다 Gemma 4 최종 QLoRA 학습: 사용자 가격표(JSON 100개) + 상품 사진 50장
  python training/train_final.py          (training\\finetune-and-apply.bat 이 부른다)

데이터
  1) training/data/user_prices.json  [{"id","name","description","price"}]  ← 사용자가 준비한 시세표(글)
     질문 = 서버가 보내는 가격 질문 + '판매자가 알려 준 정보: 이름=…'(앱에서 이름을 적었을 때와 같은 형식)
  2) 시연 상품 사진 50장(seed/pNN.jpg) + 가격   질문 = 사진 + 서버 가격 질문(힌트 없음 = 사진만 찍었을 때)
  - 비교용(학습에 안 씀): 글 10개 + 사진 5장 → 학습 전 · 후 평균 가격 오차 비교
답(JSON)은 짧게(이유 한 문장 · 태그 2개) → 앱에서 답이 빨리 나오게(서버 90초 제한)
결과: training/out-final/lora  →  serve_lora.py 가 띄워서 서버가 사용
"""
import argparse
import json
import math
import random
import re
import sys
from pathlib import Path

import torch
from PIL import Image
from peft import LoraConfig, get_peft_model
from transformers import AutoProcessor, Trainer, TrainingArguments

sys.path.insert(0, str(Path(__file__).resolve().parent))
from train_vision import (first_json, load_items, load_model, log, lora_targets, photo_prompt, user_msg,  # noqa: E402
                          variants)

HERE = Path(__file__).resolve().parent

# 이름 · 설명의 낱말로 카테고리를 정한다(앱 카테고리 코드)
CAT_WORDS = [
    ("BOOK", ["참고서", "입문서", "단어장", "서적", "만화책"]),
    ("SPORTS", ["농구공", "축구공", "요가", "덤벨", "스케이트보드", "헬멧", "라켓", "줄넘기", "등산", "캠핑"]),
    ("HOBBY", ["보드게임", "레고", "블록", "스위치 게임", "게임 컨트롤러", "스케치북", "물감", "필름 카메라"]),
    ("FASHION", ["후드티", "청바지", "패딩", "운동화"]),
    ("ACCESSORY", ["백팩", "모자", "손목시계", "지갑", "선글라스", "목도리", "캐리어", "목베개", "파우치", "카메라 가방"]),
    ("FURNITURE", ["선반", "의자", "테이블", "거울", "벽시계", "독서등", "스탠드", "거치대", "건조대"]),
    ("APPLIANCE", ["선풍기", "주전자", "토스터", "가습기", "전구", "프라이팬", "도시락", "밀폐용기", "드리퍼", "텀블러",
                   "공구", "드라이버", "타이머", "라디오"]),
]


def category_of(name, desc):
    """이름만 본다(설명의 '스탠드 포함' 같은 말에 끌려가지 않게). 안 맞으면 디지털기기"""
    for code, words in CAT_WORDS:
        if any(w in name for w in words):
            return code
    return "DIGITAL"


def condition_of(desc):
    if "미사용" in desc or "새 상품" in desc:
        return "LIKE_NEW"
    if any(w in desc for w in ("교체 필요", "마모 있음", "흠집 있음", "사용감 있음")):
        return "FAIR" if "교체 필요" in desc else "GOOD"
    return "GOOD"


def short_answer(title, category, condition, price, reason, tags):
    p = int(price)
    return json.dumps({"title": title, "category": category, "condition": condition, "price_krw": p,
                       "price_min_krw": int(round(p * 0.85 / 1000) * 1000) or p,
                       "price_max_krw": int(round(p * 1.15 / 1000) * 1000) or p, "confidence": 0.8,
                       "reason": reason[:60], "tags": tags[:2]}, ensure_ascii=False)


def user_rows(prompt):
    """사용자 가격표 → [(None, 질문, 답, 이름, 가격)]"""
    path = HERE / "data" / "user_prices.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    rows = []
    for d in data:
        name, desc, price = d["name"].strip(), d.get("description", "").strip(), int(d["price"])
        cat, cond = category_of(name, desc), condition_of(desc)
        q = prompt + f"판매자가 알려 준 정보(가능하면 따른다): 이름={name} \n"
        first = desc.split(",")[0].strip() if desc else name
        a = short_answer(name, cat, cond, price, f"{first} 중고 시세를 반영했어요.", [name, cat])
        rows.append((None, q, a, name, price))
    return rows


def photo_rows(prompt):
    """시연 상품 사진 → [(사진, 질문, 답, 이름, 가격)]"""
    rows = []
    for img, answer, title, price in load_items():
        a = json.loads(answer)
        reason = (a.get("reason") or "").split(".")[0][:50] or f"{title} 중고 시세를 반영했어요"
        rows.append((img, prompt, short_answer(title, a["category"], a["condition"], price, reason + ".",
                                               (a.get("tags") or [title])), title, price))
    return rows


def encode(proc, img, q, answer):
    content = ([{"type": "image"}] if img is not None else []) + [{"type": "text", "text": q}]
    msgs = [{"role": "user", "content": content}]
    full = msgs + [{"role": "assistant", "content": [{"type": "text", "text": answer}]}]
    t_prompt = proc.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True)
    t_full = proc.apply_chat_template(full, tokenize=False)
    kw = {"images": [img]} if img is not None else {}
    enc = proc(text=t_full, return_tensors="pt", **kw)
    n_prompt = proc(text=t_prompt, return_tensors="pt", **kw)["input_ids"].shape[1]
    labels = enc["input_ids"].clone()
    labels[:, :n_prompt] = -100
    enc["labels"] = labels
    if "pixel_values" in enc:
        enc["pixel_values"] = enc["pixel_values"].to(torch.bfloat16)
    return dict(enc)


@torch.no_grad()
def evaluate(model, proc, rows, label):
    model.eval()
    groups = {"글": [], "사진": []}
    for img_path, q, _, title, price in rows:
        img = Image.open(img_path).convert("RGB") if img_path is not None else None
        content = ([{"type": "image"}] if img is not None else []) + [{"type": "text", "text": q}]
        text = proc.apply_chat_template([{"role": "user", "content": content}], tokenize=False,
                                        add_generation_prompt=True)
        enc = proc(text=text, return_tensors="pt", **({"images": [img]} if img is not None else {})).to(model.device)
        if "pixel_values" in enc:
            enc["pixel_values"] = enc["pixel_values"].to(torch.bfloat16)
        out = model.generate(**enc, max_new_tokens=160, do_sample=False, use_cache=True)
        got = first_json(proc.decode(out[0][enc["input_ids"].shape[1]:], skip_special_tokens=True))
        pred = got.get("price_krw") if got else None
        kind = "사진" if img is not None else "글"
        if isinstance(pred, (int, float)) and pred > 0:
            groups[kind].append(abs(math.log(pred / price)))
            log(f"  [{label}·{kind}] {title}: 정답 {price:,}원 / 예측 {int(pred):,}원 ({(pred / price - 1) * 100:+.0f}%)")
        else:
            groups[kind].append(math.log(3))   # 답을 못 내면 '3배 틀림'으로 계산
            log(f"  [{label}·{kind}] {title}: 정답 {price:,}원 / 예측 실패")
    res = {}
    for k, v in groups.items():
        if v:
            m = sum(v) / len(v)
            res[k] = (math.exp(m) - 1) * 100
            log(f"[{label}] {k} {len(v)}개 평균 가격 오차 ±{res[k]:.0f}%")
    model.train()
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="google/gemma-4-E4B-it")
    ap.add_argument("--epochs", type=float, default=2)
    ap.add_argument("--skip-before", action="store_true")
    args = ap.parse_args()
    random.seed(11)

    prompt = photo_prompt()
    texts, photos = user_rows(prompt), photo_rows(prompt)
    random.shuffle(texts)
    random.shuffle(photos)
    ev = texts[:10] + photos[:5]
    tr_text, tr_photo = texts[10:], photos[5:]
    log(f"데이터: 가격표 {len(texts)}개(학습 {len(tr_text)} · 비교 10) + 사진 {len(photos)}장(학습 {len(tr_photo)}×2 · 비교 5)")

    log("모델 불러오는 중…")
    proc = AutoProcessor.from_pretrained(args.model)
    model = load_model(args.model)
    model = get_peft_model(model, LoraConfig(r=16, lora_alpha=32, lora_dropout=0.05, bias="none",
                                             task_type="CAUSAL_LM", target_modules=lora_targets(model)))
    model.print_trainable_parameters()

    before = {} if args.skip_before else evaluate(model, proc, ev, "학습 전")

    log("학습 데이터 만드는 중…")
    ds = [encode(proc, None, q, a) for _, q, a, _, _ in tr_text]
    for img_path, q, a, _, _ in tr_photo:
        for v in variants(Image.open(img_path).convert("RGB"))[:2]:
            ds.append(encode(proc, v, q, a))
    random.shuffle(ds)
    steps = math.ceil(len(ds) * args.epochs / 8)
    log(f"학습 시작: {len(ds)}개 × {args.epochs:g}번 = {steps} 단계")

    out = HERE / "out-final"
    trainer = Trainer(
        model=model,
        args=TrainingArguments(
            output_dir=str(out / "checkpoints"), num_train_epochs=args.epochs, per_device_train_batch_size=1,
            gradient_accumulation_steps=8, learning_rate=2e-4, lr_scheduler_type="cosine", warmup_steps=2,
            logging_steps=1, save_strategy="no", bf16=True, optim="paged_adamw_8bit", report_to=[],
            gradient_checkpointing=True, gradient_checkpointing_kwargs={"use_reentrant": False},
            remove_unused_columns=False, dataloader_pin_memory=False),
        train_dataset=ds,
        data_collator=lambda feats: feats[0],
    )
    trainer.train()
    model.save_pretrained(out / "lora")
    proc.save_pretrained(out / "lora")
    log(f"LoRA 저장: {out / 'lora'}")

    after = evaluate(model, proc, ev, "학습 후")
    log("=" * 60)
    for k in ("글", "사진"):
        if k in after:
            b = f"±{before[k]:.0f}%" if k in before else "-"
            log(f"결과 [{k}] 평균 가격 오차  학습 전 {b}  →  학습 후 ±{after[k]:.0f}%")
    (out / "result.json").write_text(json.dumps({"before": before, "after": after}, ensure_ascii=False),
                                     encoding="utf-8")
    (out / "DONE").write_text("ok", encoding="utf-8")


if __name__ == "__main__":
    main()
