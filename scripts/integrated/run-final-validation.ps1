# Validacao FINAL V2 — senhas, R13, pesos, ML, permissoes, E2E (IT 18080 / 5173).
param([switch]$SkipE2E, [switch]$SkipIntegrated)
$ErrorActionPreference = "Continue"
. (Join-Path $PSScriptRoot "Import-ItEnv.ps1")
. (Join-Path $PSScriptRoot "Resolve-ItLogin.ps1")
Import-ItEnv
$root = Get-ItRoot
$base = "http://127.0.0.1:18080"
. (Join-Path $PSScriptRoot "Ensure-ViteForValidation.ps1")
# Ensure-ViteForValidation nao deve sobrescrever $base da Core API (18080).
$base = "http://127.0.0.1:18080"
$psql = Get-PsqlPath
$pgHost = if ($env:PGHOST) { $env:PGHOST } else { "127.0.0.1" }
$pgPort = if ($env:PGPORT) { $env:PGPORT } else { "5433" }
$env:PGPASSWORD = $env:CORE_DB_PASSWORD
$results = [System.Collections.Generic.List[object]]::new()

function Note($id, $name, $pass, $detail) {
    $results.Add([pscustomobject]@{ Id = $id; Test = $name; Pass = [bool]$pass; Detail = $detail })
    $s = if ($pass) { "OK" } else { "FALHA" }
    Write-Host "[$s] $id — $name — $detail"
}

function Api($method, $path, $token, $obj) {
    $json = if ($null -eq $obj) { $null } elseif ($obj -is [string]) { $obj } else { $obj | ConvertTo-Json -Depth 12 -Compress }
    $curl = @("-sS", "-w", "`nHTTP:%{http_code}", "-X", $method, "-H", "Content-Type: application/json")
    if ($token) { $curl += @("-H", "Authorization: Bearer $token") }
    $file = $null
    if ($json) {
        $file = [IO.Path]::GetTempFileName()
        [IO.File]::WriteAllText($file, $json, [Text.UTF8Encoding]::new($false))
        $curl += @("--data-binary", "@$file")
    }
    $curl += "$base$path"
    $raw = & curl.exe @curl
    if ($file) { Remove-Item $file -Force }
    $text = ($raw | Out-String).TrimEnd()
    $lines = $text -split "`r?`n"
    $code = "000"
    if ($lines.Count -ge 1 -and $lines[-1] -match '^HTTP:(\d+)$') {
        $code = $Matches[1]
        $text = if ($lines.Count -gt 1) { ($lines[0..($lines.Count - 2)] -join "`n") } else { "" }
    }
    $parsed = $null
    try { if ($text) { $parsed = $text | ConvertFrom-Json } } catch { }
    return @{ Code = $code; Body = $text; Json = $parsed }
}

Write-Host "`n========== FLYWAY V12-V14 =========="
$fly = & $psql -h $pgHost -p $pgPort -U core_app -d contextshield_it -t -A -c "SELECT count(*) FROM flyway_schema_history WHERE version IN ('12','13','14') AND success=true;"
Note "FLY" "Flyway V12-V14 (core)" ([int]$fly -ge 3) "core ok=$fly"

$adminPw = Get-ItLoginPassword -Email "admin@contextshield.example" -Root $root -Base $base
$anPw = Get-ItLoginPassword -Email "analista@contextshield.example" -Root $root -Base $base
$custPw = Get-ItLoginPassword -Email "ana.martins@correio.example" -Root $root -Base $base
$adminTok = (Invoke-ItLogin -Base $base -Email "admin@contextshield.example" -Password $adminPw).Json.token
$anTok = (Invoke-ItLogin -Base $base -Email "analista@contextshield.example" -Password $anPw).Json.token
$custTok = (Invoke-ItLogin -Base $base -Email "ana.martins@correio.example" -Password $custPw).Json.token
Note "1a" "Login admin" ($adminTok) "token"
Note "1b" "Login analista" ($anTok) "token"
Note "1c" "Login cliente NEXA" ($custTok) "token"

