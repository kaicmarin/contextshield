# Smoke IT 2026-11-26 01:30

| ID | Teste | OK | Detalhe |
|---|---|---|---|
| V11 | Flyway V11 + token_version | True | success=t col=1 |
| 0 | Core reiniciada limites padrao | True | LOGIN 8/IP 25; CHECK 40 |
| 1.18080 | Health :18080 | True | UP |
| 1.18081 | Health :18081 | True | UP |
| 1.18082 | Health :18082 | True | UP |
| 1.18083 | Health :18083 | True | UP |
| 1.18084 | Health :18084 | True | UP |
| 2.1 | Login analista | True | HTTP 200 |
| 2.2 | Token invalido apos desativar | True | HTTP 401 |
| 3.1 | Analista PUT engine | True | HTTP 403 |
| 3.2 | Admin PUT engine | True | HTTP 200 |
| 3.3 | Analista demo reset | True | HTTP 403 |
| 4.1 | Login rate limit 429 | True | email rate.limit.+2f2a4141@correio.example tentativas 9 |
| 4.2 | Confira rate limit 429 | True | requisicoes 41 |
| 5 | Compra NEXA criada | True | HTTP 201 id=TX-2026-000189 decisao=APPROVED_WITH_ALERT |
| 6 | ML sombra registrou | True | linhas ml_shadow=2 |
| 7 | Threat indicador confirmado | True | ind=IND-11490d20 status=CONFIRMED HTTP 200 |
| 8.1 | Investigation caso aberto | True | HTTP 200 id=CASE-01dcc554 |
| 8.2 | Fluxo assign/evidence/transition | True | assign=200 evd=200 trans=200 |
| 10 | Org 2 sem compras nexa-demo | True | total=0 |
| 9 | Dados apos restart Core | True | core_UP=True tx_antes=22 tx_depois=22 smoke_tx=1 |
| G | ops entrypoints com ?v= | True | ops.js/css versionados |
| G2 | Cache policy assets (no-cache sem ?v= / immutable com ?v=) | True | map=True home.js=no-cache, must-revalidate ops.js?v=public, immutable |
| INTEL | Migracao canonica na copia (manual) | True | contextshield_intel_copy: 0 orfaos; script corrigido (FK) |
