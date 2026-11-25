# Auditoria de testes — ContextShield V2

**Data:** 2026-11-25  
**Repositório:** `contextshield-v2`  
**Produção (somente leitura):** https://3-22-188-147.sslip.io  
**Ambiente local integrado (IT 5433):** encerrado nesta máquina — ver `docs/SMOKE-IT-RESULTS.md` (2026-11-24).

## Resumo executivo

| Critério | Resultado |
|----------|-----------|
| Suíte Maven (`mvn test`) | **153 testes, 0 falhas** |
| Frontend unit (`npm run test:unit`) | **OK** |
| Smoke IT integrado (histórico) | **OK** (microsserviços, compra, ML sombra, threat, investigation) |
| E2E Playwright completo (`npm run test:e2e`) | **Não reexecutado** — API/seed local indisponível |
| Produção E2E autenticado (NEXA/Banco/Ops) | **Bloqueado** — login demo retorna **401** (`seed.enabled=false`) |
| Produção rotas profundas SPA (`/loja/ajuda`, etc.) | **Falha crítica** — Caddy servia `index.html` da home; **correção no código** (`deploy/caddy/Caddyfile`) |

**Prontidão para demonstração pública completa:** **não aprovada** até (1) redeploy do proxy Caddy com o fallback SPA corrigido, (2) credenciais demo ou seed controlado em produção, (3) reexecução de `test:smoke` / `test:e2e` contra o host atualizado.

---

## 1. Quantidade de testes executados (esta auditoria)

| Camada | Comando / artefato | Executados | Aprovados | Reprovados |
|--------|-------------------|------------|-----------|------------|
| Backend + ML lib | `mvn test` (raiz) | 153 | 153 | 0 |
| Frontend unit | `npm run test:unit` | 4 casos (módulo) | 4 | 0 |
| Playwright produção read-only | `BASE=… node frontend/scripts/prod-readonly.mjs` | 6 rotas + 2 APIs | 6 + 2 | 0* |
| Playwright smoke (prod) | `BASE=… npm run test:smoke` | 7 rotas | 6 | 1 (`/loja/ajuda`) |
| Smoke IT (sessão anterior) | `scripts/integrated/smoke-full.ps1` | 20+ checks | todos | 0 |
| E2E história Ana/Mariana | `npm run test:e2e` | 0 (nesta sessão) | — | — |
| A11y (`npm run test:a11y`) | — | 0 | — | — |
| Perf (`perf/load.mjs`) | — | 0 | — | — |

\* `prod-readonly` ainda **não** inclui `/loja/ajuda` na execução contra produção **antes** do redeploy; após correção do Caddy, incluir essa rota no script (já adicionada no repositório).

**Total automatizado nesta sessão:** **153 + 4 + 8 checks Playwright/API ≈ 165** (sem contar IT histórico).

---

## 2. Backend e microsserviços

### 2.1 Suíte Maven (local, H2/PostgreSQL em teste)

Distribuição por módulo (Surefire):

| Módulo | Testes |
|--------|--------|
| `core-api` | 81 |
| `risk-engine` | 55 |
| `machine-learning` (lib) | 10 |
| `ml-service` | 3 |
| `threat-service` | 2 |
| `investigation-service` | 2 |
| **Total** | **153** |

Cobertura relevante ao escopo da auditoria: autenticação JWT, checkout e motor de risco, sombra ML sem alterar decisão, isolamento por organização, threat/indicadores, casos de investigação, persistência e restrições FK/unique, rate limits, Confira/check.

### 2.2 Produção (somente leitura)

| Endpoint | Método | HTTP | Observação |
|----------|--------|------|------------|
| `/api/catalog/products` | GET | 200 | 28 produtos |
| `/api/check` | POST JSON | 200 | Classificação (ex.: 0800 → GREEN via navegador; texto curto → GRAY) |
| `/api/check` | GET | 401 | Esperado |
| `/api/engine/config` | GET | 401 | Sem token — OK |
| `/api/auth/login` | POST demo | **401** | Usuários demo não existem com `seed.enabled=false` |
| `/actuator/health` | GET | **404** | Caddy responde "fechado" — health só interno |
| `/swagger-ui.html` | GET | **200** | **Risco:** documentação exposta; `application-prod.yml` desabilita Springdoc — verificar perfil ativo no deploy |

