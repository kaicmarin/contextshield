# Motor de regras dinâmicas — evidências (IT / testes automatizados)

Data: 2026-11-25  
Escopo: segunda grande atualização do ContextShield V2 (regras dinâmicas + R13), sem commit/push/deploy em produção.

## 1. Motor não depende mais de R1–R12 hardcoded na decisão

- Migração **V12** cria `risk_engine_rule` e publica R1–R12 com `kind` interpretável (`SCORE_GTE_WEIGHT`, `CONTEXT_PROBE`, etc.).
- `EngineInputBuilder` injeta `config.rules` a cada avaliação a partir do banco (`RiskRuleService.activeRulePayload`).
- `Policy.decide` usa `DynamicPolicy` quando `rules` não está vazio; o encadeamento fixo ficou apenas em `Policy.legacyDecide` para testes sem payload.
- Teste: `DynamicPolicyEquivalenceTest` compara `legacyDecide` vs dinâmico com `BuiltinRuleCatalog`.

## 2. R13 criada e aplicada sem alterar código Java

- API Operations: `POST /api/ops/rules`, fluxo submit → admin approve → publish.
- UI: **Operations → Regras do motor** (`/ops/regras`), botão "Nova regra (ex. R13)".
- Publicação atualiza apenas o PostgreSQL; **não reinicia** `core-api` nem `risk-engine`.
- Teste: `DynamicRuleTest.r13LifecycleWithoutRestart` (chave R199 em H2) e script `scripts/integrated/run-dynamic-rules-it.ps1` (R13 em IT).

## 3. Senhas individuais

- Nenhuma alteração nos fluxos de `POST /api/me/password`, credenciais por arquivo ou política de senha.
- Suite anterior: `scripts/integrated/run-password-suite.ps1` (reexecutada na suite integrada V2).

## 4. Machine Learning

- `RiskAssessor` inalterado após a decisão de política: **Safety Monitor** e sombra ML continuam no mesmo ponto.
- Testes `PolicyAndShadowTest` e módulo `machine-learning` passam no `mvn test` da build.

## 5. Deploy automatizado (ambiente isolado)

- `scripts/aws/deploy-app-update.sh` revisado: **backup `pg_dump`**, build de todos os serviços, `up -d` preservando volume `pgdata`, espera health do `core-api`, leitura de `flyway_schema_history`, health 8081–8084.
- PostgreSQL de produção: contêiner `postgres` no compose AWS (não RDS).
- Validação local: `mvn test` (compilação + integração H2/Flyway V12); em EC2, um único comando: `./scripts/aws/deploy-app-update.sh`.

## 6. Preservação de dados

- V12 só **adiciona** tabelas e linhas de regras; não trunca `transactions`, `app_user`, `risk_policy`, etc.
- Deploy: backup gzip em `backups/` antes do build; mensagem explícita de que `pgdata` não é recriado.
- `SEED_ENABLED=false` em produção: usuários existentes intactos.

## Comandos executados nesta rodada

```text
mvn test -pl services/risk-engine,services/core-api -am   → OK (exit 0)
```

IT completo (com stack Docker local 18080):

```powershell
.\scripts\integrated\run-v2-integrated.ps1
```

## Arquivos principais

| Área | Arquivos |
|------|----------|
| DB | `V12__dynamic_risk_rules.sql` |
| Core | `RiskRuleService`, `RuleController`, `GovernanceController` (+ `/rules/*`), `EngineInputBuilder` |
| Motor | `DynamicPolicy`, `DynamicRule`, `BuiltinRuleCatalog`, `Policy.decide` |
| UI | `ops-lab.js` `rulesPage`, rota `/ops/regras` |
| Deploy | `scripts/aws/deploy-app-update.sh` |