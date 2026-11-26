#!/usr/bin/env bash
# Atualização na EC2 (PostgreSQL no contêiner Docker local, não RDS).
# Um comando: backup lógico → build → sobe serviços (Flyway na subida do core) → verifica saúde e migração.
#
# Rollback: este script NÃO restaura backup automaticamente. O .sql.gz em backups/ é só para recuperação MANUAL
# (incidente, migração corrompida). Restaurar um dump antigo sobre dados novos APAGA transações posteriores ao backup.
# Procedimento manual documentado em docs/AWS-SINGLE-UPDATE.md — nunca pipe pg_dump antigo em produção sem janela aprovada.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
test -f .env || { echo "Falta .env (SEED_ENABLED=false, JWT_SECRET, CORE_DB_PASSWORD, DB_PASSWORD)"; exit 1; }

COMPOSE="${COMPOSE:-docker compose -f $ROOT/docker-compose.aws-free.yml}"
BACKUP_DIR="${BACKUP_DIR:-$ROOT/backups}"
STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP_DIR"

echo "== 1/5 Backup PostgreSQL (volume preservado) =="
set -a; # shellcheck disable=SC1091
source "$ROOT/.env"
set +a
BACKUP_FILE="$BACKUP_DIR/contextshield-$STAMP.sql.gz"
$COMPOSE exec -T postgres pg_dump -U contextshield contextshield | gzip -c > "$BACKUP_FILE"
echo "Backup em $BACKUP_FILE ($(du -h "$BACKUP_FILE" | cut -f1))"

echo "== 2/5 Build imagens =="
$COMPOSE build core-api proxy risk-engine ml-service threat-service investigation-service

echo "== 3/5 Recria contêineres (sem apagar volume pgdata) =="
$COMPOSE up -d postgres
$COMPOSE up -d risk-engine ml-service threat-service investigation-service
$COMPOSE up -d core-api proxy

echo "== 4/5 Aguarda core-api e Flyway =="
TRIES=36
until $COMPOSE exec -T core-api wget -qO- http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; do
  TRIES=$((TRIES - 1))
  if [ "$TRIES" -le 0 ]; then
    echo "core-api não ficou saudável a tempo"
    $COMPOSE logs --no-color core-api
    exit 1
  fi
  sleep 5
done
FLYWAY_MAX=$($COMPOSE exec -T postgres psql -U contextshield -d contextshield -tAc \
  "select coalesce(max(version::text),'0') from flyway_schema_history where success" | tr -d '[:space:]')
ML_FLY=$($COMPOSE exec -T postgres psql -U contextshield -d contextshield -tAc \
  "select coalesce(max(version::text),'0') from ml.flyway_ml_history where success" | tr -d '[:space:]')
THREAT_FLY=$($COMPOSE exec -T postgres psql -U contextshield -d contextshield -tAc \
  "select coalesce(max(version::text),'0') from threat.flyway_threat_history where success" | tr -d '[:space:]')
INVEST_FLY=$($COMPOSE exec -T postgres psql -U contextshield -d contextshield -tAc \
  "select coalesce(max(version::text),'0') from investigation.flyway_investigation_history where success" | tr -d '[:space:]')
echo "Flyway core=$FLYWAY_MAX ml=$ML_FLY threat=$THREAT_FLY investigation=$INVEST_FLY"
test -d machine-learning/models && echo "Modelos .ser publicados em machine-learning/models preservados no host/imagem."

echo "== 5/5 Health dos microsserviços =="
$COMPOSE exec -T risk-engine wget -qO- http://127.0.0.1:8081/actuator/health | grep -q UP
$COMPOSE exec -T ml-service wget -qO- http://127.0.0.1:8082/actuator/health | grep -q UP
$COMPOSE exec -T threat-service wget -qO- http://127.0.0.1:8083/actuator/health | grep -q UP
$COMPOSE exec -T investigation-service wget -qO- http://127.0.0.1:8084/actuator/health | grep -q UP

echo ""
echo "OK — deploy concluído sem resetar pgdata. Usuários e transações preservados."
echo "Senhas: não alteradas. Migrações Flyway aplicadas na subida do core-api."
echo "Regras dinâmicas: publicadas pelo Operations entram na próxima compra, sem reinício."