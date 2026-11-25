# Reinício controlado IT 2026-11-24 17:06

transactions antes=27 depois=27 (persistência OK no PostgreSQL 5433)

| Porta | UP |
|---|---|
| 18081 | True |
| 18082 | True |
| 18083 | True |
| 18084 | True |
| 18080 | True |

Nota: na 2ª execução corrigiu-se `DATABASE_URL` com `${db}?currentSchema=...` (PowerShell interpretava `$db?` como operador).