# 03. API 명세서

앱은 **메인 서버 8097 하나만** 부른다. `/api/wallet/**`, `/api/payments/**` 는 메인 서버 게이트웨이가 결제 서비스(8098)로 넘긴다.
인증: `Authorization: Bearer <JWT>`(메인 서버 발급, 결제 서비스도 같은 키로 검증). 오류: `{"code","message","errors"}`.
더 자세한 요청 · 응답 예시는 각 Controller 파일 상단 주석과 `postman/` 에 있다.

## 메인 서버 (sbada-server)
| 영역 | 주소 |
|---|---|
| 상태 | `GET /api/health` |
| 인증 | `POST /api/auth/signup`(email · password · nickname, username 은 선택 — 없으면 이메일 앞부분으로) · `/login`(email 또는 예전 username · rememberMe) · `/kakao` |
| 내 정보 | `GET/PUT /api/me/...` (profile, password, photo, preferences, region, second-region, notification-settings), `GET /api/me/items`, `/api/me/likes` |
| 물건 | `GET/POST /api/items`, `GET/PUT/DELETE /api/items/{id}`, `/api/items/{id}/like`, `/matches`, `/summary`, `GET /api/items/count`, `GET /api/categories` |
| AI | `POST /api/ai/analyze` → AnalysisView(price, priceMin, priceMax, confidence, reason, tags, **newPrice**, marketSamples …), `GET /api/ai/status` |
| 피드 | `GET /api/feed`, `/api/feed/scores` |
| 교환 | `POST /api/exchanges`, `GET /api/exchanges?box=received|sent`, `GET /{id}`, `POST /{id}/accept|reject|cancel` |
| 채팅 | `GET /api/chats`, `/{roomId}`, `/{roomId}/messages`, `POST /{roomId}/messages`, `/{roomId}/read`, WebSocket `/ws?token=` |
| 알림 | `GET /api/notifications`, `/unread-count`, `POST /{id}/read`, `/read-all`, `POST /api/devices`, `/api/devices/remove` |
| 지도 | `GET /api/config/map`, `GET /api/geo/reverse` |
| 내부(서비스 간) | `GET /internal/users/{id}`, `POST /internal/events` — 헤더 `X-Internal-Token` 필수, 이벤트는 eventId 로 중복 제거 |

## 결제 서비스 (sbada-payment, 시연용)
| 주소 | 설명 |
|---|---|
| `GET /api/payment/health` | 상태(로그인 불필요) |
| `GET /api/wallet` | 잔액 · 계좌 · 최근 거래 30개 · 은행/결제수단 목록 · feePercent(5) |
| `POST /api/wallet/accounts` | `{bankCode, accountNumber, holder}` → 201. 숫자 10~16자리, 중복 409, 최대 5개 |
| `DELETE /api/wallet/accounts/{id}` | 계좌 삭제 |
| `POST /api/wallet/withdraw` | `{accountId, amount}` → `{message:"출금되었습니다", balance, transaction}` · 잔액 부족 409 |
| `POST /api/wallet/charge` | `{method, amount}` 충전(결제창 흉내) |
| `GET /api/payments/quote?amount=` | 수수료 미리보기 `{amount, feePercent, fee, total}` |
| `POST /api/payments/extra` | `{toUserId, amount, method, exchangeId?, memo?}` 보내는 사람 amount+5% 결제, 받는 사람 지갑 +amount (현재 즉시 지급) |
- 금액 범위 1,000 ~ 1,000,000원. 결제수단 코드: CARD, KAKAOPAY, TOSSPAY, TRANSFER. 은행 코드: KAKAOBANK, TOSSBANK, KB, SHINHAN, WOORI, HANA, NH, IBK.
