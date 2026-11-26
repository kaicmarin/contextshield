#!/usr/bin/env bash
# Consultas Flyway alinhadas aos schemas PostgreSQL (public, ml, threat, investigation).
# Carregue após definir psql_count() ou psql_query().
set -euo pipefail

assert_flyway_core_12_14() {
  for v in 12 13 14; do
    C=$(psql_count "select count(*) from flyway_schema_history where version='$v' and success")
    test "$C" -ge 1 || { echo "Flyway core V$v ausente"; exit 1; }
  done
}

assert_flyway_ml_1_3() {
  C=$(psql_count "select count(*) from ml.flyway_ml_history where version='1' and success")
  test "$C" -ge 1 || { echo "Flyway ml V1 ausente"; exit 1; }
  for v in 2 3; do
    C=$(psql_count "select count(*) from ml.flyway_ml_history where version='$v' and success")
    test "$C" -ge 1 || { echo "Flyway ml V$v ausente"; exit 1; }
  done
}

assert_flyway_threat_ok() {
  C=$(psql_count "select count(*) from threat.flyway_threat_history where success")
  test "$C" -ge 1 || { echo "Flyway threat sem migracoes aplicadas"; exit 1; }
}

assert_flyway_investigation_ok() {
  C=$(psql_count "select count(*) from investigation.flyway_investigation_history where success")
  test "$C" -ge 1 || { echo "Flyway investigation sem migracoes aplicadas"; exit 1; }
}

flyway_core_max() {
  psql_count "select coalesce(max(version::int),0) from flyway_schema_history where success"
}

flyway_ml_max() {
  psql_count "select coalesce(max(version::int),0) from ml.flyway_ml_history where success"
}