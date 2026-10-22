"""
스바다 Gemma 4 '사진 → 가격' QLoRA 학습 (RTX 3060 12GB 기준)
  python training/train_vision.py            (training\\train-vision.bat 이 부른다)

목표: 물건 사진만 보고 매기는 가격의 오차를 줄인다(앱에서 사진 찍고 'AI 가격 분석' 하는 상황과 같은 질문).
데이터: 실제 상품 사진 + 시세 가격
  - 기본: 서버 시연 데이터 V4 상품 50개 (사진 sbada-server/src/main/resources/seed/pNN.jpg, 가격은 V4 SQL)
  - 추가: training/photos/ 에 사진 + training/photos/prices.csv (파일,이름,카테고리,상태,가격) 를 넣으면 같이 학습
  - 8개는 학습에 쓰지 않고 '학습 전 · 후 가격 오차' 비교에만 쓴다
  - 학습용 사진은 뒤집기 · 살짝 자르기로 3배로 늘린다(같은 물건을 조금 다르게 찍은 것처럼)
결과: training/out-vision/lora (LoRA 어댑터) + logs 에 학습 전 · 후 오차

메모리(12GB) 절약:
  - 원본은 4비트(NF4), 사진 · 소리 인코더는 4비트로 바꾸지 않음(사진 인식이 흐려지지 않게)
  - 소리 인코더는 쓰지 않으므로 GPU 에서 지움
  - prepare_model_for_kbit_training 을 쓰지 않음(큰 임베딩을 fp32 로 늘려 12GB 를 넘기던 원인)
"""
import argparse
import csv
import json
import math
import random
import re
import time
from pathlib import Path

import torch
from PIL import Image, ImageEnhance, ImageOps
from peft import LoraConfig, get_peft_model
from transformers import AutoProcessor, BitsAndBytesConfig, Trainer, TrainingArguments

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
SEED_DIR = ROOT / "sbada-server/src/main/resources/seed"
V4_SQL = ROOT / "sbada-server/src/main/resources/db/migration/V4__replace_demo_items.sql"
EXTRA_DIR = HERE / "photos"
LORA_SUFFIX = ("q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj")
SKIP = ("vision", "audio")


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


# ---------------------------------------------------------------- 데이터
def photo_prompt():
    """서버 AiPrompt.build(힌트 없음) 과 같은 질문 = MakeDataset 가 만든 가격 질문에서 '판매자 정보' 줄만 뺀 것"""
    with open(HERE / "data/train.jsonl", encoding="utf-8") as f:
        for line in f:
            row = json.loads(line)
            if row["task"] == "price":
                return row["messages"][0]["content"].split("판매자가 알려 준 정보")[0]
    raise SystemExit("training/data/train.jsonl 이 없어요. 먼저 MakeDataset.java 를 실행하세요(train-vision.bat 이 함)")


def load_items():
    """[(사진 경로, 정답 JSON 문자열, 이름, 가격)]"""
    answers = {}
    for name in ("train.jsonl", "eval.jsonl"):
        with open(HERE / "data" / name, encoding="utf-8") as f:
            for line in f:
                row = json.loads(line)
                if row["task"] == "price":
                    a = json.loads(row["messages"][1]["content"])
                    answers[(a["title"], a["condition"])] = a
    sql = V4_SQL.read_text(encoding="utf-8")
    pat = re.compile(r"\('(p\d{2})', '[a-z]+', '((?:[^']|'')*)', '((?:[^']|'')*)',\s*'([A-Z_]+)', '([A-Z_]+)', "
                     r"(\d+), (\d+), (\d+), ([0-9.]+),")
    items = []
    for pid, title, desc, cat, cond, price, pmin, pmax, conf in pat.findall(sql):
        title = title.replace("''", "'")
        a = answers.get((title, cond)) or {
            "title": title, "category": cat, "condition": cond, "price_krw": int(price), "price_min_krw": int(pmin),
            "price_max_krw": int(pmax), "confidence": float(conf), "reason": "", "tags": [title]}
        img = SEED_DIR / f"{pid}.jpg"
        if img.exists():
            items.append((img, json.dumps(a, ensure_ascii=False), title, int(price)))
    # 사용자가 추가한 사진(선택)
    csv_path = EXTRA_DIR / "prices.csv"
    if csv_path.exists():
        with open(csv_path, encoding="utf-8-sig") as f:
            for r in csv.DictReader(f):
                img = EXTRA_DIR / r["파일"].strip()
                if not img.exists():
                    log(f"  (건너뜀) 사진 없음: {img.name}")
                    continue
                p = int(str(r["가격"]).replace(",", "").strip())
                a = {"title": r["이름"].strip(), "category": r.get("카테고리", "ETC").strip() or "ETC",
                     "condition": r.get("상태", "GOOD").strip() or "GOOD", "price_krw": p,
                     "price_min_krw": int(p * 0.8), "price_max_krw": int(p * 1.2), "confidence": 0.8,
                     "reason": f"'{r['이름'].strip()}'의 한국 중고 거래가를 반영했어요.", "tags": [r["이름"].strip()]}
                items.append((img, json.dumps(a, ensure_ascii=False), a["title"], p))
    return items


