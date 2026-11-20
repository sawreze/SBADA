# 스바다 MSA 1단계 — 결제 서비스 분리 (2026-11-20)

> 시연용: 실제 PG · 은행과 연결하지 않는다. 결제 · 출금은 결제 서비스 DB 안의 숫자만 바뀐다.

## 구조

```mermaid
flowchart LR
  APP[Flutter 앱] -->|모든 요청 :8097| GW
  subgraph CORE["메인 서버 sbada-server :8097"]
    GW[게이트웨이<br/>PaymentGatewayController<br/>JWT 검사 후 라우팅]
    SVC[회원 · 물건 · 교환 · 채팅 · 알림]
    IN[/internal/events<br/>/internal/users/{id}/]
  end
  subgraph PAY["결제 서비스 sbada-payment :8098"]
    W[지갑 · 계좌 · 결제 · 출금]
    OB[(아웃박스)]
    RELAY[OutboxRelay]
  end
  GW -->|/api/wallet/** · /api/payments/**| W
  GW --> SVC
  W --> OB --> RELAY -->|X-Internal-Token| IN --> SVC
  W -->|받는 사람 확인| IN
  SVC --> DB1[(H2 sbada)]
  W --> DB2[(H2 payment)]
```

| MSA 원칙 | 스바다에서 |
|---|---|
| 서비스별 독립 실행 · 배포 | `sbada-server`(8097) · `sbada-payment`(8098) 각각 jar · Dockerfile |
| 서비스별 DB | 메인 `data/sbada`, 결제 `sbada-payment/data/payment` — 서로의 표를 직접 읽지 않는다 |
| API 게이트웨이 | 앱은 8097 하나만 안다. `/api/wallet/**` · `/api/payments/**` 는 게이트웨이가 결제 서비스로 전달 |
| 인증 | 회원 서비스가 JWT 발급, 결제 서비스는 같은 키로 검증만(게이트웨이 + 서비스 이중 검사) |
| 이벤트 기반 통신 | 결제 서비스는 거래와 같은 트랜잭션에 아웃박스 이벤트를 쓰고, 릴레이가 메인 서버로 전달(at-least-once). 메인 서버는 `processed_events` 로 중복 제거(멱등) |
| 장애 격리 | 결제 서비스가 꺼져도 교환 · 채팅은 동작. 결제 API 만 503 `PAYMENT_SERVICE_DOWN` |
| 서비스 간 인증 | `X-Internal-Token`(gitignore 된 `data/.internal-token` 공유) |

규모가 커지면: 게이트웨이 → Spring Cloud Gateway / Nginx, 아웃박스 릴레이 → Kafka 프로듀서, 키 파일 → KMS.

## 결제 흐름(교환 추가 요금)

1. 채팅방 오른쪽 위 💳 → 상대가 요구한 금액 입력, 결제수단(카드 · 카카오페이 · 토스페이 · 계좌이체) 선택
2. **보내는 사람이 금액 + 수수료 5%** 를 결제 (예: 10,000원 → 10,500원)
3. 받는 사람 지갑에 10,000원 입금 + 알림 "추가 요금이 들어왔어요"
4. 받는 사람: 계정정보 → 지갑 → 계좌 선택 · 금액 입력 → 출금 → "출금되었습니다"

## 계좌번호 보호

- bcrypt 는 되돌릴 수 없는 해시라 비밀번호용. 계좌번호는 송금 때 원문이 필요해 **복호화 가능한 암호화**를 쓴다.
- 봉투 암호화: 계좌마다 새 AES-256-GCM 키로 암호화 → 그 키를 RSA-3072 OAEP(SHA-256) 공개키로 감싸 저장.
- RSA 개인키 · HMAC 키는 DB 가 아닌 `sbada-payment/data/keys/`(gitignore, 권한 600). DB 만 유출되면 계좌번호를 알 수 없다.
- DB 평문은 뒷자리(****1234)와 중복 확인용 HMAC-SHA256 지문뿐.

## 실제 서비스로 바꿀 때

개정 전자금융거래법(2024-09 시행): 구매 대금을 받아 셀러에게 정산하면 PG 등록 대상이 될 수 있다.
직접 등록 대신 PG사 **지급대행(정산대행)** 서비스를 쓰고, `WalletService.withdraw` 의 표시된 자리에서 PG API 를 부르면 된다.