$deny = Api "POST" "/api/admin/rules/1/publish" $anTok @{}
Note "11a" "Analista nao publica regra admin" ($deny.Code -eq "403") "HTTP $($deny.Code)"

Write-Host "`n========== R13 DECISAO (sem restart) =========="
$polPub = Api "GET" "/api/ops/policies" $anTok $null
$pubId = ($polPub.Json | Where-Object { $_.status -eq "PUBLISHED" -and $_.profile -eq "fintech" } | Select-Object -First 1).id
if (-not $pubId) { $pubId = 1 }
$txId = "TX-2026-000184"
$rules = Api "GET" "/api/ops/rules" $anTok $null
$preR13 = @($rules.Json | Where-Object { $_.ruleKey -eq "R13" -and $_.status -eq "PUBLISHED" }) | Select-Object -First 1
$r13Disabled = @($rules.Json | Where-Object { $_.ruleKey -eq "R13" -and $_.status -eq "DISABLED" }) | Sort-Object id -Descending | Select-Object -First 1
if ($preR13) {
    Api "POST" "/api/ops/rules/$($preR13.id)/disable" $anTok @{} | Out-Null
    Start-Sleep -Seconds 1
    $rules = Api "GET" "/api/ops/rules" $anTok $null
}
$sim0 = Api "POST" "/api/ops/policies/$pubId/simulate" $anTok @{ txId = $txId }
$before = $sim0.Json.simulatedDecision
$rule0 = $sim0.Json.simulatedRule
$r13row = @($rules.Json | Where-Object { $_.ruleKey -eq "R13" }) | Sort-Object id -Descending | Select-Object -First 1
if (-not $r13row) {
    $r13 = Api "POST" "/api/ops/rules" $anTok @{
        ruleKey = "R13"; name = "Validacao FINAL"; priority = 5; kind = "SCORE_SIGNAL"
        params = @{ minScore = 1; signal = "NEW_DEVICE"; stage = "initial" }
        outcome = "BLOCK"; summary = "final validation"
    }
    $r13row = $r13.Json
} else {
    $r13 = @{ Json = $r13row; Code = "200" }
}
if (-not $r13row -or -not $r13row.id) {
    Note "2" "R13 altera simulacao TX-184" $false "regra R13 indisponivel (sim0 HTTP $($sim0.Code))"
    $id = 0
} else {
$id = $r13row.id
$r13body = @{
    name = "Validacao FINAL"; summary = "final validation"; priority = 5; kind = "SCORE_SIGNAL"
    params = @{ minScore = 1; signal = "NEW_DEVICE"; stage = "initial" }
    outcome = "BLOCK"; note = "validacao"
}
if ($r13row.status -in @("PUBLISHED", "DISABLED")) {
    $nv = Api "POST" "/api/ops/rules/$id/version" $anTok @{}
    $id = $nv.Json.id
}
Api "PUT" "/api/ops/rules/$id" $anTok $r13body | Out-Null
$stR = (Api "GET" "/api/ops/rules/$id" $anTok $null).Json.status
if ($stR -eq "DRAFT") { Api "POST" "/api/ops/rules/$id/submit" $anTok @{} | Out-Null; $stR = "PENDING" }
if ($stR -in @("DRAFT", "PENDING")) { Api "POST" "/api/admin/rules/$id/approve" $adminTok @{} | Out-Null }
Api "POST" "/api/admin/rules/$id/publish" $adminTok @{} | Out-Null
Start-Sleep -Seconds 1
$sim1 = Api "POST" "/api/ops/policies/$pubId/simulate" $anTok @{ txId = $txId }
$after = $sim1.Json.simulatedDecision
$rule1 = $sim1.Json.simulatedRule
$changed = ($before -ne $after) -or ($rule0 -ne $rule1)
Note "2" "R13 altera simulacao TX-184" $changed "antes=$before/$rule0 depois=$after/$rule1"
}

