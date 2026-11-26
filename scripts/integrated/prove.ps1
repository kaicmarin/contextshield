# Prova local dos cinco serviços já em execução nas portas 18080-18084.
# Usa o PostgreSQL descartável da porta 5433. Não fala com o banco da porta 5432.
$ErrorActionPreference = "Continue"
$base = "http://127.0.0.1:18080"
. (Join-Path $PSScriptRoot "Import-ItEnv.ps1")
Import-ItEnv
$root = Get-ItRoot
$psql = Get-PsqlPath
$pgHost = if ($env:PGHOST) { $env:PGHOST } else { "127.0.0.1" }
$pgPort = if ($env:PGPORT) { $env:PGPORT } else { "5433" }
. (Join-Path $PSScriptRoot "Resolve-ItLogin.ps1")
function DemoPw($email) { Get-ItLoginPassword -Email $email -Root $root -Base "http://127.0.0.1:18080" }

function Call-Api([string]$method, [string]$path, [string]$token, [string]$json) {
    $curl = @("-sS", "-w", "`nHTTP:%{http_code}", "-X", $method, "-H", "Content-Type: application/json")
    if ($token) { $curl += @("-H", "Authorization: Bearer $token") }
    $file = $null
    if ($json) {
        $file = New-TemporaryFile
        [System.IO.File]::WriteAllText($file.FullName, $json, [System.Text.UTF8Encoding]::new($false))
        $curl += @("--data-binary", "@$($file.FullName)")
    }
    $curl += "$base$path"
    $raw = & curl.exe @curl
    if ($file) { Remove-Item $file.FullName -Force }
    $text = ($raw | Out-String).Trim()
    $code = "000"
    if ($text -match "HTTP:(\d+)\s*$") { $code = $Matches[1]; $text = $text -replace "`nHTTP:\d+\s*$", "" }
    Write-Host "---- $method $path -> $code"
    if ($text.Length -gt 1200) { Write-Host ($text.Substring(0, 1200) + " ...") } else { Write-Host $text }
    return [pscustomobject]@{ Code = $code; Body = $text }
}

Write-Output "=== SAUDE ==="
foreach ($port in 18080,18081,18082,18083,18084) {
    $h = & curl.exe -sS -m 5 "http://127.0.0.1:$port/actuator/health"
    Write-Output "$port $h"
}

Write-Output "=== HASH DOS MODELOS ANTES ==="
Get-ChildItem "$root\machine-learning\models\*.ser" | ForEach-Object {
    $hash = Get-FileHash $_.FullName -Algorithm SHA256
    Write-Output ("{0} {1}" -f $_.Name, $hash.Hash)
}

$check = Call-Api "POST" "/api/check" $null '{"text":"Sua entrega está retida. Pague o PIX agora para liberar o pedido."}'
$ana = Call-Api "POST" "/api/auth/login" $null ('{"email":"ana.martins@correio.example","password":"' + (DemoPw "ana.martins@correio.example") + '"}')
$anaToken = ($ana.Body | ConvertFrom-Json).token
$analyst = Call-Api "POST" "/api/auth/login" $null ('{"email":"analista@contextshield.example","password":"' + (DemoPw "analista@contextshield.example") + '"}')
$analystToken = ($analyst.Body | ConvertFrom-Json).token
$admin = Call-Api "POST" "/api/auth/login" $null ('{"email":"admin@contextshield.example","password":"' + (DemoPw "admin@contextshield.example") + '"}')
$adminToken = ($admin.Body | ConvertFrom-Json).token

$product = Call-Api "GET" "/api/catalog/products/prod-010" $null $null
$productJson = $product.Body | ConvertFrom-Json
$sellerId = $productJson.product.sellerId
Write-Output "SELLER $sellerId"

$tx1 = Call-Api "POST" "/api/transactions" $anaToken '{"items":[{"productId":"prod-010","quantity":1}],"addressId":"ADDR-ANA-HOME","deviceId":"DEV-ANA-MBA","declaredOrigin":"self"}'
$tx1id = ($tx1.Body | ConvertFrom-Json).id
Write-Output "TX1 $tx1id"

$denied = Call-Api "POST" "/api/ops/investigations" $anaToken '{"sourceType":"transaction","sourceId":"x","txId":"x","priority":"NORMAL"}'

$pending = Call-Api "POST" "/api/ops/indicators" $analystToken '{"kind":"phone","value":"11911112222"}'
$pendingId = ($pending.Body | ConvertFrom-Json).id
$jump = Call-Api "POST" "/api/ops/indicators/$pendingId/review" $analystToken '{"status":"CONFIRMED","note":"sem revisão","visibility":"SHARED"}'
$pendingCheck = Call-Api "POST" "/api/check" $null '{"text":"Ligue para (11) 91111-2222 e confirme o pagamento."}'

$opened = Call-Api "POST" "/api/ops/indicators" $analystToken '{"kind":"phone","value":"11987654321"}'
$indId = ($opened.Body | ConvertFrom-Json).id
Call-Api "POST" "/api/ops/indicators/$indId/review" $analystToken '{"status":"IN_REVIEW","note":"há repetição do número na mensagem","visibility":"PRIVATE"}' | Out-Null
$confirmed = Call-Api "POST" "/api/ops/indicators/$indId/review" $analystToken '{"status":"CONFIRMED","note":"o número apareceu em mensagens de cobrança falsa","visibility":"SHARED"}'
$history = Call-Api "GET" "/api/ops/indicators/$indId/history" $analystToken $null
$sharedCheck = Call-Api "POST" "/api/check" $null '{"text":"Central de entrega. Ligue (11) 98765-4321 e pague a taxa."}'

