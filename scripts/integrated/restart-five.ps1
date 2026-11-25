# Reinício controlado dos cinco microsserviços IT (18080-18084), um por vez.
$ErrorActionPreference = "Continue"
. (Join-Path $PSScriptRoot "Import-ItEnv.ps1")
Import-ItEnv
$root = Get-ItRoot
$psql = Get-PsqlPath
$pgHost = if ($env:PGHOST) { $env:PGHOST } else { "127.0.0.1" }
$pgPort = if ($env:PGPORT) { $env:PGPORT } else { "5433" }
if ($env:JAVA_HOME) { $env:Path = "$env:JAVA_HOME\bin;" + $env:Path }
$env:SEED_ENABLED = "false"
$env:SEED_ALLOW_DEMO_RESET = "true"
Remove-Item Env:LOGIN_PER_MINUTE_EMAIL,Env:LOGIN_PER_MINUTE_IP -ErrorAction SilentlyContinue

$before = & $psql -h $pgHost -p $pgPort -U postgres -d contextshield_it -t -A -c "SELECT count(*) FROM transactions;"
$logDir = "$root\target\it-logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Wait-Health($port, $sec) {
    for ($i = 0; $i -lt $sec; $i++) {
        try {
            $h = Invoke-RestMethod "http://127.0.0.1:$port/actuator/health" -TimeoutSec 2
            if ($h.status -eq "UP") { return $true }
        } catch { Start-Sleep -Seconds 2 }
    }
    return $false
}

function Stop-Port($port) {
    $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) { Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue; Start-Sleep -Seconds 3 }
}

function Start-ServiceIt($module, $port, $extraEnv, $log) {
    $env:PORT = "$port"
    Remove-Item Env:DATABASE_URL,Env:DATABASE_USER,Env:DATABASE_PASSWORD,Env:MODELS_DIR,Env:ML_WORK_DIR -ErrorAction SilentlyContinue
    Import-ItEnv
    foreach ($k in $extraEnv.Keys) { Set-Item -Path "Env:$k" -Value $extraEnv[$k] }
    Start-Process -FilePath "mvn" -ArgumentList "-q","-pl",$module,"spring-boot:run","-DskipTests" -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError ($log + ".err")
    return Wait-Health $port 90
}

$db = "jdbc:postgresql://${pgHost}:${pgPort}/contextshield_it"
$services = @(
    @{ Port = 18081; Module = "services/risk-engine"; Env = @{}; Log = "$logDir\risk-restart.log" },
    @{ Port = 18082; Module = "services/ml-service"; Env = @{ DATABASE_URL = "${db}?currentSchema=ml"; DATABASE_USER = "ml_app"; DATABASE_PASSWORD = $env:ML_DB_PASSWORD; MODELS_DIR = "$root\machine-learning\models"; ML_WORK_DIR = "$root\target\it-ml-work" }; Log = "$logDir\ml-restart.log" },
    @{ Port = 18083; Module = "services/threat-service"; Env = @{ DATABASE_URL = "${db}?currentSchema=threat"; DATABASE_USER = "threat_app"; DATABASE_PASSWORD = $env:THREAT_DB_PASSWORD }; Log = "$logDir\threat-restart.log" },
    @{ Port = 18084; Module = "services/investigation-service"; Env = @{ DATABASE_URL = "${db}?currentSchema=investigation"; DATABASE_USER = "investigation_app"; DATABASE_PASSWORD = $env:INVESTIGATION_DB_PASSWORD }; Log = "$logDir\investigation-restart.log" },
    @{ Port = 18080; Module = "services/core-api"; Env = @{ DATABASE_URL = $db; DATABASE_USER = "core_app"; DATABASE_PASSWORD = $env:CORE_DB_PASSWORD; RISK_ENGINE_URL = "http://127.0.0.1:18081"; ML_SERVICE_URL = "http://127.0.0.1:18082"; THREAT_SERVICE_URL = "http://127.0.0.1:18083"; INVESTIGATION_SERVICE_URL = "http://127.0.0.1:18084" }; Log = "$logDir\core-restart-five.log" }
)

$results = @()
foreach ($s in $services) {
    Write-Host "Reiniciando :$($s.Port) $($s.Module)..."
    Stop-Port $s.Port
    $up = Start-ServiceIt $s.Module $s.Port $s.Env $s.Log
    $results += [pscustomobject]@{ Port = $s.Port; Up = $up }
    Write-Host ($(if ($up) { "[OK]" } else { "[FALHA]" })) " :$($s.Port)"
}

$after = & $psql -h $pgHost -p $pgPort -U postgres -d contextshield_it -t -A -c "SELECT count(*) FROM transactions;"
$out = "$root\docs\RESTART-FIVE-IT.md"
$rows = $results | ForEach-Object { "| $($_.Port) | $($_.Up) |" }
@(
    "# Reinício controlado IT $(Get-Date -Format 'yyyy-MM-dd HH:mm')",
    "",
    "transactions antes=$before depois=$after",
    "",
    "| Porta | UP |",
    "|---|---|"
) + $rows | Set-Content $out -Encoding UTF8
Write-Host "Relatorio: $out"
$fail = ($results | Where-Object { -not $_.Up }).Count -or ($after -ne $before)
exit $(if (-not $fail) { 0 } else { 1 })