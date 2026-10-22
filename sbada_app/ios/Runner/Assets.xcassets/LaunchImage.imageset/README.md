# 스바다 iOS 시작 화면

- `LaunchImage*.png` = 앱 아이콘의 곰 · 손 그림(흰색, 투명 배경), 가로 170pt (1x 170px · 2x 340px · 3x 510px)
- 배경색은 `Base.lproj/LaunchScreen.storyboard` 의 #83E8B1(앱 아이콘 배경색, Flutter `Sb.splashBg`)
- Flutter 실행 화면(`SwapLogo`, 그림 가로 170)과 같은 크기 · 같은 자리라 넘어갈 때 그림이 튀지 않는다
- 원본: `sbada_app/assets/splash_left.png` + `splash_right.png`
