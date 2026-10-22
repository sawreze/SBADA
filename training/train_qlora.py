"""
스바다 Gemma 4 QLoRA 학습 (RTX 3060 12GB 기준)
  python training/train_qlora.py --model google/gemma-4-E4B-it
결과: training/out/lora (LoRA 어댑터) · training/out/merged (원본 + LoRA 합친 모델, Ollama 변환용)

- 4비트(NF4)로 원본을 올리고 작은 LoRA(r=8)만 학습 → 12GB 안에서 돈다
- 답(assistant) 부분만 손실을 계산 → 질문 문장을 외우는 데 힘을 쓰지 않는다
- 데이터: training/data/train.jsonl (MakeDataset.java 가 만든 174개 중 157개). 평가: eval.jsonl
- 학습 전 · 후에 평가 데이터로 'JSON 형식이 맞는 비율 · 가격 오차 · 검사 정답률'을 출력한다
"""
import argparse
import json
import math
import re
from pathlib import Path

import torch
from peft import LoraConfig, PeftModel, get_peft_model, prepare_model_for_kbit_training
from transformers import (AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig, DataCollatorForSeq2Seq, Trainer,
                          TrainingArguments)

HERE = Path(__file__).resolve().parent


def load_rows(name):
    with open(HERE / "data" / name, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def load_model(model_id):
    bnb = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4", bnb_4bit_use_double_quant=True,
                             bnb_4bit_compute_dtype=torch.bfloat16)
    try:
        model = AutoModelForCausalLM.from_pretrained(model_id, quantization_config=bnb, device_map="auto",
                                                     dtype=torch.bfloat16)
    except (ValueError, KeyError):
        # 멀티모달(사진+글) 모델이라 CausalLM 으로 안 열리면 이미지-텍스트 클래스로 연다(글만 학습)
        from transformers import AutoModelForImageTextToText
        model = AutoModelForImageTextToText.from_pretrained(model_id, quantization_config=bnb, device_map="auto",
                                                            dtype=torch.bfloat16)
    return model


LORA_SUFFIX = ("q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj")


def lora_targets(model):
    """LoRA 를 붙일 층: '글(언어 모델)' 부분의 일반 Linear 만.
    Gemma 4 는 사진 · 소리 인코더에도 q_proj 같은 같은 이름의 층(Gemma4ClippableLinear)이 있어서
    이름만으로 고르면 PEFT 가 지원하지 않는 층까지 잡혀 오류가 난다 → 종류(nn.Linear)와 이름으로 거른다."""
    skip = ("vision", "audio", "embed_vision", "embed_audio")
    names = [n for n, m in model.named_modules()
             if n.split(".")[-1] in LORA_SUFFIX and isinstance(m, torch.nn.Linear) and not any(k in n for k in skip)]
    if not names:
        raise SystemExit("LoRA 를 붙일 층을 찾지 못했어요(모델 구조 확인 필요)")
    print(f"LoRA 대상 층 {len(names)}개 (예: {names[0]})")
    return names


def encode(tok, row, max_len):
    """질문은 labels=-100(손실 제외), 답만 학습"""
    user, answer = row["messages"][0]["content"], row["messages"][1]["content"]
    prompt = tok.apply_chat_template([{"role": "user", "content": user}], tokenize=False, add_generation_prompt=True)
    p_ids = tok(prompt, add_special_tokens=False)["input_ids"]
    a_ids = tok(answer + tok.eos_token, add_special_tokens=False)["input_ids"]
    ids = (p_ids + a_ids)[:max_len]
    labels = ([-100] * len(p_ids) + a_ids)[:max_len]
    return {"input_ids": ids, "attention_mask": [1] * len(ids), "labels": labels}


def first_json(text):
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        return None
    try:
        return json.loads(m.group(0))
    except json.JSONDecodeError:
        return None


