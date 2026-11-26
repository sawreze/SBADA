#!/bin/bash
# ===== 자동 깃허브 올리기: 맥 ~/dev/SBADA 의 바뀐 파일을 5분마다 커밋 · 푸시 (2026-11-26 사용자 요청) =====
#  launchd(자동_깃허브_올리기_켜기.command 가 등록)가 5분마다 실행한다. 손으로 돌려도 된다.
#  안전장치
#   - 비밀값 파일은 .gitignore 로 빠지고, 올리기 직전 바뀐 내용에서 키 모양 문자열을 한 번 더 찾는다 → 있으면 올리지 않는다.
#   - 새 파일 · 바뀐 파일만 올린다(지워진 파일은 올리지 않음: 맥 폴더에 없는 파일이 레포에서 사라지지 않게).
#   - 먼저 깃허브의 최신 내용을 받아(rebase) 다른 곳(클라우드 · 윈도우)에서 올린 커밋과 섞이지 않게 한다.
#   - 바뀐 게 없으면 아무것도 하지 않는다.
#   - 하루에 최대 20번만 올린다(사용자 요청). 다 쓰면 그날 바뀐 것은 모아 두었다가 다음 날 첫 번째에 같이 올린다.
MAX_PER_DAY="${MAX_PER_DAY:-20}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/logs/auto-push.log"; mkdir -p "$ROOT/logs"
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:$PATH"
say(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" >> "$LOG"; }
# 로그가 너무 커지지 않게(1MB 넘으면 뒤쪽 2000줄만)
[ -f "$LOG" ] && [ "$(wc -c < "$LOG")" -gt 1048576 ] && tail -n 2000 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"

cd "$ROOT" || exit 1
# 두 번 겹쳐 돌지 않게
LOCK="$ROOT/logs/.auto-push.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
  # 30분 넘게 남은 잠금은 죽은 것으로 보고 지운다
  if [ -n "$(find "$LOCK" -maxdepth 0 -mmin +30 2>/dev/null)" ]; then rmdir "$LOCK"; mkdir "$LOCK" || exit 0; else exit 0; fi
fi
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

# 오늘 올린 횟수(logs/.auto-push-count: "날짜 횟수")
TODAY="$(date +%Y-%m-%d)"; COUNT_FILE="$ROOT/logs/.auto-push-count"
read -r CDAY CNT < "$COUNT_FILE" 2>/dev/null
[ "$CDAY" = "$TODAY" ] || CNT=0
if [ "${CNT:-0}" -ge "$MAX_PER_DAY" ]; then exit 0; fi

[ -d .git ] || { say "git 저장소가 아니에요 → 자동_깃허브_올리기_켜기.command 를 먼저 실행해 주세요"; exit 1; }
# 이전에 멈춘 rebase 가 있으면 정리
[ -d .git/rebase-merge ] || [ -d .git/rebase-apply ] && git rebase --abort >/dev/null 2>&1

git add --ignore-removal . >/dev/null 2>&1
AHEAD="$(git rev-list --count origin/main..HEAD 2>/dev/null || echo 0)"
if git diff --cached --quiet; then
  [ "$AHEAD" -gt 0 ] || exit 0   # 바뀐 것도, 못 올린 커밋도 없음
  FILES=0; FIRST="(지난번에 못 올린 커밋)"
else

# 비밀값 검사(키 값은 로그에 남기지 않고 파일 이름만)
PATTERN='(AIza[0-9A-Za-z_-]{30,}|sk_(live|test)_[0-9A-Za-z]{16,}|(live|test)_(sk|ck|gsk)_[0-9A-Za-z]{16,}|ghp_[0-9A-Za-z]{30,}|github_pat_[0-9A-Za-z_]{30,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|(api[_-]?key|secret[_-]?key|client[_-]?secret|access[_-]?token)[[:space:]]*[=:][[:space:]]*["'"'"']?[A-Za-z0-9_\-]{20,})'
# 새로 더해진 줄만 본다(파일 이름은 diff 의 +++ 줄에서)
HIT="$(git diff --cached -U0 --no-color | while IFS= read -r line; do
  case "$line" in
    '+++ b/'*) f="${line#+++ b/}" ;;
    '+++'*) ;;
    '+'*) printf '%s\n' "$line" | grep -iEq "$PATTERN" && echo "$f" ;;
  esac
done | sort -u)"
if [ -n "$HIT" ]; then
  git reset -q
  say "⚠️ 비밀값처럼 보이는 내용이 있어 올리지 않았어요: $(echo "$HIT" | tr '\n' ' ')"
  osascript -e 'display notification "비밀값처럼 보이는 내용이 있어 이번엔 올리지 않았어요 (logs/auto-push.log)" with title "스바다 자동 올리기"' >/dev/null 2>&1
  exit 1
fi

FILES="$(git diff --cached --name-only | wc -l | tr -d ' ')"
FIRST="$(git diff --cached --name-only | head -3 | tr '\n' ' ')"
NAME="$(git log -1 --format='%an' 2>/dev/null)"; EMAIL="$(git log -1 --format='%ae' 2>/dev/null)"
git -c user.name="${NAME:-sawreze}" -c user.email="${EMAIL:-sawreze@users.noreply.github.com}" \
  commit -q -m "자동 저장: 맥에서 바뀐 파일 ${FILES}개 ($(date '+%Y-%m-%d %H:%M'))" -m "$FIRST" || { say "커밋 실패"; exit 1; }
fi

if ! git pull -q --rebase --autostash origin main >> "$LOG" 2>&1; then
  git rebase --abort >/dev/null 2>&1
  say "⚠️ 깃허브 최신 내용과 겹치는 수정이 있어 합치지 못했어요(커밋은 맥에 남아 있어요). 다음 차례에 다시 시도해요"
  exit 1
fi
if git push -q origin HEAD:main >> "$LOG" 2>&1; then
  CNT=$(( ${CNT:-0} + 1 )); echo "$TODAY $CNT" > "$COUNT_FILE"
  say "올림(오늘 ${CNT}/${MAX_PER_DAY}번째): ${FILES}개 파일 · $FIRST"
  [ "$CNT" -ge "$MAX_PER_DAY" ] && say "오늘 올리기 ${MAX_PER_DAY}번을 다 썼어요. 이후 바뀐 것은 내일 첫 번째에 같이 올라가요"
else
  say "⚠️ 푸시 실패(인터넷 · 깃허브 로그인 확인). 커밋은 맥에 남아 있어 다음 차례에 같이 올라가요"
  exit 1
fi
