#!/usr/bin/env bash
# Permissões do seed só no host (bind mount :ro). Dentro do container: teste de leitura como USER app.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SEED_FILE="$ROOT/.ci/seed-credentials.json"
APP_UID="${CONTEXTSHIELD_APP_UID:-10100}"
APP_GID="${CONTEXTSHIELD_APP_GID:-10100}"

test -f "$SEED_FILE" || { echo "fix-ci-seed-mount-perms: $SEED_FILE ausente"; exit 1; }

echo "== Host: chmod 600 e chown ${APP_UID}:${APP_GID} (antes do mount :ro) =="
chmod 600 "$SEED_FILE"
if ! chown "${APP_UID}:${APP_GID}" "$SEED_FILE" 2>/dev/null; then
  if command -v sudo >/dev/null; then
    sudo chown "${APP_UID}:${APP_GID}" "$SEED_FILE"
  else
    echo "fix-ci-seed-mount-perms: chown ${APP_UID}:${APP_GID} falhou no host"
    exit 1
  fi
fi

if command -v stat >/dev/null && stat -c '%u' "$SEED_FILE" >/dev/null 2>&1; then
  HUID=$(stat -c '%u' "$SEED_FILE")
  HGID=$(stat -c '%g' "$SEED_FILE")
  HMODE=$(stat -c '%a' "$SEED_FILE")
  test "$HUID" = "$APP_UID" || { echo "dono host incorreto (uid=$HUID esperado $APP_UID)"; exit 1; }
  test "$HGID" = "$APP_GID" || { echo "grupo host incorreto (gid=$HGID esperado $APP_GID)"; exit 1; }
  test "$HMODE" = "600" || { echo "modo host incorreto ($HMODE esperado 600)"; exit 1; }
fi

COMPOSE="${COMPOSE:-docker compose -f $ROOT/docker-compose.aws-free.yml -f $ROOT/docker-compose.ci-seed.yml}"

echo "== Container: usuario app verifica leitura (mount :ro, sem chown no container) =="
$COMPOSE run --rm --no-deps --entrypoint sh core-api -c \
  'uid=$(id -u); gid=$(id -g); test "$uid" = "10100" || { echo "uid inesperado: $uid"; exit 1; }; test -r /config/seed-credentials.json || { echo "app nao le seed (uid=$uid gid=$gid)"; exit 1; }; test -s /config/seed-credentials.json || { echo "seed vazio"; exit 1; }; head -c 1 /config/seed-credentials.json >/dev/null; echo "OK_app_read_seed uid=$uid ro_mount=true"'