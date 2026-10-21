# 카카오 콘솔 [플랫폼 > Android] 에 등록할 '키 해시'를 만든다(디버그 키 = 에뮬레이터·flutter run 빌드용).
# 키 해시 = 앱 서명 인증서의 SHA-1 을 Base64 로 바꾼 값. 공개해도 되는 값이지만 PC마다 다르다.
# 결과: 화면 + logs\kakao-keyhash.txt
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$logs = Join-Path $root 'logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$out = Join-Path $logs 'kakao-keyhash.txt'

$keystore = Join-Path $env:USERPROFILE '.android\debug.keystore'
if (-not (Test-Path $keystore)) {
    "디버그 키가 없습니다: $keystore  (앱을 한 번 빌드/실행하면 생깁니다)" | Tee-Object -FilePath $out
    exit 1
}

$candidates = @()
if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\keytool.exe') }
$candidates += 'C:\dev\jdk-21\bin\keytool.exe', 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
$keytool = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $keytool) { $keytool = 'keytool' }

$text = & $keytool -list -v -keystore $keystore -alias androiddebugkey -storepass android -keypass android 2>&1 | Out-String
if ($text -match 'SHA1:\s*([0-9A-Fa-f:]{59})') {
    $bytes = $matches[1].Split(':') | ForEach-Object { [Convert]::ToByte($_, 16) }
    $hash = [Convert]::ToBase64String([byte[]]$bytes)
    @(
        "카카오 콘솔 > 내 애플리케이션 > 플랫폼 > Android",
        "  패키지명 : com.sbada.sbada",
        "  키 해시  : $hash",
        "",
        "(디버그 키 기준. 나중에 출시용 키로 서명하면 그 키의 해시도 추가로 등록해야 합니다.)"
    ) | Tee-Object -FilePath $out
} else {
    "SHA1 을 찾지 못했습니다. keytool 출력:" | Tee-Object -FilePath $out
    $text | Tee-Object -FilePath $out -Append
    exit 1
}
