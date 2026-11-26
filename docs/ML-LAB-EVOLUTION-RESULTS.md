# Evolução do laboratório ML — relatório (local / sem deploy)

Data: 2026-11-25  
Escopo: análises fundamentadas de pesos e regras dinâmicas, preservando motor dinâmico, senhas individuais e deploy único AWS.

## 1. O que melhorou no Machine Learning

- Rótulos: painel `/api/ops/ml/labels` e bloco na UI separam **sintético**, **revisado** e **relato pendente**.
- Monitor e overview mantêm alerta: métricas de arquivo/experimento são sintéticas salvo indicação contrária.
- Treino continua com os quatro modelos (RF/Logística operacional e contextual); sombra inalterada.
- `WeightSearch` passa a avaliar candidatos com **regras dinâmicas** (`dynamicRulesApplied`), não só pesos.
- `Experiment` usa `BuiltinRuleCatalog.publishedDefaults()` na otimização embutida — alinhado ao motor versionado, sem `legacyDecide` na compra real.

## 2. Como o sistema estuda e sugere pesos

- `POST /api/ops/ml/studies/weights` enfileira estudo (`ml_study`, kind `WEIGHTS`).
- Lê pesos **publicados** (`RiskPolicyService`) e regras **publicadas** (`RiskRuleService.activeRulePayload`).
- Compara baseline publicado vs busca (`WeightSearch`) em split **search / confirm** sintético.
- Com ≥12 rótulos revisados, mede também em transações confirmadas com holdout por `hash(txId) % 5`.
- Perfis de custo configuráveis: `standard` (5×FN+1×FP), `balanced`, `recall`.
- Melhorias viram `weight_suggestion` (status `SUGGESTED`); **nunca** publicação automática.
- Admin: `POST /api/admin/ml/suggestions/{id}/draft` → rascunho fintech (`RiskPolicyService`).

## 3. Como o sistema avalia regras dinâmicas

- `POST /api/ops/ml/studies/rules` gera relatório por regra publicada:
  - Acertos em `assessment.rule_id`
  - Simulação **sem** cada regra (lista dinâmica filtrada, sem Java novo)
  - Custo FP/FN em rótulos revisados (`PolicyEvaluation`)
- Identifica sinais frequentes em FP/FN.
- Com dados insuficientes (<8 revisados), encerra com mensagem explícita — sem sugestão inventada.

## 4. Sugestões de novas regras

- Armazenadas em `ml_rule_suggestion` (tipo `SCORE_SIGNAL`, etc.).
- Geradas a partir de evidências (contagens de sinais em erros).
- Admin: `POST /api/admin/ml/rule-suggestions/{id}/draft` → **rascunho** em `risk_engine_rule` via `RiskRuleService.create` — fluxo normal de aprovação/publicação Operations.

## 5. Dados e métricas que fundamentam sugestões

| Fonte | Uso |
|-------|-----|
| `known_outcome` REVIEWED + ANALYST/VERIFIED | FP/FN de política, estudo de regras |
| `known_outcome` PENDING + REPORT | Contagem apenas; **não treina** |
| Sintético (`SyntheticCases`) | Busca de pesos / treino modelo |
| `assessment` histórico | Frequência de disparo por regra |
| `evidence_json` em sugestão de regra | Sinal, origem FP/FN, contagem |

## 6. O que ainda depende de critérios de desenvolvimento

- Limiares mínimos de amostra (12 pesos / 8 regras).
- Perfis de custo pré-definidos (extensível via API `costProfile`).
- Heurísticas de proposta de regra (sinais `DESTINATION_IN_INCIDENT`, `NEW_DEVICE`).
- Meta de aceite dos modelos (`Acceptance`) e Safety Monitor — inalterados.
- Geração sintética de casos (`SyntheticCases`, `ContextCases`).

## 7. Testes automatizados

```text
mvn test -pl services/core-api,services/risk-engine,machine-learning -am
```

Novos: `LabStudyTest`, `PolicyEvaluationLeakTest`; existentes: `DynamicRuleTest`, `DynamicPolicyEquivalenceTest`, `PolicyAndShadowTest`.

## 8. Nenhuma regra hardcoded na decisão

- `DynamicPolicy` + regras PostgreSQL intactos.
- `Policy.legacyDecide` só quando `config.rules` vazio (testes isolados).
- `BuiltinRuleCatalog` é seed/equivalência, não substitui publicação Operations.

## 9. Pesos e regras continuam dinâmicos

- Publicação só via Governance + Operations; estudos gravam sugestões/rascunhos.
- Reinício de serviço **não** necessário após publicar.

## 10. Deploy único AWS

- Migração **V13** adiciona tabelas; compatível com `scripts/aws/deploy-app-update.sh` (Flyway na subida do core, backup pgdata).
- Pacote único: senhas individuais + regras dinâmicas + laboratório evoluído.

## Comandos IT sugeridos

```powershell
mvn test
.\scripts\integrated\run-v2-integrated.ps1
```