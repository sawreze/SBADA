# 🌊 스바다 — AI가 값을 매겨 주는 물물교환 앱

> 쓰던 물건을 사진으로 찍으면 **AI(Gemma)가 중고 시세로 가격을 매기고**, **비슷한 가격대**의 물건을 찾아
> **채팅** 또는 **바로 바꾸기**(상대가 **수락**해야 성사)로 교환하는 Android 앱입니다.

| 구분 | 기술 (교재 기준) |
|---|---|
| 앱 | Flutter 3.47 · Dart 3.13 · `http` · `image_picker` · `shared_preferences` · Android 에뮬레이터 |
| 서버 | Spring Boot 4.1.1 · Java 21 · Gradle 9.7.1 · Spring Security + JWT(jjwt 0.13, HS256) |
| DB | H2 파일 DB(`./data/sbada`) · Spring Data JPA(Hibernate) |
| 실시간 | WebSocket(`/ws`) — 채팅·알림 / FCM(선택) — 앱이 꺼져 있을 때 |
| AI | Gemma 4 — Google AI Studio(`gemma-4-26b-a4b-it`) 또는 로컬 Ollama(`gemma4:e4b`), 없으면 기준가 추정 |

## 폴더

```
sbada/
├─ sbada-server/   Spring Boot 서버 (포트 8097)
├─ sbada_app/      Flutter 앱
├─ docs/           아키텍처·API 명세·DB·알고리즘·상태 규칙·시연 대본
└─ scripts/        cmd 로 실행·테스트하는 배치 파일
```

## 실행 순서 (cmd)

한 번에 점검하려면 `scripts\run-all-check.bat` 을 더블클릭하세요(서버 테스트 → 서버 실행 → smoke 테스트, 결과는 `logs\`). 서버 종료는 `scripts\stop-server.bat`.

```bat
cd C:\dev\projects\sbada\scripts
test-server.bat        :: 1) 서버 테스트 (JUnit + MockMvc + WebSocket)
run-server.bat         :: 2) 서버 실행 → 브라우저 http://127.0.0.1:8097/
smoke-test.bat         :: 3) (새 cmd 창) curl 로 로컬호스트 API 점검 → smoke-result.txt
setup-app.bat          :: 4) 앱 준비(최초 1회): flutter create(android) · pub get · analyze · test
run-app.bat            :: 5) 에뮬레이터 실행 상태에서 앱 실행 (API_BASE_URL=http://10.0.2.2:8097)
demo-send-request.bat  :: 6) 시연: 다른 사용자가 교환 요청 → 에뮬레이터에 푸시 알림
```

시연 계정(서버가 처음 켜질 때 자동 생성, 로컬 전용 공개 비밀번호): `minji` · `dohyun` · `junho` · `seoyeon` / `sbada1234`

## AI(Gemma) 연결 — 비밀값은 Git에 올리지 않기

`sbada-server/application-local.properties.example` 을 `application-local.properties` 로 복사해 값을 넣습니다.
이 파일은 `.gitignore` 에 들어 있어 공개 레포에 올라가지 않습니다. (환경변수 `GEMMA_API_KEY` 로도 가능)

- 클라우드: `sbada.ai.provider=google`, `sbada.ai.google.api-key=...` (Google AI Studio 키)
- 로컬: `ollama pull gemma4:e4b` 후 `sbada.ai.provider=ollama` (키 불필요)
- 둘 다 없으면 `fallback`: 카테고리 기준가 × 상태 감가율로 추정(시연이 멈추지 않도록)

자세한 내용은 `docs/` 를 보세요.


## 맥북(macOS)에서 실행하기

Windows 의 `.bat` 과 같은 일을 하는 `.sh` 스크립트가 `scripts/` 에 함께 들어 있습니다(같은 소스, 같은 서버 주소).

**처음 한 번 준비**
1. JDK 21 설치: `brew install --cask corretto@21` (또는 Temurin 21). 확인: `/usr/libexec/java_home -v 21`
2. Flutter SDK 설치 후 `flutter doctor` 가 Android toolchain 까지 ✓ 인지 확인
3. Android Studio → Device Manager 에서 에뮬레이터(AVD) 하나 만들기 (M 시리즈 맥은 **arm64** 이미지)
4. 압축을 푼 폴더에서 실행 권한 주기: `chmod +x scripts/*.sh sbada-server/gradlew`

**실행 순서 (터미널)**
```bash
cd ~/sbada/scripts            # 압축을 푼 위치
./test-server.sh              # 1) 서버 테스트
./run-server.sh               # 2) 서버 실행 → http://127.0.0.1:8097/  (종료 Ctrl+C)
./smoke-test.sh               # 3) (새 터미널 탭) curl 로 로컬호스트 점검 → smoke-result.txt
./setup-app.sh                # 4) 앱 준비(최초 1회)
./run-app.sh                  # 5) 에뮬레이터 실행 상태에서 앱 실행
./demo-send-request.sh        # 6) 시연: 준호 → 민지 교환 요청 → 에뮬레이터 푸시
```
한 번에: `./run-all-check.sh` (서버를 백그라운드로 켜 두고 점검, 종료는 `./stop-server.sh`).

에뮬레이터 안의 `10.0.2.2` 는 맥에서도 PC 의 `127.0.0.1` 을 가리키므로 앱 설정은 바꿀 필요가 없습니다.
AI 키 파일(`application-local.properties`)은 Git 에 올라가지 않으므로 맥에서도 따로 만들어 넣습니다.
