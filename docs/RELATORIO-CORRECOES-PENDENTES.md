# Relatório — correções de bugs pendentes (revisão 15/11/2026)

Checkpoint Git: **`checkpoint-pre-correcoes-pendentes-2026-11-24`**.

## Resultado dos testes

| Suíte | Aprovados | Falhos | Erros | Ignorados |
|-------|-----------|--------|-------|-----------|
| Maven (todos os módulos) | **143** | **0** | **0** | **0** |
| `frontend/scripts/unit.mjs` | **ok** | — | — | — |
| E2E cinco microsserviços | não executado | — | — | ambiente sem stack ativa |
| Docker Compose | não executado | — | — | Docker indisponível no host |

## Tabela de auditoria

| Bug investigado | Existia no código atual? | Arquivo afetado | Correção aplicada | Teste realizado | Resultado | Pendência |
|-----------------|--------------------------|-----------------|---------------|-----------------|-----------|-----------|
| **A. Autorização ADMIN em `/api/ops/demo/reset` e `PUT /api/ops/engine/config`** | Sim — ANALYST podia alterar motor e reset | `OpsController.java`, `OpsQueryTest.java` | `@PreAuthorize("hasRole('ADMIN')")`; reset só com `SEED_ALLOW_DEMO_RESET` ou seed ligado; auditoria não é apagada no reset | `OpsAdminAuthTest`, `OpsQueryTest` | Corrigido e testado | — |
| **B. JWT válido após desativação/revogação** | Sim — só validava assinatura | `JwtAuthFilter.java`, `JwtService.java`, `AppUser.java`, `AdminController.java`, `V11__token_version.sql` | Validação no DB (ativo, papel, `token_version`); bump em troca de senha/papel/desativação | `AuthRevocationTest`, `AuthApiTest` | Corrigido e testado | Tokens de serviço (role SERVICE) continuam só por assinatura/TTL nos microsserviços internos |
| **C. X-Forwarded-For no rate limit do Confira** | Sim — aceitava header sempre | `CheckRateLimitFilter.java`, `ClientIp.java` | IP de `X-Forwarded-For` só com peer confiável | `ClientIpTest` | Corrigido e testado | Configurar `contextshield.http.trusted-proxies` no deploy |
| **D. Colisão `phoneEntityId` / `domainEntityId`** | Sim — truncamento | `IntelText.java` | IDs canônicos (dígitos completos / host normalizado) | `IntelTextTest` | Corrigido e testado | Migrar dados existentes: `scripts/db/migrate-intel-canonical-ids.sql` (manual PG, não Flyway H2) |
| **E. 500 por nulos (`EngineInputBuilder`, etc.)** | Parcial — cidade nula em `Map.of` | `EngineInputBuilder.java`, `ApiExceptionHandler.java` | `customer` com mapa null-safe; HTTP 415 para media type | Testes de checkout/regressão Maven | Corrigido e testado (regressão indireta) | — |
| **F. Proteção do login** | Não | `LoginRateLimitFilter.java`, `LoginAttemptLimiter.java`, `AuthController.java` | Limite por IP e por e-mail (429; conta não bloqueada) | `AuthApiTest`, limites altos em `application-test.yml` | Corrigido e testado | Ajustar limites em produção via env |
| **G. Cache do frontend** | Sim — HTML sem `no-cache` | `frontend/nginx.conf` | `Cache-Control: no-cache` em HTML e rotas SPA; assets versionados mantêm `immutable` | Inspeção estática | Corrigido, não testado em navegador real | — |
| **H1. Lookalike falso no `CheckUrl`** | Não reproduzido nos casos atuais | `CheckUrl.java` | Sem alteração de score (evita regressão de risco) | `CheckUrlTest` existente | Já estava adequado nos casos de teste | Monitorar relatos na demo |
| **H2. Validação de tamanho de campos** | Parcial — já em DTOs Jakarta | vários controllers | Sem mudança ampla | testes API existentes | Já coberto | — |
| **H3. `localStorage` indisponível** | Já tratado | `ops.js`, `api/client.js`, `bank.js` | try/catch existente | `unit.mjs` | Já estava corrigido | — |
| **H4. Limite cartão Aureon** | Não investigado como bug ativo | `bank.js` | Sem alteração de regra de negócio | — | Sem mudança | Confirmar manualmente se necessário |
| **H5. IDs ano fixo / `count()+1`** | Comportamento conhecido de demo | `AuthController`, etc. | Fora do escopo (sem quebra de dados) | — | Pendente opcional | Refatorar só se prod exigir |
| **H6. Swagger em produção** | Exposto por padrão | `application-prod.yml` | `springdoc.*.enabled: false` no perfil prod | Config estática | Corrigido, não testado em prod | — |
| **H7. Segurança cabeçalhos HTTP** | Parcial | `nginx.conf` | `nosniff`, `referrer-policy` já presentes | Estático | Mantido | CSP completo opcional |
| **H8. Vazamento entre clientes** | Parcialmente corrigido antes | `OrganizationService`, etc. | Mantido escopo desta entrega em ops sensíveis + JWT | `OrganizationServiceTest`, `PolicyAndShadowTest` | Parcial | Ver `docs/SEGURANCA-E-ISOLAMENTO.md` |
| **H9. Confira mistura oficial/suspeito** | Revisado | `CheckService.java` | Sem mudança de classificação nesta entrega | `CheckApiTest` / eval JSON | Já estava corrigido | — |

## Verificação dos cinco microsserviços

- **Core API**: login, JWT revogável, ops ADMIN, Confira com IP confiável — validado por testes Maven.
- **Risk Engine**: sem mudanças de decisão; testes do módulo passando.
- **ML / Threat / Investigation**: sem alteração de contrato; testes unitários passando.
- **Integração ponta a ponta**: não executada (serviços não estavam em execução neste host).

## Classificação geral

| Status | Itens |
|--------|--------|
| **Corrigido e testado agora** | A, B, C, D (lógica + testes), E (parcial), F, demo reset sem apagar auditoria, G (config), Swagger prod |
| **Corrigido, não testado em runtime** | Migração SQL intel em PG vivo, cache HTML no navegador, perfil prod springdoc |
| **Já estava corrigido / adequado** | Lookalike nos testes atuais, localStorage, validação básica em DTOs |
| **Pendente / opcional** | E2E cinco JARs, migrar intel no 5432 quando autorizado, endurecer isolamento de auditoria por org (H8), IDs sequenciais de demo |

## Evidências

- Tag: `checkpoint-pre-correcoes-pendentes-2026-11-24`
- Comando: `mvn test` → **143 testes, 0 falhas**
- Comando: `node frontend/scripts/unit.mjs` → **unit: ok**