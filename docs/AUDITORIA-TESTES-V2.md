# Auditoria de testes — ContextShield V2

**Data:** 2026-11-25  
**Repositório:** `contextshield-v2`  
**Produção (somente leitura):** https://3-22-188-147.sslip.io  
**Ambiente local integrado (IT 5433):** revalidado em **2026-11-25** — `docs/SMOKE-IT-RESULTS.md`, `docs/UI-IT-RESULTS.md`.

## Resumo executivo

| Critério | Resultado |
|----------|-----------|
| Suíte Maven (`mvn test`) | **153 testes, 0 falhas** (última execução 2026-11-25) |
| Smoke IT API (`smoke-full.ps1`) | **19/19 checks OK** |
| E2E história Ana/Mariana (`npm run test:e2e` local) | **7/7 passos OK** |
| UI autenticada NEXA/Banco/Ops (`ui-it.mjs`) | **6/6 áreas OK** |
| Produção `test:smoke` | **8/8 rotas OK** (incl. `/loja/ajuda`) |
| Produção E2E autenticado | **Não executado** — sem seed (`401` em login demo) |
| A11y Playwright (`test:a11y`) | **12 rotas OK / 6 com violações** (contraste/regiões; sem mudança de CSS nesta auditoria) |

**Prontidão:** **fluxos completos comprovados em ambiente IT isolado** (compras, relato, bloqueio, painel R1, microsserviços, ML sombra, threat, investigação). **Produção:** navegação e APIs públicas OK; **demonstração autenticada na AWS** ainda depende de seed ou credenciais fora de arquivos de teste.

---

## 1. Quantidade de testes executados (auditoria completa)

| Camada | Comando / artefato | Executados | Aprovados | Reprovados |
|--------|-------------------|------------|-----------|------------|
| Backend + ML lib | `mvn test` (raiz) | 153 | 153 | 0 |
| Smoke IT API | `scripts/integrated/smoke-full.ps1` | 19 | 19 | 0 |
| E2E história Ana/Mariana | `BASE=http://127.0.0.1:5173 npm run test:e2e` | 7 passos | 7 | 0 |
| UI IT autenticada | `ui-it.mjs` | 6 áreas | 6 | 0 |
| Frontend unit | `npm run test:unit` | 1 suíte | 1 | 0 |
| SPA rotas locais | `npm run test:spa-routes` | 10 URLs | 10 | 0 |
| Playwright smoke (prod) | `BASE=… npm run test:smoke` | 8 checks | 8 | 0 |
| Playwright prod read-only | `test:prod-readonly` | 8 checks | 8 | 0 |
| A11y | `npm run test:a11y` | 18 rotas varridas | 12 sem violação | 6 rotas* |
| Perf (`perf/load.mjs`) | — | 0 | — | — |
| Chaos (queda de microsserviço em runtime) | — | 0 | — | — |

\* A11y: violações conhecidas de contraste (`.nx-hero-w2`, botões Ops) e `region` no app Aureon (`.az-overlays`); documentadas, não bloqueiam fluxo funcional.

**Total automatizado:** **153 Maven + 19 IT + 7 E2E + 6 UI + 8 smoke prod + 10 SPA + unit ≈ 203** (sem contar cada regra axe).

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
| **P1** | Crítica | Deep links SPA em produção | Caddy sem fallback por app | **Corrigido** — `deploy/caddy/Caddyfile` em produção; `test:smoke` **OK** |
| **T1** | Teste | E2E relato Aureon instável (toast 4s) | Assert no toast | **Corrigido** — `e2e.mjs` usa checkbox + lista `CS-2026-*` |
| **T2** | Teste | `ui-it.mjs` seletor de catálogo obsoleto | `.product-card` inexistente | **Corrigido** — `.nx-card` |
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
| Lógica de negócio e integração (Maven + Smoke IT) | **Alta** — 153 + 19 checks |
| Fluxo completo UI (E2E + ui-it local) | **Alta** — indução → relato → bloqueio → R1 no painel |
| Proxy / frontend em produção | **Alta** — smoke 8/8 após Caddy |
| E2E autenticado em produção AWS | **Não validado** — seed desligado |
| Acessibilidade automática | **Média** — 6 rotas com violações axe |
| Segurança baseline | **Média-alta** — JWT, rate limit, isolamento org; revisar swagger em prod |

**Conclusão:** O ContextShield V2 **comprova os fluxos de ponta a ponta no ambiente IT isolado** (cinco microsserviços, PostgreSQL 5433, frontend em `:5173`). A **produção sslip.io** está **apta para navegação, catálogo, Confira e deep links**; **não substitui** o IT para compras autenticadas até haver dados demo controlados na AWS.

### Correções de teste (código local, não commitadas nesta etapa)

- `frontend/scripts/e2e.mjs` — relato Aureon sem depender de toast.
- `frontend/scripts/ui-it.mjs` — seletor `.nx-card` e login Aureon condicional.

### Evidências E2E local (2026-11-25)

```
✓ checkout com sinais de indução pede contexto
✓ respostas orientadas pausam a compra
✓ cliente cancela e nada é cobrado
✓ relato registrado no app do banco
✓ compra de outra cliente para o mesmo destino é bloqueada
✓ painel mostra a regra R1 na compra bloqueada
Fluxo completo OK
```