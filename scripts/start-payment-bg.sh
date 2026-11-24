#!/usr/bin/env bash
# (예전 이름 유지) 이제는 허브(Kafka) + 물품 서버 + 결제 서버를 함께 백그라운드로 켠다 → start-msa-bg.sh
exec "$(dirname "$0")/start-msa-bg.sh" "$@"
