# Backup local do PostgreSQL. Não envia o arquivo para lugar nenhum.
$ErrorActionPreference = "Stop"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dir = Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path "backups"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$out = Join-Path $dir "contextshield-$stamp.dump"
$env:PGPASSWORD = if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { "contextshield" }
& "C:\Program Files\PostgreSQL\18\bin\pg_dump.exe" -h localhost -U contextshield -d contextshield -Fc -f $out
if (-not (Test-Path $out) -or (Get-Item $out).Length -lt 1000) { throw "O backup não foi gerado." }
Write-Output $out
