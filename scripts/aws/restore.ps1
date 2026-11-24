# Restaura um dump em um banco vazio de teste. Não aponta para a AWS.
param([Parameter(Mandatory = $true)][string]$Dump, [string]$Database = "contextshield_restore")
$ErrorActionPreference = "Stop"
$env:PGPASSWORD = if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { "contextshield" }
$psql = "C:\Program Files\PostgreSQL\18\bin\psql.exe"
$restore = "C:\Program Files\PostgreSQL\18\bin\pg_restore.exe"
& $psql -h localhost -U contextshield -d postgres -c "create database $Database"
& $restore -h localhost -U contextshield -d $Database --no-owner $Dump
Write-Output "Restaurado em $Database. Confira e apague esse banco quando terminar."
