# Atualização de código na EC2 (Linux) — NÃO migra senhas nem reseta usuários no RDS.
# Uso na instância, a partir da raiz do repositório:
#   ./scripts/aws/deploy-app-update.ps1
# Requer: docker compose, .env de produção (JWT, CORE_DB_PASSWORD, SEED_ENABLED=false).
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $Root
if (-not (Test-Path "$Root\.env")) { throw "Crie .env a partir de .env.example (sem DEMO_PASSWORD)." }

$BackupDir = Join-Path $Root "backups"
New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$BackupFile = Join-Path $BackupDir "contextshield-$Stamp.sql.gz"
Write-Host "1. Backup PostgreSQL (volume pgdata preservado)..."
docker compose -f docker-compose.aws-free.yml exec -T postgres pg_dump -U contextshield contextshield | gzip -c > $BackupFile
Write-Host "   -> $BackupFile"

Write-Host "2. Build imagens..."
docker compose -f docker-compose.aws-free.yml build core-api proxy risk-engine ml-service threat-service investigation-service

Write-Host "3. Sobe serviços (Flyway na subida do core-api)..."
docker compose -f docker-compose.aws-free.yml up -d postgres
docker compose -f docker-compose.aws-free.yml up -d risk-engine ml-service threat-service investigation-service
docker compose -f docker-compose.aws-free.yml up -d core-api proxy

Write-Host "4. Health checks..."
& (Join-Path $PSScriptRoot "health.ps1")
$flyway = docker compose -f docker-compose.aws-free.yml exec -T postgres psql -U contextshield -d contextshield -tAc "select coalesce(max(version::text),'0') from flyway_schema_history"
Write-Host "   Flyway core versão: $($flyway.Trim())"

Write-Host @"

Deploy de aplicação concluído. Senhas existentes no PostgreSQL NÃO foram alteradas.
Próximos passos manuais (produção):
  - Migrar contas legadas (ex. senha compartilhada) via admin: PUT /api/admin/users/{id} com senha forte individual.
  - Ou pedir troca pelo usuário em /loja/conta/senha, /banco/senha, /ops/conta/senha.
  - Nunca commitar credenciais; manter SEED_ENABLED=false (já no compose AWS).
"@