#!/usr/bin/env bash
# Unico ponto para permissoes do .ci/seed-credentials.json no host (uid app do container = 10100).
# Nao imprime conteudo do arquivo.
set -euo pipefail

CONTEXTSHIELD_APP_UID="${CONTEXTSHIELD_APP_UID:-10100}"
CONTEXTSHIELD_APP_GID="${CONTEXTSHIELD_APP_GID:-10100}"

ci_seed__priv() {
  if [ "${GITHUB_ACTIONS:-}" = "true" ] && command -v sudo >/dev/null; then
    return 0
  fi
  return 1
}

# Gera/grava como runner, depois: chmod 600, chown app. Idempotente (sudo quando necessario).
ci_seed_set_host_permissions() {
  local f="$1"
  test -f "$f" || { echo "ci_seed_set_host_permissions: arquivo ausente: $f"; return 1; }
  if ci_seed__priv; then
    sudo chmod 600 "$f"
    sudo chown "${CONTEXTSHIELD_APP_UID}:${CONTEXTSHIELD_APP_GID}" "$f"
  else
    chmod 600 "$f"
    chown "${CONTEXTSHIELD_APP_UID}:${CONTEXTSHIELD_APP_GID}" "$f"
  fi
}

# Somente leitura/verificacao — nao altera o arquivo.
ci_seed_verify_host_permissions() {
  local f="$1"
  test -f "$f" || { echo "ci_seed_verify_host_permissions: arquivo ausente: $f"; return 1; }
  if ! command -v stat >/dev/null || ! stat -c '%u' "$f" >/dev/null 2>&1; then
    echo "ci_seed_verify_host_permissions: stat indisponivel; pulando checagem de uid no host"
    return 0
  fi
  local huid hgid hmode
  huid=$(stat -c '%u' "$f")
  hgid=$(stat -c '%g' "$f")
  hmode=$(stat -c '%a' "$f")
  test "$huid" = "$CONTEXTSHIELD_APP_UID" || {
    echo "seed host: uid=$huid esperado ${CONTEXTSHIELD_APP_UID}"
    return 1
  }
  test "$hgid" = "$CONTEXTSHIELD_APP_GID" || {
    echo "seed host: gid=$hgid esperado ${CONTEXTSHIELD_APP_GID}"
    return 1
  }
  test "$hmode" = "600" || {
    echo "seed host: modo=$hmode esperado 600"
    return 1
  }
  echo "OK seed host uid=$huid gid=$hgid mode=$hmode"
}

# Remove dono app para permitir regenerar credenciais (idempotente apply-ci-env).
ci_seed_prepare_regenerate() {
  local f="$1"
  [ ! -f "$f" ] && return 0
  [ -w "$f" ] && return 0
  if ci_seed__priv; then
    sudo chown "$(id -u):$(id -g)" "$f"
  else
    echo "ci_seed_prepare_regenerate: sem permissao para regravar $f"
    return 1
  fi
}