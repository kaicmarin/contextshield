# Encerra JVMs IT (18080-18084) e PostgreSQL 5433 (target/pg-it); preserva dados e logs.
. (Join-Path $PSScriptRoot "Import-ItEnv.ps1")
$root = Get-ItRoot
foreach ($port in 5173, 18080, 18081, 18082, 18083, 18084) {
    $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    foreach ($conn in $c) {
        Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
        Write-Host "Parado :$port PID $($conn.OwningProcess)"
    }
}
$pgCtl = if ($env:PG_CTL_BIN) { $env:PG_CTL_BIN } else { "${env:ProgramFiles}\PostgreSQL\18\bin\pg_ctl.exe" }
$data = "$root\target\pg-it"
if (Test-Path $data) {
    & $pgCtl -D $data stop -m fast 2>&1 | Out-Host
    Write-Host "PostgreSQL 5433 parado (dados em $data)"
}