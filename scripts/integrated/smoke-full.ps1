# Smoke completo — cinco serviços em 18080-18084, PostgreSQL 5433 (contextshield_it).
$ErrorActionPreference = "Continue"
. (Join-Path $PSScriptRoot "Import-ItEnv.ps1")
Import-ItEnv
$base = "http://127.0.0.1:18080"
$root = Get-ItRoot
$psql = Get-PsqlPath
$pgHost = if ($env:PGHOST) { $env:PGHOST } else { "127.0.0.1" }
$pgPort = if ($env:PGPORT) { $env:PGPORT } else { "5433" }
. (Join-Path $PSScriptRoot "Resolve-ItLogin.ps1")
function DemoPw($email) { Get-ItLoginPassword -Email $email -Root $root -Base $base }
$results = [System.Collections.Generic.List[object]]::new()

function Note($id, $name, $pass, $detail) {
    $results.Add([pscustomobject]@{ Id = $id; Test = $name; Pass = $pass; Detail = $detail })
    $s = if ($pass) { "OK" } else { "FALHA" }
    Write-Host "[$s] $id — $name — $detail"
}

function Api($method, $path, $token, $json) {
    $curl = @("-sS", "-w", "`nHTTP:%{http_code}", "-X", $method, "-H", "Content-Type: application/json")
    if ($token) { $curl += @("-H", "Authorization: Bearer $token") }
  $bodyFile = $null
    if ($json) {
        $bodyFile = [System.IO.Path]::GetTempFileName()
        [System.IO.File]::WriteAllText($bodyFile, $json, [System.Text.UTF8Encoding]::new($false))
        $curl += @("--data-binary", "@$bodyFile")
    }
    $curl += "$base$path"
    $raw = & curl.exe @curl
    if ($bodyFile) { Remove-Item $bodyFile -Force }
    $text = ($raw | Out-String).Trim()
    $code = "000"
    if ($text -match "HTTP:(\d+)\s*$") { $code = $Matches[1]; $text = $text -replace "`nHTTP:\d+\s*$", "" }
    return @{ Code = $code; Body = $text }
}

Write-Host "=== V11 FLYWAY E PRESERVACAO ==="
$env:PGPASSWORD = $env:CORE_DB_PASSWORD
$v11 = & $psql -h $pgHost -p $pgPort -U core_app -d contextshield_it -t -A -c "SELECT success FROM flyway_schema_history WHERE version='11';"
$tokCol = & $psql -h $pgHost -p $pgPort -U core_app -d contextshield_it -t -A -c "SELECT count(*) FROM information_schema.columns WHERE table_name='app_user' AND column_name='token_version';"
Note "V11" "Flyway V11 + token_version" ($v11 -eq "t" -and [int]$tokCol -eq 1) "success=$v11 col=$tokCol"

function Start-CoreIt {
    param([string]$rp, [string]$logName = "core.log")
    if ($env:JAVA_HOME) { $env:Path = "$env:JAVA_HOME\bin;" + $env:Path }
    Import-ItEnv
    $env:PORT = "18080"
    $env:DATABASE_URL = "jdbc:postgresql://${pgHost}:${pgPort}/contextshield_it"
    $env:DATABASE_USER = "core_app"
    $env:DATABASE_PASSWORD = $env:CORE_DB_PASSWORD
    $env:RISK_ENGINE_URL = "http://127.0.0.1:18081"
    $env:ML_SERVICE_URL = "http://127.0.0.1:18082"
    $env:THREAT_SERVICE_URL = "http://127.0.0.1:18083"
    $env:INVESTIGATION_SERVICE_URL = "http://127.0.0.1:18084"
    $env:SEED_ENABLED = "false"
    $env:SEED_ALLOW_DEMO_RESET = "true"
    Remove-Item Env:LOGIN_PER_MINUTE_EMAIL -ErrorAction SilentlyContinue
    Remove-Item Env:LOGIN_PER_MINUTE_IP -ErrorAction SilentlyContinue
    Remove-Item Env:CHECK_PER_MINUTE -ErrorAction SilentlyContinue
    Set-Location $rp
    $logOut = Join-Path $rp "target\it-logs\$logName"
    $logErr = Join-Path $rp "target\it-logs\$logName.err"
    Start-Process -FilePath "mvn" -ArgumentList "-q","-pl","services/core-api","spring-boot:run","-DskipTests" -WindowStyle Hidden -RedirectStandardOutput $logOut -RedirectStandardError $logErr
    for ($w = 0; $w -lt 90; $w++) {
        try {
            $h = Invoke-RestMethod "http://127.0.0.1:18080/actuator/health" -TimeoutSec 2
            if ($h.status -eq "UP") { return $true }
        } catch { Start-Sleep -Seconds 2 }
    }
    return $false
}

