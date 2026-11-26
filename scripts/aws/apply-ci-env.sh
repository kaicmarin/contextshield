#!/usr/bin/env bash
# Credenciais descartáveis para Docker Compose em CI/local isolado (nunca produção).
# Não imprime senhas. Atualiza .env a partir de placeholders ou modo CI explícito.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
test -f .env.example || { echo "Falta .env.example"; exit 1; }
if [ ! -f .env ]; then cp .env.example .env; fi

CI_DB_PASSWORD="${CI_DB_PASSWORD:-ci-github-disposable-postgres}"
CI_CORE_DB_PASSWORD="${CI_CORE_DB_PASSWORD:-ci-github-disposable-core}"
CI_THREAT_DB_PASSWORD="${CI_THREAT_DB_PASSWORD:-ci-github-disposable-threat}"
CI_INVESTIGATION_DB_PASSWORD="${CI_INVESTIGATION_DB_PASSWORD:-ci-github-disposable-investigation}"
CI_ML_DB_PASSWORD="${CI_ML_DB_PASSWORD:-ci-github-disposable-ml}"
CI_JWT_SECRET="${CI_JWT_SECRET:-ci-github-disposable-jwt-secret-32chars-min}"
CI_SEED_ENABLED="${CI_SEED_ENABLED:-true}"
CI_JWT_ALLOW_DEV_SECRET="${CI_JWT_ALLOW_DEV_SECRET:-true}"

export ROOT CI_DB_PASSWORD CI_CORE_DB_PASSWORD CI_THREAT_DB_PASSWORD CI_INVESTIGATION_DB_PASSWORD CI_ML_DB_PASSWORD CI_JWT_SECRET CI_SEED_ENABLED CI_JWT_ALLOW_DEV_SECRET

python3 <<'PY'
import os
from pathlib import Path

root = Path(os.environ["ROOT"])
env_path = root / ".env"
keys = {
    "DB_PASSWORD": os.environ["CI_DB_PASSWORD"],
    "CORE_DB_PASSWORD": os.environ["CI_CORE_DB_PASSWORD"],
    "THREAT_DB_PASSWORD": os.environ["CI_THREAT_DB_PASSWORD"],
    "INVESTIGATION_DB_PASSWORD": os.environ["CI_INVESTIGATION_DB_PASSWORD"],
    "ML_DB_PASSWORD": os.environ["CI_ML_DB_PASSWORD"],
    "JWT_SECRET": os.environ["CI_JWT_SECRET"],
    "SEED_ENABLED": os.environ["CI_SEED_ENABLED"],
    "JWT_ALLOW_DEV_SECRET": os.environ["CI_JWT_ALLOW_DEV_SECRET"],
}
lines = env_path.read_text(encoding="utf-8").splitlines()
out, seen = [], set()
for line in lines:
    s = line.strip()
    if not s or s.startswith("#") or "=" not in line:
        out.append(line)
        continue
    k = line.split("=", 1)[0].strip()
    if k in keys:
        out.append(f"{k}={keys[k]}")
        seen.add(k)
    else:
        out.append(line)
for k, v in keys.items():
    if k not in seen:
        out.append(f"{k}={v}")
env_path.write_text("\n".join(out) + "\n", encoding="utf-8")
PY

echo "OK apply-ci-env (.env preparado para compose isolado, sem credenciais de produção)"