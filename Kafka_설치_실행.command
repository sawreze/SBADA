#!/bin/bash
# ===== 허브(Kafka) 설치 · 실행 — Homebrew 로, Docker 없이 (2026-11-25 MSA 2단계) =====
#  처음 한 번: brew install kafka(5~10분) → 저장소 포맷(KRaft, ZooKeeper 없음) → 백그라운드 실행 → 토픽 만들기
#  그 뒤로는: 이미 켜져 있으면 그대로, 꺼져 있으면 다시 켠다. 주소 127.0.0.1:9092 (맥 안에서만)
#  끄기: brew services stop kafka   기록: logs/kafka.log
ROOT="$(cd "$(dirname "$0")" && pwd)"; source "$ROOT/scripts/_common.sh"
L="$ROOT/logs/kafka.log"
say(){ echo "[$(date +%H:%M:%S)] $*"; }
up(){ nc -z 127.0.0.1 9092 >/dev/null 2>&1; }
{
command -v brew >/dev/null 2>&1 || { say "Homebrew 가 없어요 → https://brew.sh 에서 설치한 뒤 다시 실행"; exit 1; }
if ! brew list kafka >/dev/null 2>&1; then say "1) Kafka 설치(처음 한 번, 몇 분 걸려요)"; brew install kafka || { say "설치 실패"; exit 1; }; else say "1) Kafka 이미 설치됨 ($(brew list --versions kafka))"; fi
BP="$(brew --prefix)"; CONF="$BP/etc/kafka/server.properties"
[ -f "$CONF" ] || CONF="$(ls "$BP"/etc/kafka/*server*.properties 2>/dev/null | head -1)"
say "설정 파일: $CONF"
LOGDIR="$(grep -E '^log.dirs=' "$CONF" | head -1 | cut -d= -f2)"
if [ -n "$LOGDIR" ] && [ ! -f "$LOGDIR/meta.properties" ]; then
  say "2) 저장소 포맷(처음 한 번, KRaft)"
  kafka-storage format --standalone -t "$(kafka-storage random-uuid)" -c "$CONF" 2>&1 | tail -2
else say "2) 저장소 준비됨"; fi
if up; then say "3) Kafka 이미 실행 중"; else
  say "3) Kafka 켜기(백그라운드)"; brew services start kafka
  for i in $(seq 1 40); do up && break; sleep 1; done
fi
up && say "   Kafka OK (127.0.0.1:9092)" || { say "   Kafka 가 안 떠요 → brew services info kafka / $BP/var/log/kafka 확인"; exit 1; }
say "4) 토픽 만들기(이미 있으면 그대로)"
for T in sbada.item.events sbada.exchange.events sbada.user.events sbada.payment.events sbada.notifications; do
  kafka-topics --bootstrap-server 127.0.0.1:9092 --create --if-not-exists --topic "$T" --partitions 3 --replication-factor 1 2>&1 | grep -v WARN
done
kafka-topics --bootstrap-server 127.0.0.1:9092 --list
say "DONE"
} 2>&1 | tee "$L"
read -r -p "Enter 를 누르면 이 창이 닫혀요"
