# Bateria IT: Maven (opcional), password-it, smoke, ui-it, password-ui-it, e2e (com reset demo).
param([switch]$SkipMaven, [switch]$SkipE2E)
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $root
if (-not $SkipMaven) {
    $prevEa = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    mvn -q test 2>&1 | Out-Null
    $ErrorActionPreference = $prevEa
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
& (Join-Path $PSScriptRoot "password-it.ps1")
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $PSScriptRoot "smoke-full.ps1")
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$env:API_URL = "http://127.0.0.1:18080"
$env:BASE = "http://127.0.0.1:5173"
. (Join-Path $PSScriptRoot "Ensure-ViteForValidation.ps1")
if (-not (Start-ValidationVite -Root $root)) { throw "Vite :5173 indisponivel para ui-it" }
node (Join-Path $root "frontend\scripts\ui-it.mjs")
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
node (Join-Path $root "frontend\scripts\password-ui-it.mjs")
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if ($SkipE2E) { exit 0 }
$dr = Invoke-ItDemoReset -ApiBase "http://127.0.0.1:18080" -ProjectRoot $root
if (-not $dr.Ok) { Write-Error "demo reset falhou: $($dr.Detail)" }
Start-Sleep -Seconds 50
Set-Location (Join-Path $root "frontend")
$env:API_URL = "http://127.0.0.1:18080"
$env:BASE = "http://127.0.0.1:5173"
$ErrorActionPreference = "Continue"
npm run test:e2e 2>&1 | Out-Null
exit $LASTEXITCODE