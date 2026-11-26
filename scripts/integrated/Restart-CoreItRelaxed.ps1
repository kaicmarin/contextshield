function Restart-CoreItRelaxed {
    param([string]$Root)
    . (Join-Path $PSScriptRoot "Import-ItEnv.ps1")
    Import-ItEnv
    if (-not $Root) { $Root = Get-ItRoot }
    if ($env:JAVA_HOME) { $env:Path = "$env:JAVA_HOME\bin;" + $env:Path }
    $pgHost = if ($env:PGHOST) { $env:PGHOST } else { "127.0.0.1" }
    $pgPort = if ($env:PGPORT) { $env:PGPORT } else { "5433" }
    $db = "jdbc:postgresql://${pgHost}:${pgPort}/contextshield_it"

    $listen = Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listen) { Stop-Process -Id $listen.OwningProcess -Force -ErrorAction SilentlyContinue; Start-Sleep -Seconds 4 }

    $env:PORT = "18080"
    $env:DATABASE_URL = $db
    $env:DATABASE_USER = "core_app"
    $env:DATABASE_PASSWORD = $env:CORE_DB_PASSWORD
    $env:RISK_ENGINE_URL = "http://127.0.0.1:18081"
    $env:ML_SERVICE_URL = "http://127.0.0.1:18082"
    $env:THREAT_SERVICE_URL = "http://127.0.0.1:18083"
    $env:INVESTIGATION_SERVICE_URL = "http://127.0.0.1:18084"
    $env:SEED_ENABLED = "false"
    $env:SEED_ALLOW_DEMO_RESET = "true"
    $env:LOGIN_PER_MINUTE_EMAIL = "500"
    $env:LOGIN_PER_MINUTE_IP = "500"
    $env:PASSWORD_CHANGE_PER_MINUTE_EMAIL = "500"

    $logDir = Join-Path $Root "target\it-logs"
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $log = Join-Path $logDir "core-password-it.log"
    Start-Process -FilePath "mvn" -ArgumentList "-q","-pl","services/core-api","spring-boot:run","-DskipTests" -WorkingDirectory $Root -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError ($log + ".err")

    for ($i = 0; $i -lt 90; $i++) {
        try {
            $h = Invoke-RestMethod "http://127.0.0.1:18080/actuator/health" -TimeoutSec 2
            if ($h.status -eq "UP") { return $true }
        } catch { Start-Sleep -Seconds 2 }
    }
    return $false
}