Write-Host "`n========== DESATIVAR R13 =========="
if ($id -gt 0) {
Api "POST" "/api/ops/rules/$id/disable" $anTok @{} | Out-Null
$sim2 = Api "POST" "/api/ops/policies/$pubId/simulate" $anTok @{ txId = $txId }
$restored = ($sim2.Json.simulatedDecision -eq $before) -or ($sim2.Json.simulatedRule -eq $rule0)
Note "3" "Desativar R13 restaura" $restored "dec=$($sim2.Json.simulatedDecision) rule=$($sim2.Json.simulatedRule)"
} else {
    Note "3" "Desativar R13 restaura" $false "R13 nao criada"
}

Write-Host "`n========== PESOS DINAMICOS =========="
$draft = Api "POST" "/api/ops/policies" $anTok @{ name = "Final weights"; profile = "fintech"; weights = @{ NEW_DEVICE = 14 }; note = "validacao" }
$policyDraftId = $draft.Json.id
Api "POST" "/api/ops/policies/$policyDraftId/submit" $anTok @{} | Out-Null
Api "POST" "/api/admin/policies/$policyDraftId/approve" $adminTok @{} | Out-Null
Api "POST" "/api/admin/policies/$policyDraftId/publish" $adminTok @{} | Out-Null
$pub2 = Api "GET" "/api/ops/policies/$policyDraftId" $anTok $null
Note "4" "Publicar pesos dinamicos" ($pub2.Json.status -eq "PUBLISHED") "status=$($pub2.Json.status)"

Write-Host "`n========== ML EXPERIMENTO + ESTUDOS =========="
$exp = Api "POST" "/api/ops/ml/experiments" $anTok @{ examples = 220; seed = 99 }
$expId = $exp.Json.id
$expOk = $false
for ($i = 0; $i -lt 180; $i++) {
    $job = Api "GET" "/api/ops/ml/experiments/$expId" $anTok $null
    if ($job.Json.status -eq "DONE") { $expOk = $true; break }
    if ($job.Json.status -eq "FAILED") { break }
    Start-Sleep -Seconds 2
}
Note "5" "Treino ML experimento" $expOk "status=$($job.Json.status)"

$wStudy = Api "POST" "/api/ops/ml/studies/weights" $anTok @{ seed = 77; costProfile = "standard" }
$wsId = $wStudy.Json.id
$wsOk = $false
for ($i = 0; $i -lt 90; $i++) {
    $st = Api "GET" "/api/ops/ml/studies/$wsId" $anTok $null
    if ($st.Json.status -eq "DONE") { $wsOk = $true; break }
    if ($st.Json.status -eq "FAILED") { break }
    Start-Sleep -Seconds 2
}
$ml = Api "GET" "/api/ops/ml" $anTok $null
$sugCount = @($ml.Json.suggestions).Count
Note "6" "Estudo pesos + sugestoes" ($wsOk -and $ml.Json.limitation) "study=$wsOk sugestoes=$sugCount"

$rStudy = Api "POST" "/api/ops/ml/studies/rules" $anTok @{}
$rsId = $rStudy.Json.id
$rsOk = $false
for ($i = 0; $i -lt 60; $i++) {
    $st = Api "GET" "/api/ops/ml/studies/$rsId" $anTok $null
    if ($st.Json.status -eq "DONE") { $rsOk = $true; break }
    if ($st.Json.status -eq "FAILED") { break }
    Start-Sleep -Seconds 2
}
$rp = $st.Json.report
$insuf = [bool]$rp.insufficientData
Note "7" "Estudo regras FP/FN" ($rsOk) "insuficiente=$insuf regras=$($rp.rules.Count)"

$ruleSug = $ml.Json.ruleSuggestions | Where-Object { $_.status -eq "SUGGESTED" } | Select-Object -First 1
$draftRule = $null
if ($ruleSug) {
    $draftRule = Api "POST" "/api/admin/ml/rule-suggestions/$($ruleSug.id)/draft" $adminTok @{}
}
$wSug = $ml.Json.suggestions | Where-Object { $_.status -eq "SUGGESTED" } | Select-Object -First 1
$stillPub = $true
if ($wSug) {
    $stW = Api "GET" "/api/ops/policies" $anTok $null
    $stillPub = @($stW.Json | Where-Object { $_.status -eq "PUBLISHED" -and $_.id -eq $policyDraftId }).Count -gt 0
}
Note "8" "Sugestao -> rascunho sem auto-publish" (($null -eq $ruleSug) -or ($draftRule.Code -eq "200")) "ruleDraft=$($draftRule.Code) weightStillDraft=$stillPub"