@torch.no_grad()
def evaluate(model, tok, rows, label):
    """평가: JSON 형식 비율, 가격 로그 오차(작을수록 좋음), 검사 allowed 정답률"""
    model.eval()
    ok = 0
    price_err, mod_hit, mod_n = [], 0, 0
    for row in rows:
        prompt = tok.apply_chat_template([{"role": "user", "content": row["messages"][0]["content"]}],
                                         tokenize=False, add_generation_prompt=True)
        ids = tok(prompt, return_tensors="pt", add_special_tokens=False).to(model.device)
        out = model.generate(**ids, max_new_tokens=256, do_sample=False)
        got = first_json(tok.decode(out[0][ids["input_ids"].shape[1]:], skip_special_tokens=True))
        want = json.loads(row["messages"][1]["content"])
        if got is None:
            if row["task"] == "moderation":
                mod_n += 1
            continue
        ok += 1
        if row["task"] == "price" and isinstance(got.get("price_krw"), (int, float)) and got["price_krw"] > 0:
            price_err.append(abs(math.log(got["price_krw"] / want["price_krw"])))
        if row["task"] == "moderation":
            mod_n += 1
            mod_hit += int(bool(got.get("allowed")) == want["allowed"])
    mean_err = sum(price_err) / len(price_err) if price_err else float("nan")
    print(f"[{label}] JSON 형식 {ok}/{len(rows)} · 가격 오차(평균 |log 비율|) {mean_err:.3f}"
          f"(≈ ±{(math.exp(mean_err) - 1) * 100:.0f}%) · 검사 정답 {mod_hit}/{mod_n}")
    model.train()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="google/gemma-4-E4B-it", help="Hugging Face 모델 이름(Gemma 4 E4B instruct)")
    ap.add_argument("--epochs", type=float, default=3)
    ap.add_argument("--max-len", type=int, default=1024)
    ap.add_argument("--skip-eval", action="store_true", help="학습 전 · 후 평가 생략(빨리 끝내기)")
    args = ap.parse_args()

    tok = AutoTokenizer.from_pretrained(args.model)
    if tok.pad_token is None:
        tok.pad_token = tok.eos_token
    model = load_model(args.model)
    model = prepare_model_for_kbit_training(model, use_gradient_checkpointing=True)
    lora = LoraConfig(r=8, lora_alpha=16, lora_dropout=0.05, bias="none", task_type="CAUSAL_LM",
                      target_modules=lora_targets(model))
    model = get_peft_model(model, lora)
    model.print_trainable_parameters()

    train_rows, eval_rows = load_rows("train.jsonl"), load_rows("eval.jsonl")
    if not args.skip_eval:
        evaluate(model, tok, eval_rows, "학습 전")

    train_ds = [encode(tok, r, args.max_len) for r in train_rows]
    out = HERE / "out"
    trainer = Trainer(
        model=model,
        args=TrainingArguments(
            output_dir=str(out / "checkpoints"), num_train_epochs=args.epochs, per_device_train_batch_size=1,
            gradient_accumulation_steps=8, learning_rate=2e-4, lr_scheduler_type="cosine", warmup_steps=2,
            logging_steps=5, save_strategy="no", bf16=True, optim="paged_adamw_8bit", report_to=[],
            gradient_checkpointing=True, remove_unused_columns=False),
        train_dataset=train_ds,
        data_collator=DataCollatorForSeq2Seq(tok, padding=True, label_pad_token_id=-100),
    )
    trainer.train()
    model.save_pretrained(out / "lora")
    tok.save_pretrained(out / "lora")
    if not args.skip_eval:
        evaluate(model, tok, eval_rows, "학습 후")

    # Ollama 변환용: 원본(bf16)에 LoRA 를 합쳐 저장(12GB GPU 에서는 CPU 로 합친다, 시간이 조금 걸림)
    print("LoRA 를 원본과 합치는 중(CPU)…")
    del model, trainer
    torch.cuda.empty_cache()
    try:
        base = AutoModelForCausalLM.from_pretrained(args.model, dtype=torch.bfloat16, device_map="cpu")
    except (ValueError, KeyError):
        from transformers import AutoModelForImageTextToText
        base = AutoModelForImageTextToText.from_pretrained(args.model, dtype=torch.bfloat16, device_map="cpu")
    merged = PeftModel.from_pretrained(base, out / "lora").merge_and_unload()
    merged.save_pretrained(out / "merged", safe_serialization=True)
    tok.save_pretrained(out / "merged")
    print(f"완료: {out / 'lora'} , {out / 'merged'}")


if __name__ == "__main__":
    main()
