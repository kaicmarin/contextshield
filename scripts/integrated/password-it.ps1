# Validação IT: senhas individuais, troca própria, reset admin, tokens e perfis (API 18080).
$ErrorActionPreference = "Continue"
. (Join-Path $PSScriptRoot "Import-ItEnv.ps1")
. (Join-Path $PSScriptRoot "Get-DemoPassword.ps1")
. (Join-Path $PSScriptRoot "Resolve-ItLogin.ps1")
Import-ItEnv
$root = Get-ItRoot
$base = "http://127.0.0.1:18080"
. (Join-Path $PSScriptRoot "Restart-CoreItRelaxed.ps1")
if (-not (Restart-CoreItRelaxed -Root $root)) { throw "Core-api IT não subiu para password-it." }
$results = [System.Collections.Generic.List[object]]::new()

function Note($id, $name, $pass, $detail) {
    $results.Add([pscustomobject]@{ Id = $id; Test = $name; Pass = $pass; Detail = $detail })
    $s = if ($pass) { "OK" } else { "FALHA" }
    Write-Host "[$s] $id — $name — $detail"
}

function Api($method, $path, $token, $obj) {
    $json = if ($null -eq $obj) { $null } elseif ($obj -is [string]) { $obj } else { $obj | ConvertTo-Json -Compress }
    $curl = @("-sS", "-w", "`nHTTP:%{http_code}", "-X", $method, "-H", "Content-Type: application/json")
    if ($token) { $curl += @("-H", "Authorization: Bearer $token") }
    $file = $null
    if ($json) {
        $file = [System.IO.Path]::GetTempFileName()
        [System.IO.File]::WriteAllText($file, $json, [System.Text.UTF8Encoding]::new($false))
        $curl += @("--data-binary", "@$file")
    }
    $curl += "$base$path"
    $raw = & curl.exe @curl
    if ($file) { Remove-Item $file -Force }
    $text = ($raw | Out-String).Trim()
    $code = "000"
    if ($text -match "HTTP:(\d+)\s*$") { $code = $Matches[1]; $text = $text -replace "`nHTTP:\d+\s*$", "" }
    return @{ Code = $code; Body = $text; Json = $(try { if ($text) { $text | ConvertFrom-Json } else { $null } } catch { $null }) }
}

$credPath = if ($env:SEED_CREDENTIALS_FILE) { $env:SEED_CREDENTIALS_FILE } else {
    Join-Path $root "services\core-api\src\test\resources\seed\test-credentials.json"
}
$creds = Get-Content $credPath -Raw | ConvertFrom-Json

Write-Host "=== 0. Admin login e migração local (somente IT) ==="
$adminEmail = "admin@contextshield.example"
$adminPw = Get-ItLoginPassword -Email $adminEmail -Root $root -Base $base
$admin = Invoke-ItLogin -Base $base -Email $adminEmail -Password $adminPw
Note "0.1" "Login admin IT" ($admin.Code -eq "200") "HTTP $($admin.Code)"
$adminTok = $admin.Json.token
$users = (Api "GET" "/api/admin/users" $adminTok $null).Json
if (-not $users) { throw "Login admin falhou; abortando password-it." }
$userIds = @{}
foreach ($u in $users) { $userIds[$u.email.ToLower()] = $u.id }

$migrated = 0
foreach ($prop in $creds.passwords.PSObject.Properties) {
    $mail = $prop.Name
    $newPw = $prop.Value
    if (-not $userIds.ContainsKey($mail)) { continue }
    $id = $userIds[$mail]
    $cur = Get-ItLoginPassword -Email $mail -Root $root -Base $base
    if ($cur -eq $newPw) { continue }
    $r = Api "PUT" "/api/admin/users/$id" $adminTok @{ password = $newPw }
    if ($r.Code -eq "200") { $migrated++ }
}
Note "0.2" "Migração IT para senhas individuais" ($migrated -ge 0) "contas atualizadas=$migrated"
if ($migrated -gt 0) {
    $admPw = Get-DemoPassword -Email $adminEmail -Root $root
    $admin = Invoke-ItLogin -Base $base -Email $adminEmail -Password $admPw
    $adminTok = $admin.Json.token
}

Write-Host "=== 1. Logins distintos ==="
$anaPw = Get-DemoPassword -Email "ana.martins@correio.example" -Root $root
$admPw = Get-DemoPassword -Email $adminEmail -Root $root
Note "1.1" "Senhas de arquivo são diferentes" ($anaPw -ne $admPw) "cliente vs admin"
$anaL = Invoke-ItLogin -Base $base -Email "ana.martins@correio.example" -Password $anaPw
$admL = Invoke-ItLogin -Base $base -Email $adminEmail -Password $admPw
Note "1.2" "Login cliente e admin" (($anaL.Code -eq "200") -and ($admL.Code -eq "200")) "HTTP $($anaL.Code)/$($admL.Code)"
$cross = Invoke-ItLogin -Base $base -Email "ana.martins@correio.example" -Password $admPw
Note "1.3" "Senha admin não autentica cliente" ($cross.Code -ne "200") "HTTP $($cross.Code)"

