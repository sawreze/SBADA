# AGENTS.md — 스바다(SBADA) 작업 규칙 (Codex · 다른 AI 에이전트용)

이 파일은 Codex 가 이 저장소에서 일할 때 자동으로 읽는 지침이다. 자세한 인수인계는 `docs/handoff/` 를 먼저 읽는다.

## 0. 꼭 지킬 것
- **답변 · 커밋 메시지 · 코드 주석은 한국어**로 쓴다(사용자가 한국어 사용).
- **비밀값(API 키 · 토큰 · 비밀번호 · 개인키)은 절대 Git 에 올리지 않는다.** 사용자가 강하게 요구한 규칙이다.
  - 비밀값 위치(모두 gitignore): `sbada-server/application-local.properties`, `sbada_app/android/local.properties`,
    `sbada_app/ios/Flutter/Secrets.xcconfig`, `*.p8`, `*/data/**`(JWT 키 `.jwt-secret`, 내부 토큰 `.internal-token`, 계좌 암호화 RSA 키 `sbada-payment/data/keys/`).
  - 커밋 전에 `git diff --cached` 에서 키 모양 문자열을 검사한다. 키 값을 출력하거나 로그에 남기지 않는다.
- 결제 · 지갑은 **시연용**이다. 실제 PG · 은행 API 를 호출하는 코드를 넣지 않는다(실제 돈 이동 금지).
- 기존 스타일(파일 상단 한국어 설명 주석, `// 2026-xx-xx 사용자 요청: …` 변경 메모)을 따른다.
- 바꾼 기능에는 테스트를 같이 추가/수정한다. DB 를 바꾸면 Flyway 마이그레이션 `V{n}__설명.sql` 을 새로 만들고
  `sbada-server/src/test/java/com/sbada/FlywayMigrationTest.java` 의 버전 개수도 올린다(현재 메인 V18 · 결제 V2 까지).

## 1. 구조
| 폴더 | 내용 |
|---|---|
| `sbada_app/` | Flutter 앱(iOS · Android · Web). `lib/screens`, `lib/widgets`, `lib/repositories`, `lib/models`, `lib/network` |
| `sbada-server/` | 메인 서버 Spring Boot 4 · Java 21 · H2 파일 DB · Flyway. 포트 **8097**. 회원·물건·교환·채팅·알림·AI + 결제 게이트웨이 |
| `sbada-payment/` | 결제 마이크로서비스(MSA 1단계). 포트 **8098**. 자기 DB, 시연용 지갑 · 계좌(RSA+AES 암호화) · 출금 · 추가 요금 |
| `docs/` | 설계 문서(architecture, algorithm, msa, database …) + `docs/handoff/`(인수인계) |
| `scripts/`, `*.command` | 맥에서 더블클릭하는 실행 스크립트(서버 실행, 시뮬레이터, 전체검사) |
| `training/` | Gemma QLoRA 미세조정(윈도우 RTX 3060 용) |

## 2. 실행 · 검사 명령
```bash
# 서버 테스트
cd sbada-server && ./gradlew test
cd sbada-payment && ./gradlew test
# 앱
cd sbada_app && flutter pub get && flutter analyze && flutter test
# 서버 실행(개발): 메인 → 결제 순서
cd sbada-server && ./gradlew bootRun --args='--spring.profiles.active=dev'
cd sbada-payment && ./gradlew bootRun
# 앱 실행(iOS 시뮬레이터)
cd sbada_app && flutter run -d <기기ID> --dart-define=API_BASE_URL=http://127.0.0.1:8097
```
- JDK 21 필요. 맥에서는 `scripts/_common.sh` 가 JAVA_HOME 을 잡아 준다.
- 완료 기준: 서버 두 프로젝트 테스트 통과 + `flutter analyze` 0 issues + `flutter test` 통과.

## 3. 코드 규칙
- 서버: 오류는 `ApiException(status, CODE, 한국어 메시지)` → `{"code","message","errors"}` JSON. 로그인 사용자는 `(Long) auth.getPrincipal()`.
- 서비스 간 통신: 결제 서비스 → 메인 서버는 아웃박스 이벤트(`/internal/events`, 헤더 `X-Internal-Token`). 다른 서비스 DB 를 직접 읽지 않는다.
- 앱: iOS 전용 모양은 `Apple.on`(lib/util/apple.dart)으로 분기, 안드로이드 모양은 그대로 둔다. 화면 이동은 `appRoute()`.
- 앱 색 · 크기는 `lib/theme/app_theme.dart` 의 `Sb.*` 를 쓴다. AI 아이콘은 `widgets/ai_sparkle.dart` 의 `AiSparkle`.
