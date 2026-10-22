import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// 한 가지 모드(다크 또는 화이트)의 색 묶음
class SbPalette {
  const SbPalette({
    required this.dark,
    required this.navy,
    required this.text2,
    required this.text3,
    required this.line,
    required this.bg,
    required this.base,
    required this.card,
    required this.chip,
    required this.blueSoft,
    required this.mintText,
    required this.green,
    required this.greenSoft,
    required this.red,
    required this.orange,
    required this.glassFill,
    required this.glassStroke,
    required this.glassHighlight,
    required this.glassBar,
    required this.glassButton,
    required this.glassShadow,
    required this.pinFill,
    required this.groupedBg,
    required this.groupCard,
    required this.tabSelected,
  });

  final bool dark;
  final Color navy, text2, text3, line, bg, base, card, chip;
  final Color blueSoft, mintText, green, greenSoft, red, orange;
  final Color glassFill, glassStroke, glassHighlight, glassBar, glassButton, glassShadow, pinFill;

  /// 묶음 카드 화면(계정정보 · 앱 설정): 바탕 / 카드 / 하단 탭바의 선택 칸
  final Color groupedBg, groupCard, tabSelected;

  static const darkMode = SbPalette(
    dark: true,
    navy: Color(0xFFF2F4F7),
    text2: Color(0xFFB6BDC7),
    text3: Color(0xFF8A919C),
    line: Color(0xFF2A2D33),
    bg: Color(0xFF22252B),
    base: Color(0xFF111215),
    card: Color(0xFF1A1C20),
    chip: Color(0xFF2B2E35),
    blueSoft: Color(0xFF1D3A2D),
    mintText: Color(0xFF93E1B7),
    green: Color(0xFF5FD3A0),
    greenSoft: Color(0xFF123B2E),
    red: Color(0xFFFF5A65),
    orange: Color(0xFFFF9A55),
    glassFill: Color(0x2EFFFFFF),
    glassStroke: Color(0x40FFFFFF),
    glassHighlight: Color(0x26FFFFFF),
    glassBar: Color(0x33202329),
    glassButton: Color(0x40202329),
    glassShadow: Color(0x66000000),
    pinFill: Color(0xE62A2D34),
    groupedBg: Color(0xFF111215), // = base(다크는 예전 그대로)
    groupCard: Color(0xFF1A1C20), // = card
    tabSelected: Color(0x24FFFFFF), // 유리 탭바 위 흰 빛 알약
  );

  static const lightMode = SbPalette(
    dark: false,
    navy: Color(0xFF191F28),
    text2: Color(0xFF4E5968),
    text3: Color(0xFF8B95A1),
    line: Color(0xFFE5E8EB),
    bg: Color(0xFFF2F4F6),
    base: Color(0xFFFFFFFF),
    card: Color(0xFFFFFFFF),
    chip: Color(0xFFF0F2F5),
    blueSoft: Color(0xFFE2F6EB),
    // 흰 바탕에서 #93E1B7 글자는 잘 안 읽혀서, 글자·아이콘·테두리만 같은 계열의 짙은 민트를 쓴다
    mintText: Color(0xFF137A50), // 흰 바탕 대비 5.35:1, 민트 알약(blueSoft) 위 4.74:1
    green: Color(0xFF16A36A),
    greenSoft: Color(0xFFE2F6EB),
    red: Color(0xFFF04452),
    orange: Color(0xFFFF8A3D),
    glassFill: Color(0xB3FFFFFF),
    glassStroke: Color(0x1F000000),
    glassHighlight: Color(0x80FFFFFF),
    glassBar: Color(0xC7FFFFFF),
    glassButton: Color(0xD9FFFFFF),
    glassShadow: Color(0x2E000000),
    pinFill: Color(0xF2FFFFFF),
    // 화이트 모드 계정정보(2026-10-22 사용자 요청): 연회색 바탕 위 흰 카드
    groupedBg: Color(0xFFF2F4F6),
    groupCard: Color(0xFFFFFFFF),
    // 화이트 모드 하단 탭바(당근 스타일): 흰 캡슐 안 선택 칸 = 회색 둥근 배경
    tabSelected: Color(0xFFECEEF1),
  );
}

/// 스바다 디자인 토큰 — 다크 / 화이트 모드 + 리퀴드 글래스(반투명 유리).
/// 화면 코드는 이 이름들만 쓰므로, 색을 바꿀 때는 여기만 고친다.
///   navy = 기본 글자색   base = 화면 바탕   card = 카드·시트   bg = 입력칸·채움색
///   blue = 포인트(민트 #93E1B7, 버튼·말풍선 같은 '바탕'용)   mintText = 포인트 색 글자·아이콘·테두리
class Sb {
  Sb._();

  /// 현재 모드. 계정정보 오른쪽 위 버튼으로 바꾸고 기기에 저장한다(theme_controller.dart).
  static final ValueNotifier<bool> darkMode = ValueNotifier(true);
  static SbPalette get p => darkMode.value ? SbPalette.darkMode : SbPalette.lightMode;
  static bool get isDark => darkMode.value;

  /// 포인트 색(민트 #93E1B7). 두 모드 공통. 이름은 예전 코드와 맞추려고 blue 로 둔다.
  static const blue = Color(0xFF93E1B7);

  /// 포인트 색 위에 올리는 글자·아이콘(밝은 민트 위에서는 짙은 색이 잘 읽힌다)
  static const onAccent = Color(0xFF0D1F16);

  /// 실행 화면 배경 = 앱 아이콘 배경색(assets/app_icon.png 에서 뽑음). 다크/화이트 모드와 상관없이 같다.
  /// Android 첫 화면(res/values/colors.xml splash_bg)도 같은 값.
  static const splashBg = Color(0xFF83E8B1);