def variants(img):
    """학습용 사진 3장: 원본 · 좌우 뒤집기 · 살짝 확대 + 밝기"""
    w, h = img.size
    crop = img.crop((int(w * 0.06), int(h * 0.06), int(w * 0.94), int(h * 0.94))).resize((w, h))
    return [img, ImageOps.mirror(img), ImageEnhance.Brightness(crop).enhance(1.12)]


# ---------------------------------------------------------------- 모델
def load_model(model_id):
    from transformers import AutoModelForImageTextToText
    bnb = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4", bnb_4bit_use_double_quant=True,
                             bnb_4bit_compute_dtype=torch.bfloat16,
                             llm_int8_skip_modules=["vision_tower", "audio_tower", "embed_vision", "embed_audio",
                                                    "lm_head"])
    model = AutoModelForImageTextToText.from_pretrained(model_id, quantization_config=bnb, device_map={"": 0},
                                                        dtype=torch.bfloat16)
    # 소리 인코더는 이번 학습에 안 쓰므로 GPU 메모리에서 뺀다
    for parent in (model, getattr(model, "model", None)):
        for name in ("audio_tower", "embed_audio"):
            if parent is not None and getattr(parent, name, None) is not None:
                setattr(parent, name, None)
                log(f"  소리 인코더 제거: {name}")
    torch.cuda.empty_cache()
    log(f"  GPU 메모리 사용: {torch.cuda.memory_allocated() / 2**30:.1f} GB")
    return model


def lora_targets(model):
    names = [n for n, m in model.named_modules()
             if n.split(".")[-1] in LORA_SUFFIX and isinstance(m, torch.nn.Linear) and not any(k in n for k in SKIP)]
    if not names:
        raise SystemExit("LoRA 를 붙일 층을 찾지 못했어요")
    log(f"  LoRA 대상 층 {len(names)}개 (예: {names[0]})")
    return names


def user_msg(prompt):
    return [{"role": "user", "content": [{"type": "image"}, {"type": "text", "text": prompt}]}]


def encode(proc, img, prompt, answer):
    """질문(사진 + 글)은 labels=-100, 답(JSON)만 학습"""
    msgs = user_msg(prompt)
    full = msgs + [{"role": "assistant", "content": [{"type": "text", "text": answer}]}]
    t_prompt = proc.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True)
    t_full = proc.apply_chat_template(full, tokenize=False)
    enc = proc(text=t_full, images=[img], return_tensors="pt")
    n_prompt = proc(text=t_prompt, images=[img], return_tensors="pt")["input_ids"].shape[1]
    labels = enc["input_ids"].clone()
    labels[:, :n_prompt] = -100
    enc["labels"] = labels
    if "pixel_values" in enc:
        enc["pixel_values"] = enc["pixel_values"].to(torch.bfloat16)
    return dict(enc)


def first_json(text):
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        return None
    try:
        return json.loads(m.group(0))
    except json.JSONDecodeError:
        return None