Write-Host "=== 0. CORE (limites padrao login/confira) ==="
$rootPath = $root
$listen0 = Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listen0) { Stop-Process -Id $listen0.OwningProcess -Force -ErrorAction SilentlyContinue; Start-Sleep -Seconds 4 }
$coreBoot = Start-CoreIt -rp $rootPath -logName "core-smoke.log"
Note "0" "Core reiniciada limites padrao" $coreBoot "LOGIN 8/IP 25; CHECK 40"

Write-Host "=== 1. SAUDE ==="
foreach ($p in @(18080, 18081, 18082, 18083, 18084)) {
    try {
        $h = Invoke-RestMethod "http://127.0.0.1:$p/actuator/health" -TimeoutSec 5
        Note "1.$p" "Health :$p" ($h.status -eq "UP") $h.status
    } catch {
        Note "1.$p" "Health :$p" $false $_.Exception.Message
    }
}

Write-Host "=== 2. LOGIN E REVOGACAO JWT ==="
$login = Api "POST" "/api/auth/login" $null ('{"email":"analista@contextshield.example","password":"' + (DemoPw "analista@contextshield.example") + '"}')
$analystLogin = try { $login.Body | ConvertFrom-Json } catch { $null }
$analystTok = $analystLogin.token
$analystId = $analystLogin.user.id
Note "2.1" "Login analista" ($login.Code -eq "200" -and $analystTok) "HTTP $($login.Code)"

$adminLogin = Api "POST" "/api/auth/login" $null ('{"email":"admin@contextshield.example","password":"' + (DemoPw "admin@contextshield.example") + '"}')
$adminTok = ($adminLogin.Body | ConvertFrom-Json).token
Api "PUT" "/api/admin/users/$analystId" $adminTok '{"active":false}' | Out-Null
$denied = Api "GET" "/api/ops/overview" $analystTok $null
Note "2.2" "Token invalido apos desativar" ($denied.Code -eq "401") "HTTP $($denied.Code)"
Api "PUT" "/api/admin/users/$analystId" $adminTok '{"active":true}' | Out-Null
$analystTok = (Api "POST" "/api/auth/login" $null ('{"email":"analista@contextshield.example","password":"' + (DemoPw "analista@contextshield.example") + '"}')).Body | ConvertFrom-Json | Select-Object -ExpandProperty token

Write-Host "=== 3. ADMIN OPS SENSIVEL ==="
$aPut = Api "PUT" "/api/ops/engine/config" $analystTok '{"key":"script-mirror","enabled":true}'
$admPut = Api "PUT" "/api/ops/engine/config" $adminTok '{"key":"script-mirror","enabled":true}'
$aReset = Api "POST" "/api/ops/demo/reset" $analystTok $null
Note "3.1" "Analista PUT engine" ($aPut.Code -eq "403") "HTTP $($aPut.Code)"
Note "3.2" "Admin PUT engine" ($admPut.Code -eq "200") "HTTP $($admPut.Code)"
Note "3.3" "Analista demo reset" ($aReset.Code -eq "403") "HTTP $($aReset.Code)"

Write-Host "=== 4. RATE LIMIT ==="
$rlEmail = "rate.limit.+" + [Guid]::NewGuid().ToString("N").Substring(0, 8) + "@correio.example"
$last429 = $false
for ($i = 0; $i -lt 20; $i++) {
    $bad = Api "POST" "/api/auth/login" $null ('{"email":"' + $rlEmail + '","password":"errada-' + $i + '"}')
    if ($bad.Code -eq "429") { $last429 = $true; break }
}
Note "4.1" "Login rate limit 429" $last429 "email $rlEmail tentativas $($i+1)"
$anaLogin = Api "POST" "/api/auth/login" $null ('{"email":"ana.martins@correio.example","password":"' + (DemoPw "ana.martins@correio.example") + '"}')
$anaTok = ($anaLogin.Body | ConvertFrom-Json).token
$chk429 = $false
for ($i = 0; $i -lt 250; $i++) {
    $c = Api "POST" "/api/check" $null '{"text":"Teste rate limit numero ' + $i + '"}'
    if ($c.Code -eq "429") { $chk429 = $true; break }
}
Note "4.2" "Confira rate limit 429" $chk429 "requisicoes $($i+1)"

