# Backup local (Windows). Para produção Linux use scripts/aws/backup.sh
$ErrorActionPreference = "Stop"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$dir = Join-Path $root.Path "backups"
$retention = if ($env:BACKUP_RETENTION_DAYS) { [int]$env:BACKUP_RETENTION_DAYS } else { 14 }
New-Item -ItemType Directory -Force -Path $dir | Out-Null

if (-not $env:DB_PASSWORD) { throw "Defina DB_PASSWORD (ou PGPASSWORD) antes do backup." }
$env:PGPASSWORD = $env:DB_PASSWORD
$pgHost = if ($env:PGHOST) { $env:PGHOST } else { "localhost" }
$pgPort = if ($env:PGPORT) { $env:PGPORT } else { "5432" }
$pgUser = if ($env:PGUSER) { $env:PGUSER } else { "contextshield" }
$pgDb = if ($env:PGDATABASE) { $env:PGDATABASE } else { "contextshield" }

$out = Join-Path $dir "contextshield-$stamp.dump"
$pgDump = if ($env:PG_DUMP_BIN) { $env:PG_DUMP_BIN } else { (Get-Command pg_dump.exe -ErrorAction Stop).Source }
& $pgDump -h $pgHost -p $pgPort -U $pgUser -d $pgDb -Fc -f $out
if (-not (Test-Path $out) -or (Get-Item $out).Length -lt 1000) { throw "O backup não foi gerado." }

$mlWork = if ($env:ML_WORK_DIR) { $env:ML_WORK_DIR } else { Join-Path $root.Path "data\ml-work" }
if (Test-Path $mlWork) {
  $mlArchive = Join-Path $dir "ml-work-$stamp.zip"
  Compress-Archive -Path (Join-Path $mlWork "*") -DestinationPath $mlArchive -Force
}

Get-ChildItem $dir -Filter "contextshield-*.dump" |
  Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-$retention) } |
  Remove-Item -Force
Get-ChildItem $dir -Filter "ml-work-*.zip" |
  Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-$retention) } |
  Remove-Item -Force

Write-Output $out