Write-Host "=== 2-3. Troca de senha e token ==="
$regEmail = "pwd.it." + [Guid]::NewGuid().ToString("N").Substring(0, 8) + "@correio.example"
$regPw = "CsIt-Reg-UI-Test1"
$reg = Api "POST" "/api/auth/register" $null @{
    name = "Pwd IT"; email = $regEmail; password = $regPw
    city = "São Paulo"; addressLine = "Rua IT 1"; district = "Centro"; zip = "01000-000"
}
$regTok = $reg.Json.token
$newPw = "CsIt-Reg-UI-Test2"
$chg = Api "POST" "/api/me/password" $regTok @{ currentPassword = $regPw; newPassword = $newPw; confirmPassword = $newPw }
Note "2" "Troca de senha API" ($chg.Code -eq "200" -and $chg.Json.token) "HTTP $($chg.Code)"
$oldMe = Api "GET" "/api/me" $regTok $null
$newTok = $chg.Json.token
$newMe = Api "GET" "/api/me" $newTok $null
Note "3.1" "Token antigo invalidado" ($oldMe.Code -eq "401") "HTTP $($oldMe.Code)"
$oldLogin = Invoke-ItLogin -Base $base -Email $regEmail -Password $regPw
$newLogin = Invoke-ItLogin -Base $base -Email $regEmail -Password $newPw
Note "3.2" "Senha antiga rejeitada / nova aceita" (($oldLogin.Code -ne "200") -and ($newLogin.Code -eq "200")) "old=$($oldLogin.Code) new=$($newLogin.Code)"

Write-Host "=== 4. Reset admin ==="
$admin = Invoke-ItLogin -Base $base -Email $adminEmail -Password (Get-DemoPassword -Email $adminEmail -Root $root)
$adminTok = $admin.Json.token
$analystEmail = "analista@contextshield.example"
$analystPw = Get-DemoPassword -Email $analystEmail -Root $root
$analystL = Invoke-ItLogin -Base $base -Email $analystEmail -Password $analystPw
$analystTok = $analystL.Json.token
$analystId = $userIds[$analystEmail]
$resetPw = "CsIt-Analyst-Reset1"
$rst = Api "PUT" "/api/admin/users/$analystId" $adminTok @{ password = $resetPw }
Note "4" "Admin redefine senha" ($rst.Code -eq "200") "HTTP $($rst.Code)"
$denied = Api "GET" "/api/ops/overview" $analystTok $null
$analystL2 = Invoke-ItLogin -Base $base -Email $analystEmail -Password $resetPw
Note "4.1" "Token antigo após reset" ($denied.Code -eq "401" -and $analystL2.Code -eq "200") "overview=$($denied.Code) relogin=$($analystL2.Code)"
Api "PUT" "/api/admin/users/$analystId" $adminTok @{ password = $analystPw } | Out-Null
$analystTok = (Invoke-ItLogin -Base $base -Email $analystEmail -Password $analystPw).Json.token

Write-Host "=== 5. Permissões ==="
$anaL = Invoke-ItLogin -Base $base -Email "ana.martins@correio.example" -Password $anaPw
$anaTok = $anaL.Json.token
$cForbidden = Api "GET" "/api/ops/overview" $anaTok $null
$aForbidden = Api "GET" "/api/admin/users" $analystTok $null
$aEngine = Api "PUT" "/api/ops/engine/config" $analystTok '{"key":"script-mirror","enabled":true}'
$admEngine = Api "PUT" "/api/ops/engine/config" $adminTok '{"key":"script-mirror","enabled":true}'
Note "5" "Perfis CLIENTE/ANALYST/ADMIN" (
    ($cForbidden.Code -eq "403") -and ($aForbidden.Code -eq "403") -and ($aEngine.Code -eq "403") -and ($admEngine.Code -eq "200")
) "cli=$($cForbidden.Code) ana_admin=$($aForbidden.Code) ana_cfg=$($aEngine.Code) adm_cfg=$($admEngine.Code)"

Write-Host "=== 6. Respostas sem credenciais ==="
$loginBody = $analystL2.Body
Note "6" "Login JSON sem senha" (($loginBody -notmatch "CsIt-") -and ($loginBody -notmatch "contexto")) "comprimento=$($loginBody.Length)"

Write-Host "=== 7. Compatibilidade (contas existentes) ==="
$env:PGPASSWORD = $env:CORE_DB_PASSWORD
$pgHost = if ($env:PGHOST) { $env:PGHOST } else { "127.0.0.1" }
$pgPort = if ($env:PGPORT) { $env:PGPORT } else { "5433" }
$cnt = & (Get-PsqlPath) -h $pgHost -p $pgPort -U core_app -d contextshield_it -t -A -c "SELECT count(*) FROM app_user WHERE active;" 2>$null
Note "7" "Usuários ativos preservados" ([int]$cnt -ge 11) "active=$cnt"

$out = Join-Path $root "docs\PASSWORD-IT-RESULTS.md"
$lines = @("# Password IT $(Get-Date -Format 'yyyy-MM-dd HH:mm')", "", "| ID | Teste | OK | Detalhe |", "|---|---|---|---|")
$lines += $results | ForEach-Object { "| $($_.Id) | $($_.Test) | $($_.Pass) | $($_.Detail -replace '\|','/') |" }
$lines -join "`n" | Set-Content $out -Encoding UTF8
Write-Host "Relatorio: $out"
$fail = ($results | Where-Object { -not $_.Pass }).Count
exit $(if ($fail -eq 0) { 0 } else { 1 })