Comunicação entre serviços: validada no **Smoke IT** (compra → risk → ML shadow → threat → investigation). Não revalidada nesta sessão (stack local parada).

Recuperação com microsserviço indisponível: coberto em testes (`motorForaDoArNaoAprovaACompra`, checks com inteligência indisponível). Não repetido em chaos test em produção (regra de não destrutivo).

---

## 3. Loja NEXA

| Fluxo | Local (Maven + E2E) | Produção |
|-------|---------------------|----------|
| Catálogo / API | `CheckoutFlowTest`, IT smoke | **OK** (200, 28 itens) |
| Login / cadastro | `AuthApiTest` | **Não testável** (401) |
| Carrinho / checkout / cenários | `CheckoutFlowTest`, `test:e2e` | **Não testável** sem usuário + SPA deep link quebrado |
| Ajuda `/loja/ajuda` | `server.mjs` + smoke local | **Falha** — HTML da **home**, não da NEXA |

---

## 4. Banco Aureon e Operations

- **Local:** fluxos cobertos em `CheckoutFlowTest`, `IncidentAlertAdminApiTest`, `OpsQueryTest`, `PolicyAndShadowTest`, E2E (`relato`, painel R1).
- **Produção:** páginas raiz `/banco`, `/ops` renderizam; rotas profundas (`/banco/relatar`, `/ops/transacoes`) sofrem o **mesmo bug de fallback** do Caddy. Login **401**.

---

## 5. Machine Learning

- Lib + API: `TrainingTest`, `ContextStudyTest`, `FalseNegativeDiagnosisTest`, `PredictionApiTest`, `PolicyAndShadowTest` (sombra não altera aprovação).
- Produção: sem treino/reset (regra de não destrutivo). IT smoke confirmou registro em `ml_shadow` (histórico).

---

## 6. Frontend e UX

| Teste | Resultado |
|-------|-----------|
| `npm run test:unit` | OK |
| `prod-readonly` (prod) | OK nas raízes + catálogo + check |
| `test:smoke` (prod) | Falha em `/loja/ajuda` (causa raiz: proxy) |
| Deep links (curl prod) | `/loja/produto/x`, `/banco/relatar`, `/ops/transacoes` → **home** |
| Dev `server.mjs` | `/loja/ajuda` → **NEXA** (referência correta) |

Correções aplicadas no repositório:

1. **`deploy/caddy/Caddyfile`** — fallbacks alinhados ao `frontend/nginx.conf` (`/loja`, `/banco`, `/ops`, `/sistema`); estáticos `/assets/*`, `/products/*`, `/previews/*` e `/mark.svg` servidos antes dos SPAs; `{path}.html` preservado no catch-all.
2. **`frontend/scripts/smoke.mjs`** — timeouts alinhados ao `prod-readonly`.
3. **`frontend/scripts/prod-readonly.mjs`** — rota `/loja/ajuda` para regressão pós-deploy.
4. **`frontend/package.json`** — script `test:prod-readonly`.

---

## 7. Banco de dados

- Flyway V11 e migração canônica: OK no Smoke IT.
- Restrições: `PersistenceTest` (FK, unique email, check constraints).
- Isolamento de schemas por microsserviço: validado em IT (`OrganizationServiceTest`, `PolicyAndShadowTest`).
- **Produção:** nenhuma mutação ou inspeção direta do RDS/Postgres AWS (regra 6–7).

---

## 8. Segurança

| Item | Status |
|------|--------|
| API protegida sem JWT | 401 em rotas sensíveis |
| Actuator externo | 404 (Caddy) |
| Seed / demo reset em prod | Desligado (`application-prod.yml`) — correto, mas impede testes demo |
| CORS | Navegador usa mesmo host; sem teste cross-origin nesta sessão |
| Swagger em prod | **Possível exposição** (HTTP 200) — confirmar `SPRING_PROFILES_ACTIVE=prod` |
| Credenciais em repo de teste | `it.env` gitignored; exemplos em `it.env.example` |

