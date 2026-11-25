#!/usr/bin/env bash
# Restaura backup em ambiente ISOLADO. Não use no banco principal sem revisão.
set -euo pipefail

if [ $# -lt 1 ]; then
  echo "Uso: $0 caminho/para/contextshield-YYYYMMDD-HHMMSS.dump [ml-work-....tar.gz]" >&2
  exit 1
fi

DUMP="$1"
ML_ARCHIVE="${2:-}"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE="${COMPOSE:-docker compose -f $ROOT/docker-compose.aws-free.yml}"

if [ ! -f "$DUMP" ]; then
  echo "Arquivo não encontrado: $DUMP" >&2
  exit 1
fi

"$COMPOSE" up -d postgres
"$COMPOSE" exec -T postgres pg_restore -U contextshield -d contextshield --clean --if-exists < "$DUMP"

if [ -n "$ML_ARCHIVE" ] && [ -f "$ML_ARCHIVE" ]; then
  docker run --rm -v contextshield_mlwork:/data -v "$(dirname "$ML_ARCHIVE")":/in alpine \
    sh -c "rm -rf /data/* && tar -xzf /in/$(basename "$ML_ARCHIVE") -C /data"
fi

echo "Restauração concluída. Valide transações, casos e modelos antes de expor o serviço."