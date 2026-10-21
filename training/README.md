# 스바다 전용 Gemma 4 — QLoRA 미세조정(적은 데이터)

앱이 Gemma 에 보내는 두 가지 질문에 **스바다 형식 그대로** 답하도록 Gemma 4 E4B 를 조금 더 가르친다.
결과는 Ollama 로 띄워 서버 설정 한 줄로 연결한다(앱 코드 변경 없음).

| 무엇을 | 데이터 | 기대 효과 |
| --- | --- | --- |
| 가격 추정(AiPrompt) | 상품 50개 × (원래 상태 + 다른 상태) = 100개 | JSON 형식 고정, 원화 시세 · 상태 감가 감각 |
| 콘텐츠 검사(ModerationService) | 정상 56 + 욕설 · 선정 · 혐오 · 폭력 18 = 74개 | 보류 기준을 앱 규칙에 맞춤, 헷갈리는 정상 사례는 통과 |

합계 174개(학습 157 · 평가 17). 글(텍스트 힌트)만 학습하고 사진 인식 부분은 그대로 둔다.

## 왜 이 설정인가

- **모델: Gemma 4 E4B** — RTX 3060(12GB)에서 4비트로 올리고 LoRA 를 붙여 학습할 수 있는 크기. 지금 쓰는 26B 클라우드 모델은 12GB 에 안 들어간다.
- **QLoRA**: 원본은 4비트(NF4)로 얼려 두고 r=8 짜리 작은 어댑터만 학습 → 메모리 적게, 데이터 적게.
- **답 부분만 손실 계산**: 긴 질문 문장을 외우지 않고 '답 쓰는 법'만 배운다.
- **3 epoch · lr 2e-4 · 배치 1 × 누적 8**: 데이터가 적어 과적합되지 않도록 짧게.

## 1. 준비(처음 한 번)

1. Python 3.11 설치(설치 화면에서 *Add python.exe to PATH* 체크)
2. `training\setup.bat` 실행 → `training\.venv` 에 PyTorch(CUDA) · transformers · peft · bitsandbytes 설치, 마지막 줄에 GPU 이름이 나오면 OK
3. Hugging Face 가입 → Gemma 4 모델 페이지에서 사용 동의 → `training\.venv\Scripts\activate` 후 `huggingface-cli login`
   - 모델 이름 기본값은 `google/gemma-4-E4B-it`. **Hugging Face 에서 정확한 이름을 확인**하고 다르면 `train.bat --model 정확한이름` 으로 실행

## 2. 학습

`training\train.bat` — 데이터 다시 만들기 → 학습 전 평가 → 학습 → 학습 후 평가 → LoRA 합치기. 기록: `logs\qlora-train.log`

학습 전 · 후 평가 줄을 비교한다:
`[학습 후] JSON 형식 17/17 · 가격 오차 0.xxx(≈ ±xx%) · 검사 정답 x/x`

## 3. 데이터 바꾸기

- 상품(V4 SQL)이나 서버 프롬프트(AiPrompt · ModerationService)를 바꾸면 `training\MakeDataset.java` 를 같이 고친 뒤 다시 학습한다(질문 문장이 서버와 같아야 효과가 있다).
- 검사 사례는 `MakeDataset.java` 의 `bad` · `fine` 표에 한 줄씩 추가하면 된다.

## 4. Ollama 로 앱에 연결

1. GGUF 로 바꾸기(llama.cpp 의 변환 스크립트, Gemma 4 지원 버전 필요):
   `python llama.cpp\convert_hf_to_gguf.py training\out\merged --outfile training\out\sbada-gemma-f16.gguf`
   → `llama-quantize training\out\sbada-gemma-f16.gguf training\out\sbada-gemma-q4_k_m.gguf Q4_K_M`
2. `ollama create sbada-gemma -f training\Modelfile`
3. `sbada-server\application-local.properties` 에
   `sbada.ai.provider=ollama` · `sbada.ai.ollama.model=sbada-gemma` → `scripts\restart-server.bat`
4. 앱에서 사진을 올려 가격 · 검사 결과를 확인. 되돌리려면 `sbada.ai.provider=google`

## 주의

- 학습 결과(`training\out`, 수 GB)와 `.venv` 는 Git · 소스 zip 에 넣지 않는다(.gitignore).
- 데이터가 174개뿐이라 '새로운 지식'보다는 **형식 · 기준 맞추기** 용도다. 시세를 더 정확히 하려면 실제 거래 기록을 데이터에 더한다.
