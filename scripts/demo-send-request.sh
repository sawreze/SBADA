#!/usr/bin/env bash
# ===== 시연 도우미: 준호(junho)가 민지(minji)의 물건에 '바로 바꾸기' 요청을 보낸다 =====
# 에뮬레이터에 민지로 로그인해 둔 상태에서 실행하면 '교환 요청이 왔어요' 푸시가 뜬다.
source "$(dirname "$0")/_common.sh"
python3 "$(dirname "$0")/demo_send_request.py" "$@"
