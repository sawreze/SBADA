# sbada-proto — 서비스끼리 주고받는 약속(gRPC '배달부') (2026-11-25)

세 서버가 같은 `.proto` 를 읽어 자바 코드를 만든다. 각 서버의 `build.gradle` 이
`sourceSets.main.proto.srcDir '../sbada-proto/proto'` 로 이 폴더를 가리킨다(따로 빌드할 필요 없음).

| 파일 | 제공(서버) | 부르는 쪽 | 하는 일 |
|---|---|---|---|
| user/v1/user.proto | 메인 :9097 | 물품 | 회원 이름 · 동네(목록 표시), 추천에 쓰는 취향 · 보낸 요청 |
| exchange/v1/exchange.proto | 메인 :9097 | 결제 | 교환의 추가 금액 조건(누가 · 누구에게 · 얼마) |
| item/v1/item.proto | 물품 :9099 | 메인 | 교환 · 채팅에 보여 줄 물건 정보, 교환 성사 시 두 물건을 거래 완료로 |
| payment/v1/payment.proto | 결제 :9098 | 메인 | 안전결제 정산 · 환불 |

규칙
- 필드 번호는 한 번 정하면 바꾸지 않는다(지우면 `reserved`). 새 필드는 뒤에 번호를 이어서 붙인다.
- 인증: 모든 호출에 메타데이터 `x-internal-token`(공유 비밀 파일 data/.internal-token). 없으면 UNAUTHENTICATED.
- 업무 오류는 gRPC 상태 FAILED_PRECONDITION · NOT_FOUND 등 + 설명 `"코드|한국어 메시지"` 로 돌려준다.