---

## Erros identificados e causas

| ID | Severidade | Descrição | Causa | Correção |
|----|------------|-----------|-------|----------|
| **P1** | Crítica | Deep links `/loja/*`, `/banco/*`, `/ops/*` servem a landing **ContextShield** | `try_files {path} {path}.html /index.html` no **Caddy** | `deploy/caddy/Caddyfile` atualizado (redeploy pendente) |
| **P2** | Alta | Login demo em produção 401 | `contextshield.seed.enabled=false` + ausência de usuários | Operacional: criar usuários ou seed pontual controlado (fora do escopo de código) |
| **P3** | Média | `swagger-ui.html` acessível em produção | Perfil prod ou proxy | Verificar variáveis de ambiente do `core-api` |
| **P4** | Baixa | Smoke Playwright timeout 8s em rota lenta | Secundário ao P1 (página errada) | Timeouts aumentados em `smoke.mjs` |

---

## Funcionalidades ainda não testadas (nesta auditoria)

- `npm run test:e2e` e `frontend/scripts/ui-it.mjs` (requer stack + seed).
- `npm run test:a11y`.
- `node perf/load.mjs` contra produção (carga).
- Chaos / queda de um microsserviço em runtime.
- Responsividade sistemática (mobile) em todas as telas.
- Fluxos Aureon/Ops autenticados em produção.
- Treino ML e publicação de modelo em produção (propositalmente omitido).

---

## Riscos para produção

1. **Demonstração quebrada por link direto ou favorito** (ajuda, produto, relato, transações no painel) até redeploy do Caddy.
2. **Impossibilidade de validar E2E autenticado** sem política de dados demo em prod.
3. **Documentação API exposta** se Springdoc permanecer ativo.
4. **Monitoramento externo** sem rota de health pública (decisão de segurança aceitável se houver probe interno).

---

## Evidências

### Maven

```
TOTAL maven tests: 153 (failures=0 em todos os módulos Surefire)
```

### Produção — HTML incorreto em deep link (antes do fix)

```bash
curl -s https://3-22-188-147.sslip.io/loja/ajuda   # → home.js / title ContextShield
curl -s https://3-22-188-147.sslip.io/loja/       # → store.js / title NEXA · ContextShield
```

### Produção — `prod-readonly`

```
✓ /
✓ /loja
✓ /banco
✓ /ops
✓ /sistema
✓ /api/catalog/products 28 itens
✓ /api/check GREEN
prod-readonly: ok
```

### Referência histórica integrada

`docs/SMOKE-IT-RESULTS.md`, `docs/UI-IT-RESULTS.md`, `docs/RESTART-FIVE-IT.md`.

---

## Plano de verificação pós-correção (recomendado)

1. Redeploy do serviço `proxy` (Caddy) na AWS com o `Caddyfile` novo.
2. `cd frontend && BASE=https://3-22-188-147.sslip.io npm run test:smoke` — deve passar `/loja/ajuda`.
3. `BASE=… npm run test:prod-readonly` — inclui ajuda.
4. Se autorizado: subir IT local (`scripts/integrated/…`) e `npm run test:e2e`.
5. Confirmar `swagger-ui` → 404 em prod e login apenas com credenciais reais fornecidas de forma segura.

---

## Avaliação final de prontidão

| Dimensão | Nota |
|----------|------|
| Lógica de negócio e integração (testes automatizados locais) | **Alta** — 153 testes verdes + IT documentado |
| Proxy / frontend em produção | **Baixa** — bug P1 ativo até redeploy |
| Testes E2E em produção | **Incompleta** — read-only parcial; sem fluxos autenticados |
| Segurança baseline | **Média-alta** — JWT/seed OK; revisar swagger e health |

**Conclusão:** O núcleo do sistema **compila, passa na suíte Maven e já foi validado end-to-end em ambiente IT isolado**, mas **a instância pública sslip.io não está pronta para comprovar fluxos completos** por combinação de **roteamento SPA incorreto no Caddy** e **ausência de contas demo**. A correção de código para P1 está no repositório; **deploy não foi executado** (regra 10 do escopo).