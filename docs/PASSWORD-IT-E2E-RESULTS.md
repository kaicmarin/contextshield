# Validação IT — senhas individuais (2026-11-25)

Ambiente: PostgreSQL `5433` / serviços `18080–18084` / frontend `5173`. **Sem alterações na AWS.**

## Maven (repositório completo)

| Suite | Resultado |
|---|---|
| `mvn test` | **OK** (exit 0), inclui `PasswordSecurityTest` |

## API dedicada (`scripts/integrated/password-it.ps1`)

| # | Requisito | Resultado |
|---|---|---|
| 1 | Logins distintos por papel | OK |
| 2 | Troca de senha API | OK |
| 3 | Invalidação de JWT | OK |
| 4 | Reset admin | OK |
| 5 | Permissões por perfil | OK |
| 6 | Credenciais fora das respostas | OK |
| 7 | Contas existentes preservadas | OK (`active=17`) |

Relatório: `docs/PASSWORD-IT-RESULTS.md`

**Nota IT:** na primeira execução, o PG IT ainda tinha hash legado `contexto`; o script migrou as 11 contas de demo para senhas do arquivo de teste (somente laboratório).

## Smoke IT (`smoke-full.ps1`)

**19/19 OK** após correção do teste de isolamento de org (reuso do token do analista, evitando 429).

Relatório: `docs/SMOKE-IT-RESULTS.md`

## UI IT (`frontend/scripts/ui-it.mjs`)

**6/6 áreas OK** (NEXA, Aureon, Operations).

## Alterar senha nas UIs (`frontend/scripts/password-ui-it.mjs`)

| App | Rota | Resultado |
|---|---|---|
| NEXA | `/loja/conta/senha` | OK |
| Aureon | `/banco/senha` | OK |
| Operations | `/ops/conta/senha` | OK |

## E2E história (`npm run test:e2e`)

**7/7 passos OK** após `POST /api/ops/demo/reset` (admin) com `SEED_ALLOW_DEMO_RESET=true` e ~50s para o histórico. Orquestração: `scripts/integrated/run-password-suite.ps1`.

## Correções aplicadas durante a validação

- `Get-DemoPassword.ps1` — dot-source seguro (função).
- `Resolve-ItLogin.ps1` — corpo JSON via arquivo; fallback `contexto` → senhas do IT.
- `Restart-CoreItRelaxed.ps1` — limites altos para suites de senha.
- `password-it.ps1`, `password-ui-it.mjs` — novos.
- `smoke-full.ps1` — teste 10 sem relogin após rate limit.

## Comandos para repetir localmente

```powershell
mvn test
.\scripts\integrated\password-it.ps1
.\scripts\integrated\smoke-full.ps1
$env:API_URL="http://127.0.0.1:18080"; $env:BASE="http://127.0.0.1:5173"
node frontend/scripts/ui-it.mjs
node frontend/scripts/password-ui-it.mjs
# antes do E2E: reset demo (admin) ou Painel → Demonstração
npm run test:e2e --prefix frontend
```