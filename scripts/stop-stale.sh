#!/usr/bin/env bash
# ===== 옛 코드로 돌고 있는 서버 끄기 (2026-11-25) =====
#  서버가 켜진 뒤에 소스가 바뀌었으면(예: MSA 적용 · 코드 업데이트) 그 서버는 옛 코드라 오류가 난다
#  (예: NoClassDefFoundError — 테스트가 build/classes 를 새로 만들면서 옛 클래스가 사라짐).
#  포트별로 "서버 시작 시각 < 소스 마지막 수정 시각" 이면 끈다. 실행 스크립트가 다시 켠다.
#  사용: scripts/stop-stale.sh            (메인 8097 · 결제 8098 · 물품 8099 모두 확인)
source "$(dirname "$0")/_common.sh"
mtime(){ stat -f %m "$1" 2>/dev/null || stat -c %Y "$1"; }
newest(){ # 폴더들 안 소스 · 설정 파일 중 가장 최근 수정 시각(초)
  find "$@" -type f \( -name '*.java' -o -name '*.proto' -o -name '*.sql' -o -name '*.properties' -o -name '*.graphqls' -o -name '*.gradle' \) 2>/dev/null \
    | while IFS= read -r f; do mtime "$f"; done | sort -n | tail -1
}
started(){ # PID 시작 시각(초) = 지금 - 돌아간 시간(etime: [[일-]시:]분:초). 한국어 설정에서도 숫자만 나와서 안전
  local e d=0 h=0 m=0 x=0 a b c
  e="$(LC_ALL=C ps -o etime= -p "$1" 2>/dev/null | tr -d ' ')"; [ -z "$e" ] && return
  case "$e" in *-*) d="${e%%-*}"; e="${e#*-}";; esac
  IFS=: read -r a b c <<<"$e"
  if [ -n "$c" ]; then h=$a; m=$b; x=$c; else m=$a; x=$b; fi
  echo $(( $(date +%s) - (10#$d*86400 + 10#$h*3600 + 10#$m*60 + 10#$x) ))
}
check(){ # 포트 폴더
  local port="$1" dir="$2" pid st nw
  pid="$(lsof -ti tcp:$port -sTCP:LISTEN 2>/dev/null | head -1)"; [ -z "$pid" ] && return
  # 2026-11-25: 다른 폴더(예: 구글드라이브 사본)에서 켜진 서버면 끈다 — 키 · DB · 토큰 파일이 달라서
  #  로그인 만료(401) · 물품 서버 DOWN 이 난다
  local cwd; cwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -1)"
  if [ -n "$cwd" ] && [ "${cwd#$ROOT/}" = "$cwd" ]; then
    echo "포트 $port 서버가 다른 폴더($cwd)에서 켜져 있어요 → 끄고 이 폴더에서 새로 켭니다"
    kill $(lsof -ti tcp:$port -sTCP:LISTEN 2>/dev/null); sleep 3; return
  fi
  st="$(started "$pid")"; nw="$(newest "$ROOT/$dir/src/main" "$ROOT/$dir/build.gradle" "$ROOT/sbada-proto/proto")"
  [ -z "$st" ] || [ -z "$nw" ] && return
  if [ "$nw" -gt "$st" ]; then
    echo "포트 $port($dir) 서버가 옛 코드로 돌고 있어요(소스가 그 뒤에 바뀜) → 끄고 새로 켭니다"
    kill $(lsof -ti tcp:$port -sTCP:LISTEN 2>/dev/null); sleep 3
  fi
}
check 8097 sbada-server
check 8098 sbada-payment
check 8099 sbada-item
true
