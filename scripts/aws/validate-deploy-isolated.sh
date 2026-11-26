#!/usr/bin/env bash
# Validação descartável do deploy (Docker local/CI). Não usa EC2 de produção.
# Fluxo: Postgres → primeiro deploy (Flyway + seed) → baseline de dados → backup → deploy-app-update → preservação.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-cs-deploy-validate}"
export CI_ISOLATED_VALIDATE=1
export COMPOSE="docker compose -f $ROOT/docker-compose.aws-free.yml -f $ROOT/docker-compose.ci-seed.yml"

bash "$ROOT/scripts/aws/apply-ci-env.sh"
test -f "$ROOT/.ci/seed-credentials.json" || { echo "Credenciais de seed CI ausentes (.ci/seed-credentials.json)"; exit 1; }

set -a
# shellcheck disable=SC1091
source "$ROOT/.env"
set +a

psql_count() {
  $COMPOSE exec -T postgres psql -U contextshield -d contextshield -tAc "$1" | tr -d '[:space:]'
}

# shellcheck source=flyway-sql-checks.sh
. "$ROOT/scripts/aws/flyway-sql-checks.sh"

dump_core_api_logs() {
  local dest="$ROOT/docs/DEPLOY-ISOLATED-CORE-API.log"
  mkdir -p "$(dirname "$dest")"
  $COMPOSE logs --no-color core-api >"$dest" 2>&1 || true
  echo "========== core-api (log completo: $dest) =========="
  cat "$dest"
}

wait_core_up() {
  local tries="${1:-50}"
  until $COMPOSE exec -T core-api wget -qO- http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; do
    tries=$((tries - 1))
    if [ "$tries" -le 0 ]; then
      echo "core-api unhealthy (primeira subida ou redeploy)"
      dump_core_api_logs
      exit 1
    fi
    sleep 3
  done
}

assert_health_five() {
  $COMPOSE exec -T core-api wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"'
  $COMPOSE exec -T risk-engine wget -qO- http://127.0.0.1:8081/actuator/health | grep -q UP
  $COMPOSE exec -T ml-service wget -qO- http://127.0.0.1:8082/actuator/health | grep -q UP
  $COMPOSE exec -T threat-service wget -qO- http://127.0.0.1:8083/actuator/health | grep -q UP
  $COMPOSE exec -T investigation-service wget -qO- http://127.0.0.1:8084/actuator/health | grep -q UP
}

echo "== Subir Postgres (volume pgdata do projeto $COMPOSE_PROJECT_NAME) =="
$COMPOSE up -d postgres
for _ in $(seq 1 36); do
  if $COMPOSE exec -T postgres pg_isready -U contextshield >/dev/null 2>&1; then break; fi
  sleep 2
done
$COMPOSE exec -T postgres pg_isready -U contextshield

MODELS_HOST=$(find machine-learning/models -name '*.ser' 2>/dev/null | wc -l | tr -d '[:space:]')

echo "== Primeiro deploy: build + Flyway (schema + dados seed no volume existente) =="
$COMPOSE build core-api risk-engine ml-service threat-service investigation-service
bash "$ROOT/scripts/aws/fix-ci-seed-mount-perms.sh"
$COMPOSE up -d risk-engine ml-service threat-service investigation-service
$COMPOSE up -d core-api

wait_core_up 50
assert_health_five
assert_flyway_core_12_14
assert_flyway_ml_1_3
assert_flyway_threat_ok
assert_flyway_investigation_ok

USERS_BASELINE=0
for _ in $(seq 1 45); do
  USERS_BASELINE=$(psql_count "select count(*) from app_user")
  test "$USERS_BASELINE" -gt 0 && break
  sleep 2
done
RULES_BASELINE=$(psql_count "select count(*) from risk_engine_rule where status='PUBLISHED'")
POL_BASELINE=$(psql_count "select count(*) from risk_policy where status='PUBLISHED'")

test "$USERS_BASELINE" -gt 0 || { echo "Nenhum app_user após seed/migrações; verifique SEED_ENABLED"; exit 1; }
test "$RULES_BASELINE" -ge 12 || { echo "Regras publicadas V12 insuficientes ($RULES_BASELINE)"; exit 1; }
test "$POL_BASELINE" -ge 1 || { echo "Nenhuma política publicada após deploy inicial"; exit 1; }

echo "== Baseline: usuarios=$USERS_BASELINE regras_publicadas=$RULES_BASELINE politicas_publicadas=$POL_BASELINE =="

mkdir -p "$ROOT/backups"
BAK="$ROOT/backups/isolated-$(date +%Y%m%d-%H%M%S).sql.gz"
$COMPOSE exec -T postgres pg_dump -U contextshield contextshield | gzip -c > "$BAK"
test -s "$BAK"
echo "Backup lógico criado ($(du -h "$BAK" | cut -f1)); sem restauração automática."

echo "== Segundo deploy (deploy-app-update: backup + build + migrações idempotentes) =="
export COMPOSE
export COMPOSE_PROJECT_NAME
bash "$ROOT/scripts/aws/deploy-app-update.sh"

wait_core_up 40
assert_health_five
assert_flyway_core_12_14
assert_flyway_ml_1_3
assert_flyway_threat_ok
assert_flyway_investigation_ok

FLYWAY_MAX=$(flyway_core_max)
ML_V=$(flyway_ml_max)

USERS_AFTER=$(psql_count "select count(*) from app_user")
RULES_AFTER=$(psql_count "select count(*) from risk_engine_rule where status='PUBLISHED'")
POL_AFTER=$(psql_count "select count(*) from risk_policy where status='PUBLISHED'")

test "$USERS_BASELINE" = "$USERS_AFTER" || { echo "Usuarios alterados após redeploy ($USERS_BASELINE -> $USERS_AFTER)"; exit 1; }
test "$RULES_BASELINE" = "$RULES_AFTER" || { echo "Regras publicadas alteradas ($RULES_BASELINE -> $RULES_AFTER)"; exit 1; }
test "$POL_BASELINE" = "$POL_AFTER" || { echo "Politicas publicadas alteradas ($POL_BASELINE -> $POL_AFTER)"; exit 1; }

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
  "flywayMlV2": true,
  "flywayMlV3": true,
  "usersBaseline": $USERS_BASELINE,
  "rulesPublishedBaseline": $RULES_BASELINE,
  "policiesPublishedBaseline": $POL_BASELINE,
  "usersAfterRedeploy": $USERS_AFTER,
  "rulesPublishedAfterRedeploy": $RULES_AFTER,
  "policiesPublishedAfterRedeploy": $POL_AFTER,
  "usersPreserved": true,
  "hostSerFiles": $MODELS_HOST
}
EOF

LOG_DEST="$ROOT/docs/DEPLOY-ISOLATED-CORE-API.log"
if [ ! -f "$LOG_DEST" ]; then
  $COMPOSE logs --no-color core-api >"$LOG_DEST" 2>&1 || true
fi

echo "OK validate-deploy-isolated (projeto $COMPOSE_PROJECT_NAME, rollback manual apenas)"