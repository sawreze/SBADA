# sbada: restart server and test real Gemma price analysis (key value is never printed)
$ErrorActionPreference = 'Continue'
$root = Split-Path $PSScriptRoot -Parent
$logs = Join-Path $root 'logs'
if (-not (Test-Path $logs)) { New-Item -ItemType Directory $logs | Out-Null }
$out = Join-Path $logs 'ai-check.txt'
function Say($m) { Add-Content -Path $out -Value $m -Encoding UTF8 }
Set-Content -Path $out -Value "==== ai-check $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ====" -Encoding UTF8
Say ("local properties exists: " + (Test-Path (Join-Path $root 'sbada-server\application-local.properties')))
Get-NetTCPConnection -LocalPort 8097 -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue; Say "stopped old server pid $($_.OwningProcess)" }
Start-Sleep 3
if (-not $env:JAVA_HOME -and (Test-Path 'C:\dev\jdk-21\bin\java.exe')) { $env:JAVA_HOME = 'C:\dev\jdk-21' }
if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME\bin;$env:PATH" }
$srv = Join-Path $root 'sbada-server'
$slog = Join-Path $logs '2-server.log'
Start-Process cmd -ArgumentList "/c cd /d `"$srv`" && call gradlew.bat bootRun --console=plain --args=--spring.profiles.active=dev > `"$slog`" 2>&1" -WindowStyle Minimized
$base = 'http://127.0.0.1:8097'
$up = $false
for ($i = 0; $i -lt 90; $i++) { try { Invoke-WebRequest "$base/api/health" -UseBasicParsing -TimeoutSec 3 | Out-Null; $up = $true; break } catch { Start-Sleep 2 } }
Say "server up: $up"
if (-not $up) { Say 'DONE'; exit }
$tmp = Join-Path $env:TEMP 'sbada-ai'
New-Item -ItemType Directory $tmp -Force | Out-Null
curl.exe -s -o "$tmp\status.json" "$base/api/ai/status"
Say ("ai status: " + (Get-Content "$tmp\status.json" -Raw -Encoding UTF8))
Set-Content "$tmp\login.json" '{"username":"minji","password":"sbada1234"}' -Encoding ASCII
curl.exe -s -o "$tmp\token.json" -H "Content-Type: application/json" --data-binary "@$tmp\login.json" "$base/api/auth/login"
$token = (Get-Content "$tmp\token.json" -Raw | ConvertFrom-Json).accessToken
$img = Join-Path $srv 'src\main\resources\seed\s05.jpg'
curl.exe -s -o "$tmp\img.json" -H "Authorization: Bearer $token" -F "file=@$img;type=image/jpeg" "$base/api/images"
$imageId = (Get-Content "$tmp\img.json" -Raw | ConvertFrom-Json).id
Say "uploaded image id: $imageId"
Set-Content "$tmp\analyze.json" ('{"imageId":' + $imageId + '}') -Encoding ASCII
curl.exe -s -m 110 -o "$tmp\result.json" -w "HTTP %{http_code}" -H "Authorization: Bearer $token" -H "Content-Type: application/json" --data-binary "@$tmp\analyze.json" "$base/api/ai/analyze" | ForEach-Object { Say $_ }
Say ("analyze result: " + (Get-Content "$tmp\result.json" -Raw -Encoding UTF8))
Say '---- server log lines about Gemma ----'
Get-Content $slog -Encoding UTF8 | Select-String -Pattern 'Gemma|gemma|AI ' | Select-Object -Last 15 | ForEach-Object { Say $_.Line }
Remove-Item "$tmp\token.json","$tmp\login.json" -ErrorAction SilentlyContinue
Say 'DONE'