  static Color get mintText => p.mintText;
  static Color get blueSoft => p.blueSoft;
  static Color get navy => p.navy;
  static Color get text2 => p.text2;
  static Color get text3 => p.text3;
  static Color get line => p.line;
  static Color get bg => p.bg;
  static Color get base => p.base;
  static Color get card => p.card;
  static Color get chip => p.chip;
  static Color get green => p.green;
  static Color get greenSoft => p.greenSoft;
  static Color get red => p.red;
  static Color get orange => p.orange;

  /// 유리(글래스) 표면
  static Color get glassFill => p.glassFill;
  static Color get glassStroke => p.glassStroke;
  static Color get glassHighlight => p.glassHighlight;
  static Color get glassBar => p.glassBar;
  static Color get glassButton => p.glassButton;
  static Color get glassShadow => p.glassShadow;
  static Color get pinFill => p.pinFill;

  /// 묶음 카드 화면(계정정보 · 앱 설정) 바탕 · 카드, 하단 탭바 선택 칸
  static Color get groupedBg => p.groupedBg;
  static Color get groupCard => p.groupCard;
  static Color get tabSelected => p.tabSelected;

  static const radius = 20.0;
  static const gap = 16.0;

  /// 떠 있는 하단바(높이 64 + 아래 여백)에 가려지지 않도록 목록 끝에 두는 여백
  static const tabBarSpace = 104.0;

  static BorderRadius get r => BorderRadius.circular(radius);
}

ThemeData buildTheme() {
  final dark = Sb.isDark;
  final brightness = dark ? Brightness.dark : Brightness.light;
  final scheme = ColorScheme.fromSeed(
    seedColor: Sb.blue,
    primary: dark ? Sb.blue : Sb.mintText,
    surface: Sb.base,
    brightness: brightness,
  ).copyWith(onSurface: Sb.navy, surfaceContainerHighest: Sb.bg, outline: Sb.line);
  return ThemeData(
    useMaterial3: true,
    brightness: brightness,
    colorScheme: scheme,
    scaffoldBackgroundColor: Sb.base,
    canvasColor: Sb.base,
    appBarTheme: AppBarTheme(
      backgroundColor: Sb.base,
      foregroundColor: Sb.navy,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
      systemOverlayStyle: SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: dark ? Brightness.light : Brightness.dark,
        statusBarBrightness: dark ? Brightness.dark : Brightness.light,
      ),
      titleTextStyle: TextStyle(fontSize: 21, fontWeight: FontWeight.w800, color: Sb.navy),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: Sb.blue,
        foregroundColor: Sb.onAccent,
        disabledBackgroundColor: Sb.chip,
        disabledForegroundColor: Sb.text3,
        minimumSize: const Size.fromHeight(54),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        textStyle: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: Sb.navy,
        minimumSize: const Size.fromHeight(54),
        side: BorderSide(color: Sb.line),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
      ),
    ),
    textButtonTheme: TextButtonThemeData(style: TextButton.styleFrom(foregroundColor: Sb.mintText)),
    chipTheme: ChipThemeData(
      backgroundColor: Sb.chip,
      selectedColor: Sb.navy,
      labelStyle: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: Sb.navy),
      secondaryLabelStyle: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: Sb.base),
      side: BorderSide.none,
      shape: const StadiumBorder(),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
    ),
    segmentedButtonTheme: SegmentedButtonThemeData(
      style: SegmentedButton.styleFrom(
        backgroundColor: Sb.bg,
        foregroundColor: Sb.text2,
        selectedBackgroundColor: Sb.blueSoft,
        selectedForegroundColor: Sb.navy,
        side: BorderSide(color: Sb.line),
      ),
    ),
    bottomSheetTheme: BottomSheetThemeData(
      backgroundColor: Sb.card,
      surfaceTintColor: Colors.transparent,
      showDragHandle: true,
      dragHandleColor: Sb.text3,
    ),
    dialogTheme: DialogThemeData(backgroundColor: Sb.card, surfaceTintColor: Colors.transparent),
    snackBarTheme: SnackBarThemeData(contentTextStyle: TextStyle(color: Sb.navy)),
    listTileTheme: ListTileThemeData(textColor: Sb.navy, iconColor: Sb.text2),
    progressIndicatorTheme: ProgressIndicatorThemeData(color: dark ? Sb.blue : Sb.mintText),
    dividerColor: Sb.line,
    dividerTheme: DividerThemeData(color: Sb.line, thickness: 0.6),
  );
}

/// 입력칸 모양을 한 곳에서 정한다. 라벨을 칸 위에 따로 두는 화면(디자인 시안)은 label 을 null 로 준다.
InputDecoration sbInput(String? label, {String? hint, String? errorText, Widget? suffix}) {
  return InputDecoration(
    labelText: label,
    hintText: hint,
    errorText: errorText,
    suffixIcon: suffix,
    filled: true,
    fillColor: Sb.bg,
    labelStyle: TextStyle(color: Sb.text3),
    hintStyle: TextStyle(color: Sb.text3),
    contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
    border: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide.none),
    enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide.none),
    focusedBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(14),
      borderSide: BorderSide(color: Sb.mintText, width: 1.5),
    ),
  );
}

// 글자 스타일도 모드에 따라 색이 바뀌므로 상수(const)가 아니라 그때그때 만든다(getter).
TextStyle get sbTitle => TextStyle(fontSize: 24, fontWeight: FontWeight.w800, color: Sb.navy, height: 1.35);
TextStyle get sbH2 => TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: Sb.navy);
TextStyle get sbBody => TextStyle(fontSize: 15, color: Sb.text2, height: 1.5);
TextStyle get sbCaption => TextStyle(fontSize: 13, color: Sb.text3);
