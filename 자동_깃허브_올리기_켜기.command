#!/bin/bash
# ===== 자동 깃허브 올리기 켜기: 5분마다 맥 ~/dev/SBADA → github.com/sawreze/SBADA (2026-11-26 사용자 요청) =====
#  1) 이 폴더가 git 저장소가 아니면 깃허브 레포와 연결한다(파일은 그대로 두고 기록만 맞춘다).
#  2) 깃허브 로그인을 확인하고 한 번 바로 올려 본다.
#  3) launchd 에 등록해 맥이 켜져 있는 동안 5분마다 scripts/auto_push.sh 를 실행한다(재부팅 후에도 계속).
#     단, 실제로 올리는 건 하루 최대 20번(scripts/auto_push.sh 의 MAX_PER_DAY).
#  끄기: 자동_깃허브_올리기_끄기.command · 기록: logs/auto-push.log
ROOT="$(cd "$(dirname "$0")" && pwd)"
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:$PATH"
say(){ echo "[$(date +%H:%M:%S)] $*"; }
REPO="https://github.com/sawreze/SBADA.git"
LABEL="com.sbada.autopush"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
cd "$ROOT" || exit 1
mkdir -p logs

# 1) 깃허브 레포와 연결
if [ ! -d .git ]; then
  say "이 폴더를 깃허브 레포와 연결하는 중…"
  git init -q -b main && git remote add origin "$REPO"
  if ! git fetch -q origin main; then
    say "깃허브에서 받아오지 못했어요(인터넷 · 로그인 확인)"; rm -rf .git; read -r -p "Enter 를 누르면 닫혀요"; exit 1
  fi
  git reset -q origin/main          # 파일은 건드리지 않고 기록만 깃허브와 맞춘다
  git branch -q --set-upstream-to=origin/main main
  # 줄바꿈(윈도우 CRLF) · 끝 공백만 다른 파일은 깃허브 것으로 맞춘다(내용이 같으니 잃는 것 없음). 없는 파일도 되살린다.
  git ls-files -d | while IFS= read -r f; do git checkout -q -- "$f"; done
  git diff --name-only | while IFS= read -r f; do
    git diff --quiet --ignore-cr-at-eol --ignore-space-at-eol -- "$f" && git checkout -q -- "$f"
  done
  say "연결 완료. 깃허브와 다른 파일:"; git status --short | grep -v '^ D' | head -20
else
  git remote get-url origin >/dev/null 2>&1 || git remote add origin "$REPO"
  git fetch -q origin main
fi

# 2) 깃허브 로그인(푸시 권한) 확인
if command -v gh >/dev/null 2>&1; then
  gh auth status >/dev/null 2>&1 || { say "깃허브 로그인이 필요해요 — 브라우저가 열리면 로그인해 주세요"; gh auth login -h github.com -p https -w; }
  gh auth setup-git >/dev/null 2>&1
fi
if ! git push -q --dry-run origin HEAD:main 2>/dev/null; then
  say "깃허브에 올릴 권한이 아직 없어요."
  say "  → 터미널에서 'brew install gh' 후 'gh auth login' 으로 로그인하고 이 파일을 다시 실행해 주세요."
  read -r -p "Enter 를 누르면 닫혀요"; exit 1
fi

# 3) 지금 한 번 올려 보기
chmod +x scripts/auto_push.sh
bash scripts/auto_push.sh; tail -n 3 logs/auto-push.log 2>/dev/null

# 4) 5분마다 실행 등록(launchd)
mkdir -p "$HOME/Library/LaunchAgents"
cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array><string>/bin/bash</string><string>$ROOT/scripts/auto_push.sh</string></array>
  <key>StartInterval</key><integer>300</integer>
  <key>RunAtLoad</key><true/>
  <key>StandardOutPath</key><string>$ROOT/logs/auto-push.launchd.log</string>
  <key>StandardErrorPath</key><string>$ROOT/logs/auto-push.launchd.log</string>
</dict>
</plist>
EOF
launchctl bootout "gui/$(id -u)/$LABEL" >/dev/null 2>&1
if launchctl bootstrap "gui/$(id -u)" "$PLIST"; then
  say "완료! 이제 5분마다 확인해서 바뀐 파일을 깃허브에 자동으로 올려요(하루 최대 20번)."
  say "  기록: logs/auto-push.log · 끄기: 자동_깃허브_올리기_끄기.command"
else
  say "등록 실패 — 위 메시지를 확인해 주세요"
fi
read -r -t 15 -p "Enter 를 누르면 닫혀요(15초 뒤 자동으로 닫힘)"
