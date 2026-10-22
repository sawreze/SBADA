# 학습이 끝나면 자동으로: 학습한 AI · 서버 API 점검 → flutter 검사 → 에뮬레이터에 앱 설치 · 실행 · 화면 저장 · 오류 로그 검사
# 결과 요약: logs\test-after-training.txt   화면: logs\screens\   앱 오류 로그: logs\test-logcat.txt
$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $PSScriptRoot
$Logs = Join-Path $Root "logs"
$Shots = Join-Path $Logs "screens"
New-Item -ItemType Directory -Force -Path $Shots | Out-Null
$Sum = Join-Path $Logs "test-after-training.txt"
"[$(Get-Date -Format 'HH:mm:ss')] 학습 후 자동 점검 시작" | Set-Content -Encoding UTF8 $Sum
function Log($m) { $line = "[$(Get-Date -Format 'HH:mm:ss')] $m"; Write-Host $line; Add-Content -Encoding UTF8 $Sum $line }
function Ok($name, $cond, $detail) { if ($cond) { Log "PASS  $name  $detail" } else { Log "FAIL  $name  $detail" } }
$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$Adb = Join-Path $Sdk "platform-tools\adb.exe"
$Emu = Join-Path $Sdk "emulator\emulator.exe"

# ---- 0) 학습 · 전환 끝날 때까지 기다리기(최대 4시간) ----
Log "학습이 끝나길 기다리는 중(training\out-final\DONE + AI 서버 11435 + 서버 8097)"
$deadline = (Get-Date).AddHours(4)
while ((Get-Date) -lt $deadline) {
  $done = Test-Path (Join-Path $Root "training\out-final\DONE")
  $ai = try { (Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 http://127.0.0.1:11435/api/tags).StatusCode -eq 200 } catch { $false }
  $st = try { Invoke-RestMethod -TimeoutSec 5 http://127.0.0.1:8097/api/ai/status } catch { $null }
  if ($done -and $ai -and $st) { break }
  Start-Sleep -Seconds 60
}
Start-Sleep -Seconds 20

# ---- 1) 서버 · AI 상태 ----
$st = try { Invoke-RestMethod -TimeoutSec 10 http://127.0.0.1:8097/api/ai/status } catch { $null }
Log ("AI 상태: " + ($st | ConvertTo-Json -Compress))
Ok "학습한 AI 서버(11435)" ($(try { (Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 http://127.0.0.1:11435/api/tags).StatusCode } catch { 0 }) -eq 200) ""
$health = try { Invoke-RestMethod -TimeoutSec 10 http://127.0.0.1:8097/api/health } catch { $null }
Ok "스바다 서버 /api/health" ($null -ne $health) ""

# ---- 2) 앱과 같은 순서로 API 점검: 로그인 → 사진 올리기 → AI 가격 분석 ----
$login = try { Invoke-RestMethod -Method Post -ContentType "application/json; charset=utf-8" -TimeoutSec 15 `
  -Body ([Text.Encoding]::UTF8.GetBytes('{"username":"minji","password":"sbada1234"}')) http://127.0.0.1:8097/api/auth/login } catch { $null }
Ok "로그인(minji)" ($null -ne $login.accessToken) ""
$seed = Join-Path $Root "sbada-server\src\main\resources\seed"
foreach ($p in @("p03", "p21", "p33", "p41", "s05")) {
  $img = Join-Path $seed "$p.jpg"
  if (-not (Test-Path $img) -or -not $login) { continue }
  $up = & curl.exe -s -H "Authorization: Bearer $($login.accessToken)" -F "file=@$img;type=image/jpeg" http://127.0.0.1:8097/api/images | ConvertFrom-Json
  if (-not $up.id) { Ok "사진 올리기 $p" $false "응답 없음"; continue }
  $t0 = Get-Date
  $an = try { Invoke-RestMethod -Method Post -ContentType "application/json" -TimeoutSec 130 `
    -Headers @{ Authorization = "Bearer $($login.accessToken)" } -Body ("{""imageId"":$($up.id)}") http://127.0.0.1:8097/api/ai/analyze } catch { $null }
  $sec = [int]((Get-Date) - $t0).TotalSeconds
  if ($an) {
    Ok "AI 분석 $p" ($an.provider -eq "ollama") ("provider=$($an.provider) model=$($an.model) $($an.suggestedTitle) $($an.price)원 ($($an.priceMin)~$($an.priceMax)) ${sec}초")
    Ok "  응답 시간 90초 이내 $p" ($sec -le 90) "${sec}초"
  } else { Ok "AI 분석 $p" $false "오류 또는 시간 초과 ${sec}초" }
}

# ---- 3) 앱 코드 검사 ----
Push-Location (Join-Path $Root "sbada_app")
$an = & flutter analyze 2>&1 | Out-String
Ok "flutter analyze" ($an -match "No issues found") (($an -split "`n" | Select-Object -Last 2) -join " ")
$ft = & flutter test 2>&1 | Out-String
Ok "flutter test" ($ft -match "All tests passed") (($ft -split "`n" | Where-Object { $_ -match "\+\d+" } | Select-Object -Last 1))
$ft | Set-Content -Encoding UTF8 (Join-Path $Logs "test-flutter.txt")

# ---- 4) 에뮬레이터: 켜기 → 앱 빌드(profile) · 설치 · 실행 → 화면 저장 · 오류 검사 ----
$dev = (& $Adb devices) -match "emulator-\d+\s+device"
if (-not $dev) {
  $avd = (& $Emu -list-avds | Select-Object -First 1)
  Log "에뮬레이터 켜기: $avd"
  Start-Process -FilePath $Emu -ArgumentList @("-avd", $avd, "-gpu", "host", "-no-boot-anim")
  & $Adb wait-for-device
  for ($i = 0; $i -lt 90; $i++) { if ((& $Adb shell getprop sys.boot_completed 2>$null) -match "1") { break }; Start-Sleep 2 }
}
Ok "에뮬레이터 부팅" (((& $Adb shell getprop sys.boot_completed 2>$null) -join "") -match "1") ""
$b = & flutter build apk --profile --dart-define=API_BASE_URL=http://10.0.2.2:8097 2>&1 | Out-String
$apk = Join-Path $Root "sbada_app\build\app\outputs\flutter-apk\app-profile.apk"
Ok "앱 빌드(profile)" (Test-Path $apk) (($b -split "`n" | Select-Object -Last 2) -join " ")
Pop-Location
& $Adb install -r $apk 2>&1 | Out-Null
& $Adb logcat -c
& $Adb shell am force-stop com.sbada.sbada
& $Adb shell am start -n com.sbada.sbada/.MainActivity 2>&1 | Out-Null
foreach ($n in 1..4) {
  Start-Sleep -Seconds 10
  $png = Join-Path $Shots ("app-{0:00}.png" -f $n)
  & cmd /c "`"$Adb`" exec-out screencap -p > `"$png`""
}
$pid2 = (& $Adb shell pidof com.sbada.sbada) -join ""
Ok "앱 실행 중(40초 뒤)" ($pid2 -match "\d") "pid=$pid2"
$lc = & $Adb logcat -d 2>&1
$lc | Set-Content -Encoding UTF8 (Join-Path $Logs "test-logcat.txt")
$bad = $lc | Select-String -Pattern "FATAL EXCEPTION|E/flutter|E/AndroidRuntime|Unhandled Exception" 
Ok "앱 오류 로그 없음" ($bad.Count -eq 0) ("오류 줄 $($bad.Count)개" + $(if ($bad.Count) { ": " + ($bad | Select-Object -First 3 | ForEach-Object { $_.Line.Trim() }) -join " / " } else { "" }))
Log "점검 끝. 화면: logs\screens\app-01~04.png"
Add-Content -Encoding UTF8 $Sum "DONE"
