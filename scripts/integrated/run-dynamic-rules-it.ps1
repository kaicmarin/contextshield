# IT: motor de regras dinâmicas (requer core em 18080 e migração V12).
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "Import-ItEnv.ps1")
. (Join-Path $PSScriptRoot "Resolve-ItLogin.ps1")
Import-ItEnv
$Root = Get-ItRoot
$Base = "http://127.0.0.1:18080"

function Api($method, $path, $token, $obj) {
  $json = if ($null -eq $obj) { $null } else { $obj | ConvertTo-Json -Depth 8 -Compress }
  $curl = @("-sS", "-w", "`nHTTP:%{http_code}", "-X", $method, "-H", "Content-Type: application/json")
  if ($token) { $curl += @("-H", "Authorization: Bearer $token") }
  $file = $null
  if ($json) {
    $file = [IO.Path]::GetTempFileName()
    [IO.File]::WriteAllText($file, $json, [Text.UTF8Encoding]::new($false))
    $curl += @("--data-binary", "@$file")
  }
  $curl += "$Base$path"
  $raw = & curl.exe @curl
  if ($file) { Remove-Item $file -Force }
  $text = ($raw | Out-String).TrimEnd()
  $lines = $text -split "`r?`n"
  $code = "000"
  if ($lines.Count -ge 1 -and $lines[-1] -match '^HTTP:(\d+)$') {
    $code = $Matches[1]
    $text = if ($lines.Count -gt 1) { ($lines[0..($lines.Count - 2)] -join "`n") } else { "" }
  }
  if ($code -notmatch "^2") { throw "$method $path -> HTTP $code : $text" }
  if (-not $text) { return $null }
  return $text | ConvertFrom-Json
}

$adminPw = Get-ItLoginPassword -Email "admin@contextshield.example" -Root $Root -Base $Base
$anPw = Get-ItLoginPassword -Email "analista@contextshield.example" -Root $Root -Base $Base
$adminTok = (Invoke-ItLogin -Base $Base -Email "admin@contextshield.example" -Password $adminPw).Json.token
$anTok = (Invoke-ItLogin -Base $Base -Email "analista@contextshield.example" -Password $anPw).Json.token

Write-Host "1. Catálogo publicado..."
$cat = Api GET "/api/ops/rules/catalog" $anTok $null
if ($cat.publishedCount -lt 16) { throw "Esperava >=16 regras seed, obteve $($cat.publishedCount)" }

Write-Host "2. Criar/publicar R13..."
$list = Api GET "/api/ops/rules" $anTok $null
$r13 = @($list | Where-Object { $_.ruleKey -eq "R13" }) | Sort-Object id -Descending | Select-Object -First 1
$r13body = @{
  name = "Destino em incidente IT"
  summary = "Bloqueio com destino em incidente e score medio+"
  priority = 15
  kind = "SCORE_SIGNAL"
  params = @{ weightKey = "THRESHOLD_MEDIUM"; signal = "DESTINATION_IN_INCIDENT"; stage = "any"; summary = "destino em incidente" }
  outcome = "BLOCK"
  note = "IT dinamico"
}
if (-not $r13) {
  $r13 = Api POST "/api/ops/rules" $anTok @{
    ruleKey = "R13"
    name = $r13body.name
    summary = $r13body.summary
    priority = $r13body.priority
    kind = $r13body.kind
    params = $r13body.params
    outcome = $r13body.outcome
    note = $r13body.note
  }
}
$id = $r13.id
if ($r13.status -in @("PUBLISHED", "DISABLED")) {
  $nv = Api POST "/api/ops/rules/$id/version" $anTok @{}
  $id = $nv.id
}
Api PUT "/api/ops/rules/$id" $anTok $r13body | Out-Null
$stR = (Api GET "/api/ops/rules/$id" $anTok $null).status
if ($stR -eq "DRAFT") { Api POST "/api/ops/rules/$id/submit" $anTok @{} | Out-Null; $stR = "PENDING" }
if ($stR -in @("DRAFT", "PENDING")) { Api POST "/api/admin/rules/$id/approve" $adminTok @{} | Out-Null }
Api POST "/api/admin/rules/$id/publish" $adminTok @{} | Out-Null
$after = Api GET "/api/ops/rules/catalog" $anTok $null
$r13Pub = @($after.rules | Where-Object { $_.ruleKey -eq "R13" -or $_.displayKey -eq "R13" }).Count
if ($r13Pub -lt 1) { throw "R13 nao consta no catalogo publicado (count=$($after.publishedCount))" }

Write-Host "3. Desativar e reativar..."
Api POST "/api/ops/rules/$id/disable" $anTok @{} | Out-Null
$off = (Api GET "/api/ops/rules/catalog" $anTok $null).publishedCount
Api POST "/api/ops/rules/$id/enable" $anTok @{} | Out-Null
$on = (Api GET "/api/ops/rules/catalog" $anTok $null).publishedCount
if ($on -ne $after.publishedCount) { throw "Reativação falhou ($off -> $on)" }

Write-Host "OK — R13 ciclo criar/publicar/desativar/ativar."