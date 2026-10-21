# 스바다 시연 도우미: 준호(junho)가 민지(minji)의 물건에 '바로 바꾸기' 요청을 보낸다.
# 이미 대기 중인 조합(409)은 건너뛰고, 가격이 가장 가까운 다음 조합으로 다시 시도한다.
param([string]$From = 'junho', [string]$To = 'minji', [string]$Base = 'http://127.0.0.1:8097')
$ErrorActionPreference = 'Stop'
$log = Join-Path $PSScriptRoot '..\logs\demo.log'
function Say($m) { Write-Host $m; Add-Content -Path $log -Value $m -Encoding UTF8 }
function Api($Method, $Uri, $Headers = @{}, $Body = $null) {
  # PowerShell 5.1 은 charset 이 없는 JSON 을 ISO-8859-1 로 읽어 한글이 깨지므로 UTF-8 로 직접 해석한다
  $p = @{ Method = $Method; Uri = $Uri; Headers = $Headers; UseBasicParsing = $true; ContentType = 'application/json; charset=utf-8' }
  if ($Body -ne $null) { $p.Body = [System.Text.Encoding]::UTF8.GetBytes($Body) }
  $r = Invoke-WebRequest @p
  $text = [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
  return $text | ConvertFrom-Json
}
function Login($u) {
  return Api 'Post' "$Base/api/auth/login" @{} (@{ username = $u; password = 'sbada1234' } | ConvertTo-Json)
}
Set-Content -Path $log -Value "==== demo $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ====" -Encoding UTF8
$sender = Login $From
$receiver = Login $To
$h = @{ Authorization = "Bearer $($sender.accessToken)" }
$all = Api 'Get' "$Base/api/items?limit=100"
$targets = @($all.items | Where-Object { $_.owner.id -eq $receiver.user.id -and $_.status -eq 'AVAILABLE' })
$mine = @((Api 'Get' "$Base/api/me/items" $h).items | Where-Object { $_.status -eq 'AVAILABLE' })
if ($targets.Count -eq 0 -or $mine.Count -eq 0) { Say 'NO_ITEMS: 교환할 물건이 없습니다.'; exit 1 }

# 모든 조합을 가격 비율이 가까운 순서로 시도
$pairs = foreach ($t in $targets) { foreach ($m in $mine) {
  [pscustomobject]@{ t = $t; m = $m; d = [math]::Abs([math]::Log([double]$m.price / [double]$t.price)) } } }
foreach ($p in ($pairs | Sort-Object d)) {
  $req = @{ targetItemId = $p.t.id; offeredItemId = $p.m.id; message = '시연용 요청이에요!' } | ConvertTo-Json
  try {
    $r = Api 'Post' "$Base/api/exchanges" $h $req
    Say ("OK: {0}의 '{1}'({2}원) -> {3}의 '{4}'({5}원) 교환 요청 #{6} 상태 {7}" -f $sender.user.nickname, $p.m.title, $p.m.price, $receiver.user.nickname, $p.t.title, $p.t.price, $r.id, $r.status)
    Say ("{0}의 에뮬레이터에 알림이 갑니다." -f $receiver.user.nickname)
    exit 0
  } catch {
    $msg = $_.ErrorDetails.Message; if (-not $msg) { $msg = $_.Exception.Message }
    Say ("SKIP: '{0}' -> '{1}' : {2}" -f $p.m.title, $p.t.title, $msg)
  }
}
Say 'FAIL: 보낼 수 있는 조합이 없습니다(모두 대기 중).'
exit 1
