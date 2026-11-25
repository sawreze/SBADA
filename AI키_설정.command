#!/bin/bash
# ===== 사진 AI 분석(Gemma) 켜기 (2026-11-25) =====
#  물품 서버는 ① Google AI Studio 키가 있으면 클라우드 Gemma → ② 맥에 Ollama(gemma4:e4b)가 켜져 있으면 로컬 Gemma
#  → ③ 둘 다 없으면 '카테고리 시세 × 상태' 로 추정(사진을 보지 않음, 앱에 'AI 미연결' 표시) 순서로 쓴다.
#  이 파일: 지금 상태를 보여 주고, Google AI Studio 키를 화면에 안 보이게 받아 Git 제외 파일에만 저장한다.
#    키 받기: https://aistudio.google.com/apikey → Create API key (무료, AIza 로 시작). 채팅에 붙여 넣지 마세요.
#    키 없이 하려면: https://ollama.com 에서 Ollama 설치 → 터미널 `ollama pull gemma4:e4b` (몇 GB, 한 번만)
#  저장 위치: sbada-server/application-local.properties (물품 서버도 이 파일을 읽음)
ROOT="$(cd "$(dirname "$0")" && pwd)"
MAIN="$ROOT/sbada-server/application-local.properties"
put(){ local f="$1" k="$2" v="$3"; touch "$f"; chmod 600 "$f"
  grep -v "^$k=" "$f" > "$f.tmp" 2>/dev/null; echo "$k=$v" >> "$f.tmp"; mv "$f.tmp" "$f"; chmod 600 "$f"; }

echo "== 지금 AI 상태"
S="$(curl -s -m 5 http://127.0.0.1:8097/api/ai/status)"; echo "  서버: ${S:-'(서버가 꺼져 있음)'}"
if curl -s -m 3 http://127.0.0.1:11434/api/tags | grep -q 'gemma4'; then echo "  Ollama: 켜져 있고 gemma4 모델 있음 ✅"
elif curl -s -m 3 http://127.0.0.1:11434/api/tags >/dev/null; then echo "  Ollama: 켜져 있지만 gemma4 모델 없음 → 터미널에서 ollama pull gemma4:e4b"
else echo "  Ollama: 꺼져 있거나 설치 안 됨"; fi
grep -q '^sbada.ai.google.api-key=.' "$MAIN" 2>/dev/null && echo "  Google AI 키: 들어 있음" || echo "  Google AI 키: 없음"

echo; echo "== Google AI Studio 키 넣기 (https://aistudio.google.com/apikey)"
echo "키를 붙여 넣고 Enter (⌘V, 화면에 안 보여요 / 건너뛰려면 그냥 Enter)"
read -rs V; echo; V="$(printf '%s' "$V" | tr -d '[:space:]')"
if [ -n "$V" ]; then
  if printf '%s' "$V" | grep -Eq '^AIza[0-9A-Za-z_-]{20,60}$'; then
    put "$MAIN" sbada.ai.google.api-key "$V"; put "$MAIN" sbada.ai.provider auto
    echo "  [OK] 저장(${#V}자) — 서버를 다시 켜면 사진을 Gemma 가 직접 보고 분석해요"
  else echo "  [거절] AIza 로 시작하는 키가 아니에요. 다시 확인해 주세요."; fi
fi
cd "$ROOT" && { git check-ignore -q "$MAIN" 2>/dev/null || grep -qx 'application-local.properties' sbada-server/.gitignore 2>/dev/null; } \
  && echo "[OK] Git 제외 파일: sbada-server/application-local.properties"
echo; echo "다음: 스바다_실행.command (옛 서버는 자동으로 끄고 새로 켬) → 다시 이 파일을 열면 상태에 google 이 보여요."
read -r -p "Enter 를 누르면 닫혀요"