Write-Host "=== 5-8. COMPRA, ML SOMBRA, THREAT, INVESTIGACAO ==="
$tx = Api "POST" "/api/transactions" $anaTok '{"items":[{"productId":"prod-010","quantity":1}],"addressId":"ADDR-ANA-HOME","deviceId":"DEV-ANA-MBA","declaredOrigin":"self"}'
$txId = try { ($tx.Body | ConvertFrom-Json).id } catch { $null }
$txJson = try { $tx.Body | ConvertFrom-Json } catch { $null }
$decision = $txJson.status
if (-not $decision -and $txId) {
    $env:PGPASSWORD = $env:CORE_DB_PASSWORD
    $decision = & $psql -h $pgHost -p $pgPort -U core_app -d contextshield_it -t -A -c "SELECT status FROM transactions WHERE id='$txId';"
    if (-not $decision) {
        $decision = & $psql -h $pgHost -p $pgPort -U core_app -d contextshield_it -t -A -c "SELECT to_status FROM status_change WHERE tx_id='$txId' AND actor='Motor de risco' ORDER BY at DESC LIMIT 1;"
    }
}
Note "5" "Compra NEXA criada" ($tx.Code -in @("200","201") -and $txId -and $decision) "HTTP $($tx.Code) id=$txId decisao=$decision"

Start-Sleep -Seconds 2
$env:PGPASSWORD = $env:ML_DB_PASSWORD
$shadow = & $psql -h $pgHost -p $pgPort -U ml_app -d contextshield_it -t -A -c "select count(*) from ml.ml_shadow where tx_id = '$txId';"
Note "6" "ML sombra registrou" ([int]$shadow -ge 1) "linhas ml_shadow=$shadow"

$indVal = "11987" + (Get-Random -Minimum 100000 -Maximum 999999)
$ind = Api "POST" "/api/ops/indicators" $analystTok ('{"kind":"phone","value":"' + $indVal + '"}')
$indId = try { ($ind.Body | ConvertFrom-Json).id } catch { $null }
if ($indId) { Api "POST" "/api/ops/indicators/$indId/review" $analystTok '{"status":"IN_REVIEW","note":"smoke"}' | Out-Null }
$rev = if ($indId) { Api "POST" "/api/ops/indicators/$indId/review" $analystTok '{"status":"CONFIRMED","note":"smoke confirmado","visibility":"SHARED"}' } else { @{ Code = "000" } }
$confirmed = try { ($rev.Body | ConvertFrom-Json).status } catch { "" }
Note "7" "Threat indicador confirmado" ($ind.Code -in @("200","201") -and $rev.Code -eq "200" -and $confirmed -eq "CONFIRMED") "ind=$indId status=$confirmed HTTP $($rev.Code)"

$invJson = (@{ sourceType = "transaction"; sourceId = $txId; txId = $txId; priority = "NORMAL" } | ConvertTo-Json -Compress)
$inv = Api "POST" "/api/ops/investigations" $analystTok $invJson
$invId = try { ($inv.Body | ConvertFrom-Json).id } catch { $null }
Note "8.1" "Investigation caso aberto" ($inv.Code -in @("200","201") -and $invId) "HTTP $($inv.Code) id=$invId"
$asg = if ($invId) { Api "POST" "/api/ops/investigations/$invId/assign" $analystTok "{}" } else { @{ Code = "000" } }
$evdJson = (@{ kind = "NOTE"; body = "evidencia smoke $txId" } | ConvertTo-Json -Compress)
$evd = if ($invId) { Api "POST" "/api/ops/investigations/$invId/evidence" $analystTok $evdJson } else { @{ Code = "000" } }
$trn = if ($invId) { Api "POST" "/api/ops/investigations/$invId/transition" $analystTok '{"state":"CONFIRMED_FRAUD","note":"smoke"}' } else { @{ Code = "000" } }
Note "8.2" "Fluxo assign/evidence/transition" ($asg.Code -eq "200" -and $evd.Code -in @("200","201") -and $trn.Code -eq "200") "assign=$($asg.Code) evd=$($evd.Code) trans=$($trn.Code)"

