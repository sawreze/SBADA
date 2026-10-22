# 소스 · 학습한 LoRA 를 바탕화면 스바다_인수인계\04_소스코드_백업 으로 복사(zip)
# 비밀값 파일(application-local.properties, local.properties, 키 저장소)과 DB · 빌드 결과는 넣지 않는다.
$ErrorActionPreference = 'Stop'
$root  = Split-Path -Parent $PSScriptRoot
$out   = Join-Path ([Environment]::GetFolderPath('Desktop')) '스바다_인수인계\04_소스코드_백업'
$stamp = Get-Date -Format 'yyyy-MM-dd'
$tmp   = Join-Path $env:TEMP 'sbada-src-backup'
function Log($m) { Write-Output ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }

if (-not (Test-Path $out)) { New-Item -ItemType Directory -Path $out | Out-Null }
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
Log "소스 복사 시작: $root"
$xd = @('.git', '.idea', 'build', '.dart_tool', '.gradle', '.kotlin', 'node_modules', '.venv', '__pycache__',
        (Join-Path $root 'logs'), (Join-Path $root 'sbada-server\data'),
        (Join-Path $root 'training\out-final'), (Join-Path $root 'training\out-vision'))
$xf = @('application-local.properties', 'local.properties', 'key.properties', '*.jks', '*.keystore',
        'google-services.json', '*.mv.db', '*.trace.db', '*.safetensors')
& robocopy $root $tmp /E /XD $xd /XF $xf /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy 실패 (코드 $LASTEXITCODE)" }

$leak = Get-ChildItem $tmp -Recurse -File -Include 'application-local.properties', 'local.properties', 'key.properties', '*.jks'
if ($leak) { throw ("비밀값 파일이 섞여 있어 중단: " + ($leak.FullName -join ', ')) }

$zip = Join-Path $out "sbada-source-$stamp.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $tmp '*') -DestinationPath $zip -CompressionLevel Optimal
Log ("소스 zip: {0} ({1:N1} MB)" -f $zip, ((Get-Item $zip).Length / 1MB))

$lora = Join-Path $root 'training\out-final\lora'
if (Test-Path $lora) {
    $lz = Join-Path $out "sbada-lora-$stamp.zip"
    if (Test-Path $lz) { Remove-Item $lz -Force }
    Compress-Archive -Path $lora, (Join-Path $root 'training\out-final\result.json') -DestinationPath $lz -CompressionLevel Fastest
    Log ("학습한 LoRA zip: {0} ({1:N1} MB)" -f $lz, ((Get-Item $lz).Length / 1MB))
}
Remove-Item $tmp -Recurse -Force
Log "DONE"
