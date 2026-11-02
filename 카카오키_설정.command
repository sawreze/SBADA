#!/bin/bash
# 카카오 네이티브 앱 키를 이 맥에 넣는다(아이폰 · 안드로이드 둘 다). 키는 화면에 안 보이고, Git 에서 빠지는 파일에만 저장된다.
#  - 아이폰: sbada_app/ios/Flutter/Secrets.xcconfig   (KAKAO_NATIVE_APP_KEY)
#  - 안드로이드: sbada_app/android/local.properties   (kakao.nativeAppKey)
ROOT="$HOME/dev/SBADA/sbada_app"
echo "카카오 네이티브 앱 키를 붙여 넣고 Enter 를 누르세요(⌘V — 화면에는 안 보여요)."
echo "(카카오 개발자 콘솔 > 내 애플리케이션 > 앱 키 > 네이티브 앱 키, 또는 윈도우 PC 의 android/local.properties 에 있는 값)"
read -rs KEY; echo
KEY="$(printf '%s' "$KEY" | tr -d '[:space:]')"
if [ -z "$KEY" ]; then echo "키가 비었어요. 다시 실행해 주세요."; read -r -p "Enter 를 누르면 닫혀요"; exit 1; fi
S="$ROOT/ios/Flutter/Secrets.xcconfig"
printf '// Git 제외 파일. 카카오 네이티브 앱 키\nKAKAO_NATIVE_APP_KEY = %s\n' "$KEY" > "$S"; chmod 600 "$S"
LP="$ROOT/android/local.properties"
grep -v '^kakao.nativeAppKey=' "$LP" > "$LP.tmp" 2>/dev/null; echo "kakao.nativeAppKey=$KEY" >> "$LP.tmp"; mv "$LP.tmp" "$LP"; chmod 600 "$LP"
cd "$HOME/dev/SBADA" && if git check-ignore -q "$S" && git check-ignore -q "$LP"; then echo "[OK] 두 파일 모두 Git 제외 확인"; else echo "[주의] Git 제외가 안 돼 있어요 — 커밋하지 마세요"; fi
echo "[OK] 저장했어요(${#KEY}자). 앱을 완전히 다시 빌드해야 적용돼요: 터미널의 flutter 에서 q → 테스트_실행.command"
echo "※ 카카오 콘솔 > 플랫폼 > iOS 에 번들 ID com.sbada.sbada 가 등록돼 있어야 아이폰 로그인이 돼요."
read -r -p "Enter 를 누르면 닫혀요"
