"""
학습한 스바다 Gemma(원본 4비트 + LoRA)를 PC 에서 띄우는 작은 AI 서버 — Ollama 와 같은 주소 · 형식
  python training/serve_lora.py            (기본 http://127.0.0.1:11435)

스바다 서버(OllamaGemmaClient)가 원래 Ollama 에 보내던 요청을 그대로 받는다:
  GET  /api/tags      → {"models":[{"name":"sbada-gemma-lora"}]}   (켜져 있는지 확인용)
  POST /api/generate  {"model","prompt","images":[Base64],"stream":false,"format":"json"}
                      → {"model":"sbada-gemma-lora","response":"{...JSON...}","done":true}
그래서 서버 코드는 바꾸지 않고, 서버 실행 옵션만
  --sbada.ai.provider=ollama --sbada.ai.ollama.url=http://127.0.0.1:11435 --sbada.ai.ollama.model=sbada-gemma-lora
로 바꾸면 학습한 모델로 가격을 매긴다(scripts\\restart-server-finetuned.bat).
"""
import argparse
import base64
import io
import json
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import torch
from PIL import Image
from peft import PeftModel
from transformers import AutoProcessor

sys.path.insert(0, str(Path(__file__).resolve().parent))
from train_vision import load_model, log  # noqa: E402

HERE = Path(__file__).resolve().parent
NAME = "sbada-gemma-lora"
LOCK = threading.Lock()
STATE = {}


def answer(prompt, image_bytes):
    proc, model = STATE["proc"], STATE["model"]
    img = Image.open(io.BytesIO(image_bytes)).convert("RGB") if image_bytes else None
    if img is not None:
        img.thumbnail((896, 896))
    content = ([{"type": "image"}] if img is not None else []) + [{"type": "text", "text": prompt}]
    text = proc.apply_chat_template([{"role": "user", "content": content}], tokenize=False, add_generation_prompt=True)
    enc = proc(text=text, return_tensors="pt", **({"images": [img]} if img is not None else {})).to(model.device)
    if "pixel_values" in enc:
        enc["pixel_values"] = enc["pixel_values"].to(torch.bfloat16)
    with LOCK, torch.no_grad():
        out = model.generate(**enc, max_new_tokens=STATE["max_new"], do_sample=False, use_cache=True)
    raw = proc.decode(out[0][enc["input_ids"].shape[1]:], skip_special_tokens=True)
    m = re.search(r"\{.*\}", raw, re.S)
    return m.group(0) if m else raw


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/api/tags"):
            self._send(200, {"models": [{"name": NAME, "model": NAME}]})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if not self.path.startswith("/api/generate"):
            self._send(404, {"error": "not found"})
            return
        try:
            req = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("utf-8"))
            imgs = req.get("images") or []
            t0 = time.time()
            resp = answer(req.get("prompt", ""), base64.b64decode(imgs[0]) if imgs else None)
            log(f"분석 {time.time() - t0:.1f}초: {resp[:120]}")
            self._send(200, {"model": NAME, "response": resp, "done": True})
        except Exception as e:  # 서버가 실패로 보고 fallback 가격을 쓰게 500
            log(f"오류: {e!r}")
            self._send(500, {"error": str(e)})

    def log_message(self, fmt, *args):  # 기본 접속 기록은 끄고 위 log 만 남긴다
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="google/gemma-4-E4B-it")
    ap.add_argument("--lora", default=str(HERE / "out-final" / "lora"))
    ap.add_argument("--port", type=int, default=11435)
    ap.add_argument("--max-new", type=int, default=160)
    args = ap.parse_args()
    log(f"모델 불러오는 중… (LoRA: {args.lora})")
    STATE["proc"] = AutoProcessor.from_pretrained(args.model)
    base = load_model(args.model)
    STATE["model"] = PeftModel.from_pretrained(base, args.lora).eval()
    STATE["max_new"] = args.max_new
    log(f"준비 완료: http://127.0.0.1:{args.port}  (모델 이름 {NAME})")
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
