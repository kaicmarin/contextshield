# Teste descartavel do fluxo deploy-app-update (Docker local, projeto isolado).
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $Root
if (-not (Test-Path ".env")) { Copy-Item ".env.example" ".env" }
$env:COMPOSE_PROJECT_NAME = "cs-deploy-it"
$compose = "docker compose -f docker-compose.aws-free.yml"

Write-Host "1. Subir Postgres isolado..."
Invoke-Expression "$compose up -d postgres"
for ($i = 0; $i -lt 36; $i++) {
    try {
        $h = Invoke-Expression "$compose exec -T postgres pg_isready -U contextshield" 2>$null
        if ($LASTEXITCODE -eq 0) { break }
    } catch { }
    Start-Sleep -Seconds 2
}

$usersBefore = Invoke-Expression "$compose exec -T postgres psql -U contextshield -d contextshield -t -A -c `"select count(*) from app_user`"" 2>$null
if (-not $usersBefore) { $usersBefore = "0" }
Write-Host "   Usuarios antes: $usersBefore"

Write-Host "2. Build + core-api (Flyway V1-V13)..."
Invoke-Expression "$compose build core-api risk-engine"
Invoke-Expression "$compose up -d risk-engine ml-service threat-service investigation-service"
Invoke-Expression "$compose up -d core-api"
for ($i = 0; $i -lt 60; $i++) {
    try {
        $j = Invoke-Expression "$compose exec -T core-api wget -qO- http://127.0.0.1:8080/actuator/health" | ConvertFrom-Json
        if ($j.status -eq "UP") { break }
    } catch { }
    Start-Sleep -Seconds 3
}

$fly = Invoke-Expression "$compose exec -T postgres psql -U contextshield -d contextshield -t -A -c `"select string_agg(version,',' order by installed_rank) from flyway_schema_history where success`""
$v12 = Invoke-Expression "$compose exec -T postgres psql -U contextshield -d contextshield -t -A -c `"select count(*) from flyway_schema_history where version='12'`""
$v13 = $null
if (-not $v13) {
    $v13 = Invoke-Expression "$compose exec -T postgres psql -U contextshield -d contextshield -t -A -c `"select count(*) from flyway_schema_history where version='13'`""
}
$rules = Invoke-Expression "$compose exec -T postgres psql -U contextshield -d contextshield -t -A -c `"select count(*) from risk_engine_rule where status='PUBLISHED'`""

Write-Host "Flyway: $fly | V12=$v12 V13=$v13 | regras publicadas=$rules"

Write-Host "3. Backup (como deploy-app-update)..."
New-Item -ItemType Directory -Force -Path (Join-Path $Root "backups") | Out-Null
$bak = Join-Path $Root "backups" "isolated-test-$(Get-Date -Format 'yyyyMMdd-HHmmss').sql.gz"
Invoke-Expression "$compose exec -T postgres pg_dump -U contextshield contextshield" | gzip -c > $bak
$bakOk = Test-Path $bak
Write-Host "   Backup: $bak ($bakOk)"

$usersAfter = Invoke-Expression "$compose exec -T postgres psql -U contextshield -d contextshield -t -A -c `"select count(*) from app_user`""
Write-Host "Usuarios depois: $usersAfter (preservacao=$($usersBefore -eq $usersAfter))"

Write-Host "4. Health microsservicos..."
$healthOk = $true
foreach ($svc in @("risk-engine","ml-service","threat-service","investigation-service")) {
    try {
        Invoke-Expression "$compose exec -T $svc wget -qO- http://127.0.0.1:8081/actuator/health" | Out-Null
    } catch { $healthOk = $false }
}

Write-Host "5. Derrubar projeto isolado (volume preservado para inspecao)..."
# Invoke-Expression "$compose down"  # manter up para inspecao manual se falhar

$report = @{
    backup = $bakOk
    flywayIncludesV12 = ([int]$v12 -ge 1)
    flywayIncludesV13 = ([int]$v13 -ge 1)
    rulesSeeded = ([int]$rules -ge 16)
    dataPreserved = ($usersBefore -eq $usersAfter)
    health = $healthOk
}
$report | ConvertTo-Json | Set-Content (Join-Path $Root "docs\DEPLOY-ISOLATED-TEST.json")
$report