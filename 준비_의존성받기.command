#!/bin/bash
# ===== MSA 2단계 준비: 새 라이브러리(gRPC · Kafka · GraphQL) 받기 + .proto → 자바 코드 생성 확인 =====
# 서버 3개(메인 · 결제 · 물품)에서 ./gradlew prefetch generateProto 를 실행한다. 기록: logs/prefetch.log
#  - prefetch: 필요한 jar 를 내려받고 build/prefetch-cp.txt 에 목록(경로만)을 남긴다. 비밀값은 없다
#  - Claude 가 이 jar 들로 클라우드에서 컴파일 · 테스트를 돌린다(맥에서 매번 검사하지 않아도 되게)
ROOT="$(cd "$(dirname "$0")" && pwd)"; source "$ROOT/scripts/_common.sh"
L="$ROOT/logs/prefetch.log"
{
echo "[$(date +%H:%M:%S)] 시작 (java: $(java -version 2>&1 | head -1))"
for P in sbada-server sbada-payment sbada-item; do
  echo "===== $P"
  cd "$ROOT/$P" && chmod +x gradlew && ./gradlew prefetch generateProto --console=plain 2>&1 \
    | grep -E "PREFETCH_OK|protobuf-java.version|BUILD|FAIL|error|Could not|What went wrong|> " | head -40
  echo "${P}_EXIT=${PIPESTATUS[0]}"
  ls build/generated/sources/proto/main 2>/dev/null | tr '\n' ' '; echo
done
echo "[$(date +%H:%M:%S)] DONE"
} 2>&1 | tee "$L"
read -r -p "Enter 를 누르면 이 창이 닫혀요"
