#!/usr/bin/env bash
# Credenciais descartáveis para Docker Compose em CI/local isolado (nunca produção).
# Não imprime senhas. Não sobrescreve .env de produção real.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
test -f .env.example || { echo "Falta .env.example"; exit 1; }

if [ ! -f .env ]; then
  cp .env.example .env
fi

env_looks_disposable() {
  grep -qE '^DB_PASSWORD=troque-|^JWT_SECRET=troque-' .env 2>/dev/null
}

if [ "${GITHUB_ACTIONS:-}" = "true" ] || [ "${CI_ISOLATED_VALIDATE:-}" = "1" ]; then
  :
elif env_looks_disposable; then
  :
else
  echo "apply-ci-env: recusado — .env parece ambiente real. Use CI_ISOLATED_VALIDATE=1 só em projeto Docker isolado."
  exit 1
fi

CI_DB_PASSWORD="${CI_DB_PASSWORD:-ci-github-disposable-postgres}"
CI_CORE_DB_PASSWORD="${CI_CORE_DB_PASSWORD:-ci-github-disposable-core}"
CI_THREAT_DB_PASSWORD="${CI_THREAT_DB_PASSWORD:-ci-github-disposable-threat}"
CI_INVESTIGATION_DB_PASSWORD="${CI_INVESTIGATION_DB_PASSWORD:-ci-github-disposable-investigation}"
CI_ML_DB_PASSWORD="${CI_ML_DB_PASSWORD:-ci-github-disposable-ml}"
CI_JWT_SECRET="${CI_JWT_SECRET:-ci-github-disposable-jwt-secret-32chars-min}"
CI_SEED_ENABLED="${CI_SEED_ENABLED:-true}"
CI_JWT_ALLOW_DEV_SECRET="${CI_JWT_ALLOW_DEV_SECRET:-true}"

export ROOT CI_DB_PASSWORD CI_CORE_DB_PASSWORD CI_THREAT_DB_PASSWORD CI_INVESTIGATION_DB_PASSWORD CI_ML_DB_PASSWORD CI_JWT_SECRET CI_SEED_ENABLED CI_JWT_ALLOW_DEV_SECRET

mkdir -p "$ROOT/.ci"

python3 <<'PY'
import json
import os
import secrets
import string
from pathlib import Path

root = Path(os.environ["ROOT"])
env_path = root / ".env"
ci_dir = root / ".ci"
ci_dir.mkdir(parents=True, exist_ok=True)

emails = [
    "ana.martins@correio.example",
    "carlos.menezes@correio.example",
    "mariana.rocha@correio.example",
    "rafael.nunes@correio.example",
    "beatriz.lima@correio.example",
    "diego.faria@correio.example",
    "helena.prado@correio.example",
    "pedro.alves@correio.example",
    "joana.ribeiro@correio.example",
    "analista@contextshield.example",
    "admin@contextshield.example",
]

alphabet = string.ascii_letters + string.digits + "-_+"

def one_password() -> str:
    # 16+ chars, único por execução — só válido no volume descartável do CI.
    core = "".join(secrets.choice(alphabet) for _ in range(18))
    return f"CiDisp-{core}"

passwords = {mail: one_password() for mail in emails}
cred_path = ci_dir / "seed-credentials.json"
cred_path.write_text(
    json.dumps({"passwords": passwords}, indent=2, ensure_ascii=False) + "\n",
    encoding="utf-8",
)
os.chmod(cred_path, 0o600)

keys = {
    "DB_PASSWORD": os.environ["CI_DB_PASSWORD"],
    "CORE_DB_PASSWORD": os.environ["CI_CORE_DB_PASSWORD"],
    "THREAT_DB_PASSWORD": os.environ["CI_THREAT_DB_PASSWORD"],
    "INVESTIGATION_DB_PASSWORD": os.environ["CI_INVESTIGATION_DB_PASSWORD"],
    "ML_DB_PASSWORD": os.environ["CI_ML_DB_PASSWORD"],
    "JWT_SECRET": os.environ["CI_JWT_SECRET"],
    "SEED_ENABLED": os.environ["CI_SEED_ENABLED"],
    "JWT_ALLOW_DEV_SECRET": os.environ["CI_JWT_ALLOW_DEV_SECRET"],
    "SEED_CREDENTIALS_FILE": "file:/config/seed-credentials.json",
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

echo "OK apply-ci-env (.env descartável + .ci/seed-credentials.json gerado; nenhuma senha impressa)"