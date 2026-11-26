#!/usr/bin/env bash
# Validação descartável do deploy (Docker local/CI). Não usa EC2 de produção.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
test -f .env || cp .env.example .env
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-cs-deploy-validate}"
export COMPOSE="docker compose -f $ROOT/docker-compose.aws-free.yml"

if grep -q 'troque-este-segredo' .env 2>/dev/null; then
  # CI/local: valores mínimos para subir o compose (não usar em produção).
  if command -v sed >/dev/null; then
    sed -i.bak 's/^JWT_SECRET=.*/JWT_SECRET=ci-isolated-jwt-secret-32chars-min-ok/' .env
    sed -i.bak 's/^DB_PASSWORD=.*/DB_PASSWORD=ci-db-pass-change-me/' .env
    sed -i.bak 's/^CORE_DB_PASSWORD=.*/CORE_DB_PASSWORD=ci-core-pass-change-me/' .env
    sed -i.bak 's/^THREAT_DB_PASSWORD=.*/THREAT_DB_PASSWORD=ci-threat-pass/' .env
    sed -i.bak 's/^INVESTIGATION_DB_PASSWORD=.*/INVESTIGATION_DB_PASSWORD=ci-invest-pass/' .env
    sed -i.bak 's/^ML_DB_PASSWORD=.*/ML_DB_PASSWORD=ci-ml-pass/' .env
    sed -i.bak 's/^JWT_ALLOW_DEV_SECRET=.*/JWT_ALLOW_DEV_SECRET=true/' .env
    sed -i.bak 's/^SEED_ENABLED=.*/SEED_ENABLED=true/' .env
    rm -f .env.bak
  fi
fi

echo "== Subir Postgres (volume pgdata do projeto $COMPOSE_PROJECT_NAME) =="
$COMPOSE up -d postgres
for _ in $(seq 1 36); do
  if $COMPOSE exec -T postgres pg_isready -U contextshield >/dev/null 2>&1; then break; fi
  sleep 2
done

USERS_BEFORE=$($COMPOSE exec -T postgres psql -U contextshield -d contextshield -tAc "select count(*) from app_user" | tr -d '[:space:]')
RULES_BEFORE=$($COMPOSE exec -T postgres psql -U contextshield -d contextshield -tAc "select count(*) from risk_engine_rule where status='PUBLISHED'" 2>/dev/null | tr -d '[:space:]' || echo "0")
MODELS_HOST=$(find machine-learning/models -name '*.ser' 2>/dev/null | wc -l | tr -d '[:space:]')

echo "== Build e subida (Flyway na core + ml-service) =="
$COMPOSE build core-api risk-engine ml-service threat-service investigation-service
$COMPOSE up -d risk-engine ml-service threat-service investigation-service
$COMPOSE up -d core-api

TRIES=50
until $COMPOSE exec -T core-api wget -qO- http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; do
  TRIES=$((TRIES - 1))
  test "$TRIES" -gt 0 || { echo "core-api unhealthy"; $COMPOSE logs --tail 40 core-api; exit 1; }
  sleep 3
done

FLYWAY_MAX=$($COMPOSE exec -T postgres psql -U contextshield -d contextshield -tAc \
  "select coalesce(max(version::int),0) from flyway_schema_history where success" | tr -d '[:space:]')
for v in 12 13 14; do
  C=$($COMPOSE exec -T postgres psql -U contextshield -d contextshield -tAc \
    "select count(*) from flyway_schema_history where version='$v' and success" | tr -d '[:space:]')
  test "$C" -ge 1 || { echo "Flyway core V$v ausente"; exit 1; }
done
ML_V=$($COMPOSE exec -T postgres psql -U contextshield -d contextshield -tAc \
  "select coalesce(max(version::int),0) from flyway_ml_history where success" 2>/dev/null | tr -d '[:space:]' || echo "0")
RULES_AFTER=$($COMPOSE exec -T postgres psql -U contextshield -d contextshield -tAc \
  "select count(*) from risk_engine_rule where status='PUBLISHED'" | tr -d '[:space:]')

mkdir -p "$ROOT/backups"
BAK="$ROOT/backups/isolated-$(date +%Y%m%d-%H%M%S).sql.gz"
$COMPOSE exec -T postgres pg_dump -U contextshield contextshield | gzip -c > "$BAK"
test -s "$BAK"

USERS_AFTER=$($COMPOSE exec -T postgres psql -U contextshield -d contextshield -tAc "select count(*) from app_user" | tr -d '[:space:]')

$COMPOSE exec -T risk-engine wget -qO- http://127.0.0.1:8081/actuator/health | grep -q UP
$COMPOSE exec -T ml-service wget -qO- http://127.0.0.1:8082/actuator/health | grep -q UP
$COMPOSE exec -T threat-service wget -qO- http://127.0.0.1:8083/actuator/health | grep -q UP
$COMPOSE exec -T investigation-service wget -qO- http://127.0.0.1:8084/actuator/health | grep -q UP

echo "== Segundo deploy (preserva pgdata + volumes; sem restaurar backup) =="
export COMPOSE
export COMPOSE_PROJECT_NAME
bash "$ROOT/scripts/aws/deploy-app-update.sh"

USERS_2=$($COMPOSE exec -T postgres psql -U contextshield -d contextshield -tAc "select count(*) from app_user" | tr -d '[:space:]")
POL_PUBLISHED=$($COMPOSE exec -T postgres psql -U contextshield -d contextshield -tAc \
  "select count(*) from risk_policy where status='PUBLISHED'" 2>/dev/null | tr -d '[:space:]' || echo "0")

cat >"$ROOT/docs/DEPLOY-ISOLATED-TEST.json" <<EOF
{
  "backupCreated": true,
  "backupPath": "$BAK",
  "autoRestore": false,
  "flywayCoreMax": $FLYWAY_MAX,
  "flywayMlMax": $ML_V,
  "flywayV12": true,
  "flywayV13": true,
  "flywayV14": true,
  "rulesPublished": $RULES_AFTER,
  "policiesPublished": $POL_PUBLISHED,
  "usersPreservedFirstDeploy": $([ "$USERS_BEFORE" = "$USERS_AFTER" ] && echo true || echo false),
  "usersPreservedRedeploy": $([ "$USERS_AFTER" = "$USERS_2" ] && echo true || echo false),
  "hostSerFiles": $MODELS_HOST
}
EOF

echo "OK validate-deploy-isolated (projeto $COMPOSE_PROJECT_NAME, rollback manual apenas)"