@torch.no_grad()
def evaluate(model, proc, items, prompt, label):
    model.eval()
    errs, ok = [], 0
    for img_path, answer, title, price in items:
        img = Image.open(img_path).convert("RGB")
        text = proc.apply_chat_template(user_msg(prompt), tokenize=False, add_generation_prompt=True)
        enc = proc(text=text, images=[img], return_tensors="pt").to(model.device)
        if "pixel_values" in enc:
            enc["pixel_values"] = enc["pixel_values"].to(torch.bfloat16)
        out = model.generate(**enc, max_new_tokens=200, do_sample=False, use_cache=True)
        got = first_json(proc.decode(out[0][enc["input_ids"].shape[1]:], skip_special_tokens=True))
        pred = got.get("price_krw") if got else None
        if isinstance(pred, (int, float)) and pred > 0:
            ok += 1
            e = abs(math.log(pred / price))
            errs.append(e)
            log(f"  [{label}] {title}: 정답 {price:,}원 / 예측 {int(pred):,}원 ({(pred / price - 1) * 100:+.0f}%)")
        else:
            log(f"  [{label}] {title}: 정답 {price:,}원 / 예측 실패(JSON 아님)")
    mean = sum(errs) / len(errs) if errs else float("nan")
    log(f"[{label}] JSON 성공 {ok}/{len(items)} · 평균 가격 오차 ±{(math.exp(mean) - 1) * 100:.0f}% "
        f"(|log 비율| {mean:.3f})")
    model.train()
    return mean


# ---------------------------------------------------------------- 실행
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="google/gemma-4-E4B-it")
    ap.add_argument("--epochs", type=float, default=3)
    ap.add_argument("--eval-n", type=int, default=8, help="학습에 안 쓰고 비교에만 쓸 상품 수")
    ap.add_argument("--skip-before", action="store_true", help="학습 전 평가 생략(빨리)")
    args = ap.parse_args()
    random.seed(7)

    prompt = photo_prompt()
    items = load_items()
    random.shuffle(items)
    eval_items, train_items = items[:args.eval_n], items[args.eval_n:]
    log(f"데이터: 사진 {len(items)}개 → 학습 {len(train_items)}개(×3 = {len(train_items) * 3}장) · 비교용 {len(eval_items)}개")

    log("모델 불러오는 중…")
    proc = AutoProcessor.from_pretrained(args.model)
    model = load_model(args.model)
    lora = LoraConfig(r=8, lora_alpha=16, lora_dropout=0.05, bias="none", task_type="CAUSAL_LM",
                      target_modules=lora_targets(model))
    model = get_peft_model(model, lora)
    model.print_trainable_parameters()

    if not args.skip_before:
        log("학습 전 평가(비교용 사진으로 가격 맞히기)…")
        before = evaluate(model, proc, eval_items, prompt, "학습 전")
    else:
        before = float("nan")

    log("학습 데이터 만드는 중…")
    train_ds = []
    for img_path, answer, _, _ in train_items:
        for v in variants(Image.open(img_path).convert("RGB")):
            train_ds.append(encode(proc, v, prompt, answer))
    random.shuffle(train_ds)
    log(f"  {len(train_ds)}개, 평균 길이 {sum(d['input_ids'].shape[1] for d in train_ds) / len(train_ds):.0f} 토큰")

    out = HERE / "out-vision"
    trainer = Trainer(
        model=model,
        args=TrainingArguments(
            output_dir=str(out / "checkpoints"), num_train_epochs=args.epochs, per_device_train_batch_size=1,
            gradient_accumulation_steps=8, learning_rate=2e-4, lr_scheduler_type="cosine", warmup_steps=2,
            logging_steps=2, save_strategy="no", bf16=True, optim="paged_adamw_8bit", report_to=[],
            gradient_checkpointing=True, gradient_checkpointing_kwargs={"use_reentrant": False},
            remove_unused_columns=False, dataloader_pin_memory=False),
        train_dataset=train_ds,
        data_collator=lambda feats: feats[0],
    )
    log(f"학습 시작: {len(train_ds)}장 × {args.epochs:g}번 = 약 {math.ceil(len(train_ds) * args.epochs / 8)} 단계")
    trainer.train()
    model.save_pretrained(out / "lora")
    proc.save_pretrained(out / "lora")
    log(f"LoRA 저장: {out / 'lora'}")

    log("학습 후 평가…")
    after = evaluate(model, proc, eval_items, prompt, "학습 후")
    log("=" * 60)
    log(f"결과: 평균 가격 오차  학습 전 ±{(math.exp(before) - 1) * 100:.0f}%  →  학습 후 ±{(math.exp(after) - 1) * 100:.0f}%"
        if not math.isnan(before) else f"결과: 학습 후 평균 가격 오차 ±{(math.exp(after) - 1) * 100:.0f}%")
    (out / "result.json").write_text(json.dumps({"before_log_err": before, "after_log_err": after,
                                                 "eval": [t for _, _, t, _ in eval_items]}, ensure_ascii=False),
                                     encoding="utf-8")


if __name__ == "__main__":
    main()