if ($sellerId) {
    $sellerInd = Call-Api "POST" "/api/ops/indicators" $analystToken ("{`"kind`":`"seller`",`"value`":`"$sellerId`"}")
    $sellerIndId = ($sellerInd.Body | ConvertFrom-Json).id
    Call-Api "POST" "/api/ops/indicators/$sellerIndId/review" $analystToken '{"status":"IN_REVIEW","note":"vendedor citado em relato conferido","visibility":"PRIVATE"}' | Out-Null
    Call-Api "POST" "/api/ops/indicators/$sellerIndId/review" $analystToken '{"status":"CONFIRMED","note":"vendedor associado a cobrança confirmada neste teste","visibility":"PRIVATE"}' | Out-Null
}
$tx2 = Call-Api "POST" "/api/transactions" $anaToken '{"items":[{"productId":"prod-010","quantity":1}],"addressId":"ADDR-ANA-HOME","deviceId":"DEV-ANA-MBA","declaredOrigin":"self"}'
$tx2id = ($tx2.Body | ConvertFrom-Json).id
$ops1 = Call-Api "GET" "/api/ops/transactions/$tx1id" $analystToken $null
$ops2 = Call-Api "GET" "/api/ops/transactions/$tx2id" $analystToken $null
Write-Output ("TX1 CONFIRMED_INDICATOR " + ($ops1.Body -match "CONFIRMED_INDICATOR"))
Write-Output ("TX2 CONFIRMED_INDICATOR " + ($ops2.Body -match "CONFIRMED_INDICATOR"))

$case = Call-Api "POST" "/api/ops/investigations" $analystToken ("{`"sourceType`":`"transaction`",`"sourceId`":`"$tx1id`",`"txId`":`"$tx1id`",`"priority`":`"HIGH`"}")
$caseId = ($case.Body | ConvertFrom-Json).id
Call-Api "POST" "/api/ops/investigations/$caseId/assign" $analystToken "{}" | Out-Null
Call-Api "POST" "/api/ops/investigations/$caseId/evidence" $analystToken '{"kind":"NOTE","body":"A compra habitual não traz o indicador confirmado do vendedor."}' | Out-Null
Call-Api "POST" "/api/ops/investigations/$caseId/comments" $analystToken '{"body":"Sem elemento novo além do histórico do cliente."}' | Out-Null
Call-Api "POST" "/api/ops/investigations/$caseId/transition" $analystToken '{"state":"CONFIRMED_LEGIT","note":"compra compatível com o histórico"}' | Out-Null
$closed = Call-Api "POST" "/api/ops/investigations/$caseId/transition" $analystToken '{"state":"CLOSED","note":"encerrado após a revisão"}'
$dossier = Call-Api "GET" "/api/ops/investigations/$caseId" $analystToken $null
$report = Call-Api "GET" "/api/ops/investigations/report" $analystToken $null
$ml = Call-Api "GET" "/api/ops/ml" $analystToken $null
$reserved = Call-Api "POST" "/api/ops/ml/experiments" $analystToken '{"examples":200,"seed":9901}'
$job = Call-Api "POST" "/api/ops/ml/experiments" $adminToken '{"examples":200,"seed":42}'
$jobId = ($job.Body | ConvertFrom-Json).id
Write-Output "EXPERIMENT $jobId"
if ($jobId) {
    for ($i = 0; $i -lt 36; $i++) {
        Start-Sleep -Seconds 5
        $st = Call-Api "GET" "/api/ops/ml/experiments/$jobId" $analystToken $null
        if ($st.Body -match '"status"\s*:\s*"(SUCCEEDED|FAILED|DONE|FINISHED|COMPLETED)"') { break }
    }
}

Write-Output "=== HASH DOS MODELOS DEPOIS ==="
Get-ChildItem "$root\machine-learning\models\*.ser" | ForEach-Object {
    $hash = Get-FileHash $_.FullName -Algorithm SHA256
    Write-Output ("{0} {1}" -f $_.Name, $hash.Hash)
}

Write-Output "=== POSTGRES DESCARTAVEL ==="
$env:PGPASSWORD = if ($env:POSTGRES_SUPER_PASSWORD) { $env:POSTGRES_SUPER_PASSWORD } else { $env:DB_PASSWORD }
& $psql -h $pgHost -p $pgPort -U postgres -d contextshield_it -c "select file_name, status, decision_authorized from ml.ml_model order by id;" -c "select tx_id, prediction, engine_decision, error, latency_ms from ml.ml_shadow order by id;" -c "select id, status, seed, example_count, error from ml.ml_experiment order by id;" -c "select id, state, assignee, training_label from investigation.review_case;" -c "select indicator_id, actor, from_status, to_status from threat.threat_review order by id;"
$env:PGPASSWORD = $env:CORE_DB_PASSWORD
Write-Output "=== CORE NAO LE O SCHEMA ML ==="
& $psql -h $pgHost -p $pgPort -U core_app -d contextshield_it -c "select count(*) from ml.ml_shadow;"
