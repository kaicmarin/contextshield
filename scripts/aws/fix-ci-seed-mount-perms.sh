#!/usr/bin/env bash
# Verifica permissoes do seed no host e leitura no container (:ro). Nao altera o arquivo.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=ci-seed-host-perms.sh
. "$ROOT/scripts/aws/ci-seed-host-perms.sh"

SEED_FILE="$ROOT/.ci/seed-credentials.json"
COMPOSE="${COMPOSE:-docker compose -f $ROOT/docker-compose.aws-free.yml -f $ROOT/docker-compose.ci-seed.yml}"

echo "== Verificar permissões no host (sem chmod/chown) =="
ci_seed_verify_host_permissions "$SEED_FILE"

echo "== Container: usuario app confirma leitura (mount :ro) =="
$COMPOSE run --rm --no-deps --entrypoint sh core-api -c \
  'uid=$(id -u); gid=$(id -g); test "$uid" = "10100" || { echo "uid inesperado: $uid"; exit 1; }; test -r /config/seed-credentials.json || { echo "app nao le seed (uid=$uid gid=$gid)"; exit 1; }; test -s /config/seed-credentials.json || { echo "seed vazio"; exit 1; }; head -c 1 /config/seed-credentials.json >/dev/null; echo "OK_app_read_seed uid=$uid ro_mount=true"'