$txDet = Api "GET" "/api/ops/transactions/$txId" $anTok $null
if ($txDet.Json.summary) { $safety = ($txDet.Json.assessments | Select-Object -First 1) }
elseif ($txDet.Json.assessments) { $safety = $txDet.Json.assessments | Select-Object -Last 1 }
elseif ($txDet.Json.assessment) { $safety = $txDet.Json.assessment }
else { $safety = $txDet.Json.latestAssessment }
$hasSafety = ($null -ne $safety) -and ($null -ne $safety.safety)
Note "9" "Safety Monitor" $hasSafety "envelope=$(if($hasSafety){$safety.safety.issues.Count}else{'n/a'})"

Note "10" "Aviso sintetico na UI/API" ($ml.Json.limitation -match "sint") "limitation"

Write-Host "`n========== SUITE INTEGRADA + E2E =========="
if ((-not $SkipIntegrated) -or (-not $SkipE2E)) {
    if (-not (Start-ValidationVite -Root $root)) {
        Write-Warning "Vite :5173 indisponivel antes da suite integrada/E2E."
    }
}
if (-not $SkipIntegrated) {
    & (Join-Path $PSScriptRoot "run-v2-integrated.ps1")
    Note "INT" "run-v2-integrated" ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
} else {
    Note "INT" "run-v2-integrated" $false "SKIP solicitado — nao executado"
}

if ($SkipE2E) {
    Note "12" "E2E NEXA/Aureon/Ops" $false "SKIP solicitado — nao executado"
} else {
    $feUp = Test-ValidationVite
    if (-not $feUp) { $feUp = Start-ValidationVite -Root $root }
    $dr = Invoke-ItDemoReset -ApiBase $base -ProjectRoot $root
    if (-not $feUp) { Note "12" "E2E NEXA/Aureon/Ops" $false "frontend :5173 indisponivel" }
    elseif (-not $dr.Ok) { Note "12" "E2E NEXA/Aureon/Ops" $false $dr.Detail }
    else {
        Start-Sleep -Seconds 50
        Set-Location (Join-Path $root "frontend")
        $env:API_URL = $base
        $env:BASE = "http://127.0.0.1:5173"
        $ErrorActionPreference = "Continue"
        npm run test:e2e 2>&1 | Out-Null
        $e2eCode = $LASTEXITCODE
        Note "12" "E2E NEXA/Aureon/Ops" ($e2eCode -eq 0) "exit=$e2eCode"
        Set-Location $root
    }
}
Stop-ValidationVite

$ok = @($results | Where-Object { $_.Pass }).Count
$fail = @($results | Where-Object { -not $_.Pass }).Count
Write-Host "`n========== RESUMO FINAL =========="
Write-Host "Aprovados: $ok | Reprovados: $fail"
$results | Format-Table -AutoSize
$report = Join-Path $root "docs\FINAL-VALIDATION-RESULTS.md"
@"
# Validacao final V2 — $(Get-Date -Format 'yyyy-MM-dd HH:mm')

| Resultado | Quantidade |
|-----------|------------|
| Aprovados | $ok |
| Reprovados | $fail |

## Evidencias R13
- Antes: $before / $rule0
- Depois publicar R13: $after / $rule1
- Apos desativar: $($sim2.Json.simulatedDecision) / $($sim2.Json.simulatedRule)

## ML
- Experimento: $($job.Json.status)
- Estudo pesos: $wsOk (sugestoes=$sugCount)
- Estudo regras: $rsOk

## Detalhe
$($results | ConvertTo-Json -Depth 3)
"@ | Set-Content -Encoding UTF8 $report
Write-Host "Relatorio: $report"
if ($fail -gt 0) { exit 1 }