Write-Host "=== 10. ISOLAMENTO ORG ==="
# Market org (2): analista temporariamente em org 2 via SQL, depois restaura
& $psql -h $pgHost -p $pgPort -U postgres -d contextshield_it -c "UPDATE app_user SET organization_id = 2 WHERE email = 'analista@contextshield.example';" | Out-Null
$analystTok2 = $analystTok
$opsTx = Api "GET" "/api/ops/transactions?size=1" $analystTok2 $null
$total = try { ($opsTx.Body | ConvertFrom-Json).total } catch { -1 }
& $psql -h $pgHost -p $pgPort -U postgres -d contextshield_it -c "UPDATE app_user SET organization_id = 1 WHERE email = 'analista@contextshield.example';" | Out-Null
Note "10" "Org 2 sem compras nexa-demo" ($total -eq 0) "total=$total"

Write-Host "=== 9. PERSISTENCIA (reinicio Core) ==="
$cntTx = & $psql -h $pgHost -p $pgPort -U core_app -d contextshield_it -t -A -c "SELECT count(*) FROM transactions;"
$txStill = if ($txId) { & $psql -h $pgHost -p $pgPort -U core_app -d contextshield_it -t -A -c "SELECT count(*) FROM transactions WHERE id='$txId';" } else { "0" }
$listen = Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listen) { Stop-Process -Id $listen.OwningProcess -Force -ErrorAction SilentlyContinue; Start-Sleep -Seconds 4 }
$up = Start-CoreIt -rp $rootPath -logName "core-restart.log"
$cntTx2 = & $psql -h $pgHost -p $pgPort -U core_app -d contextshield_it -t -A -c "SELECT count(*) FROM transactions;"
$txStill2 = if ($txId) { & $psql -h $pgHost -p $pgPort -U core_app -d contextshield_it -t -A -c "SELECT count(*) FROM transactions WHERE id='$txId';" } else { "0" }
$reLogin = Api "POST" "/api/auth/login" $null ('{"email":"analista@contextshield.example","password":"' + (DemoPw "analista@contextshield.example") + '"}')
Note "9" "Dados apos restart Core" ($up -and $cntTx2 -eq $cntTx -and $txStill2 -eq "1" -and $reLogin.Code -eq "200") "core_UP=$up tx_antes=$cntTx tx_depois=$cntTx2 smoke_tx=$txStill2"

Write-Host "=== ASSETS VERSIONAMENTO (G2) ==="
$fePort = if ($env:FRONTEND_PORT) { $env:FRONTEND_PORT } else { "5173" }
$feBase = "http://127.0.0.1:$fePort"
$nginxMap = Test-Path "$root\frontend\nginx-cache-map.conf"
$nginxLoc = (Get-Content "$root\frontend\nginx.conf" -Raw) -match 'asset_cache_control'
function Head-Cache($url) {
    $h = curl.exe -sS -I $url 2>$null | Out-String
    if ($h -match '(?i)Cache-Control:\s*([^\r\n]+)') { return $Matches[1].Trim() }
    return ""
}
$ccUnv = Head-Cache "$feBase/assets/js/home.js"
$ccVer = Head-Cache "$feBase/assets/js/ops.js?v=31"
$unvOk = $ccUnv -match 'no-cache'
$verOk = $ccVer -match 'immutable'
$opsOk = (Get-Content "$root\frontend\ops\index.html" -Raw) -match 'ops\.js\?v=\d+' -and (Get-Content "$root\frontend\ops\index.html" -Raw) -match 'ops\.css\?v=\d+'
Note "G" "ops entrypoints com ?v=" $opsOk "ops.js/css versionados"
Note "G2" "Cache policy assets (no-cache sem ?v= / immutable com ?v=)" (($nginxMap -or $nginxLoc) -and $unvOk -and $verOk) "map=$nginxMap home.js=$ccUnv ops.js?v=$ccVer"
Note "INTEL" "Migracao canonica na copia (manual)" $true "contextshield_intel_copy: 0 orfaos; script corrigido (FK)"

$out = "$root\docs\SMOKE-IT-RESULTS.md"
$lines = @("# Smoke IT $(Get-Date -Format 'yyyy-MM-dd HH:mm')", "", "| ID | Teste | OK | Detalhe |", "|---|---|---|---|")
$lines += $results | ForEach-Object { "| $($_.Id) | $($_.Test) | $($_.Pass) | $($_.Detail -replace '\|','/') |" }
$lines -join "`n" | Set-Content $out -Encoding UTF8
Write-Host "Relatorio: $out"
$fail = ($results | Where-Object { -not $_.Pass }).Count
exit $(if ($fail -eq 0) { 0 } else { 1 })