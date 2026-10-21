$ErrorActionPreference = 'Continue'
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root 'logs\gemini-img.txt'
function Say($m) { Add-Content -Path $out -Value $m -Encoding UTF8 }
Set-Content -Path $out -Value "==== gemini-img $(Get-Date -Format 'HH:mm:ss') ====" -Encoding UTF8
$f = Join-Path $root 'sbada-server\application-local.properties'
$line = (Get-Content $f -Encoding UTF8 | Where-Object { $_ -match '^\s*sbada\.ai\.google\.api-key\s*=' } | Select-Object -First 1)
$key = ($line -replace '^\s*sbada\.ai\.google\.api-key\s*=', '').Trim().Trim('"').Trim()
$img = Join-Path $root 'sbada-server\src\main\resources\seed\s05.jpg'
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($img))
$tmp = Join-Path $env:TEMP 'sbada-ai'; New-Item -ItemType Directory $tmp -Force | Out-Null
$body = '{"contents":[{"role":"user","parts":[{"text":"What is this item? Answer in one short sentence."},{"inlineData":{"mimeType":"image/jpeg","data":"' + $b64 + '"}}]}],"generationConfig":{"temperature":0.2,"maxOutputTokens":2048}}'
[IO.File]::WriteAllText("$tmp\img-body.json", $body)
foreach ($m in @('gemma-4-26b-a4b-it','gemma-4-31b-it')) {
  for ($t = 1; $t -le 2; $t++) {
    $code = curl.exe -s -m 100 -o "$tmp\img-res.json" -w "%{http_code}" -H "x-goog-api-key: $key" -H "Content-Type: application/json" --data-binary "@$tmp\img-body.json" "https://generativelanguage.googleapis.com/v1beta/models/${m}:generateContent"
    $txt = Get-Content "$tmp\img-res.json" -Raw -Encoding UTF8
    Say "$m try $t -> HTTP $code"
    Say $txt.Substring(0, [Math]::Min(500, $txt.Length))
    if ($code -eq '200') { break }
    Start-Sleep 3
  }
}
Remove-Item "$tmp\img-body.json","$tmp\img-res.json" -ErrorAction SilentlyContinue
Say 'DONE'
