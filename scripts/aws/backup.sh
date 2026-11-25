#!/usr/bin/env bash
# Backup PostgreSQL + volume de trabalho do ML (Linux/Docker/EC2).
# Não grava senhas no script: use .env ou variáveis de ambiente.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
DEST="${BACKUP_DIR:-$ROOT/backups}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
mkdir -p "$DEST"

PGUSER="${PGUSER:-contextshield}"
PGDB="${PGDATABASE:-contextshield}"
COMPOSE="${COMPOSE:-docker compose -f $ROOT/docker-compose.aws-free.yml}"

if [ -z "${DB_PASSWORD:-}" ]; then
  echo "Defina DB_PASSWORD no ambiente ou no .env" >&2
  exit 1
fi

DUMP="$DEST/contextshield-$STAMP.dump"
"$COMPOSE" exec -T postgres pg_dump -U "$PGUSER" -d "$PGDB" -Fc > "$DUMP"
if [ ! -s "$DUMP" ]; then
  echo "Backup PostgreSQL vazio" >&2
  exit 1
fi

ML_ARCHIVE="$DEST/ml-work-$STAMP.tar.gz"
if docker volume inspect contextshield_mlwork >/dev/null 2>&1; then
  docker run --rm -v contextshield_mlwork:/data:ro -v "$DEST":/out alpine \
    tar -czf "/out/ml-work-$STAMP.tar.gz" -C /data .
else
  echo "Volume mlwork não encontrado; apenas o dump do banco foi gerado." >&2
fi

find "$DEST" -name 'contextshield-*.dump' -mtime +"$RETENTION_DAYS" -delete
find "$DEST" -name 'ml-work-*.tar.gz' -mtime +"$RETENTION_DAYS" -delete

echo "$DUMP"
[ -f "$ML_ARCHIVE" ] && echo "$ML_ARCHIVE"