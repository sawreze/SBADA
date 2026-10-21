# Diagnose Gemini API 400. Prints only key FORMAT info, never the key itself.
$ErrorActionPreference = 'Continue'
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root 'logs\gemini-diag.txt'
function Say($m) { Add-Content -Path $out -Value $m -Encoding UTF8 }
Set-Content -Path $out -Value "==== gemini-diag $(Get-Date -Format 'HH:mm:ss') ====" -Encoding UTF8
$f = Join-Path $root 'sbada-server\application-local.properties'
$line = (Get-Content $f -Encoding UTF8 | Where-Object { $_ -match '^\s*sbada\.ai\.google\.api-key\s*=' } | Select-Object -First 1)
$raw = ($line -replace '^\s*sbada\.ai\.google\.api-key\s*=', '')
Say ("lines in file: " + (Get-Content $f).Count)
Say ("raw value length: " + $raw.Length + ", starts with quote: " + $raw.StartsWith('"') + ", ends with quote: " + $raw.EndsWith('"') + ", has leading/trailing space: " + ($raw -ne $raw.Trim()))
$key = $raw.Trim().Trim('"').Trim()
Say ("clean key length: " + $key.Length + ", first 3 chars: " + $key.Substring(0, [Math]::Min(3,$key.Length)))
$tmp = Join-Path $env:TEMP 'sbada-ai'; New-Item -ItemType Directory $tmp -Force | Out-Null
Say '-- list models (filter gemma) --'
$code = curl.exe -s -o "$tmp\models.json" -w "%{http_code}" -H "x-goog-api-key: $key" "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200"
Say "HTTP $code"
$txt = Get-Content "$tmp\models.json" -Raw -Encoding UTF8
if ($code -eq '200') { ($txt | ConvertFrom-Json).models | Where-Object { $_.name -match 'gemma' } | ForEach-Object { Say $_.name } }
else { Say $txt.Substring(0, [Math]::Min(800, $txt.Length)) }
Say '-- generateContent gemma-4-26b-a4b-it --'
Set-Content "$tmp\body.json" '{"contents":[{"parts":[{"text":"Say OK"}]}]}' -Encoding ASCII
$code2 = curl.exe -s -o "$tmp\gen.json" -w "%{http_code}" -H "x-goog-api-key: $key" -H "Content-Type: application/json" --data-binary "@$tmp\body.json" "https://generativelanguage.googleapis.com/v1beta/models/gemma-4-26b-a4b-it:generateContent"
Say "HTTP $code2"
$t2 = Get-Content "$tmp\gen.json" -Raw -Encoding UTF8
Say $t2.Substring(0, [Math]::Min(800, $t2.Length))
Remove-Item "$tmp\models.json","$tmp\gen.json","$tmp\body.json" -ErrorAction SilentlyContinue
Say 'DONE'
