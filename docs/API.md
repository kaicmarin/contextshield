# API

A documentação interativa (OpenAPI 3 / Swagger) é gerada a partir do código e fica em `/swagger-ui.html` com o sistema no ar. Esta página é o resumo.

**Autenticação:** `POST /api/auth/login` devolve um JWT. Envie `Authorization: Bearer <token>` nas demais chamadas.
**Erros:** sempre no formato `{ "timestamp", "status", "error", "path", "fields" }`. `fields` lista os campos inválidos (400).

## core-api (porta 8080)

### Público

| Método | Rota | O que faz |
|---|---|---|
| POST | `/api/auth/login` | Login. Devolve `token`, `expiresAt` e o usuário |
| POST | `/api/auth/register` | Cadastro de cliente novo (cria cliente, casa, aparelho e cartão simulados) |
| GET | `/api/catalog/categories` · `/products` · `/products/{id}` · `/sellers/{id}` · `/pickup-points` | Catálogo da loja |
| GET | `/api/catalog/scenarios` · `/options` | Cenários da demonstração e opções de formulário |
| POST | `/api/check` | “Confira antes de pagar”: analisa uma mensagem (1–4000 caracteres). Público, com teto por IP. Além de `level` / `types` / `cues`, devolve `score`, `signals`, `unverified` e `verdict`. Sem busca do link. Detalhe dos pesos em `docs/CHECK.md` |
| GET | `/api/engine/model` | Catálogo de sinais, regras e fontes do motor (transparência) |

### Cliente (`CUSTOMER`)

| Método | Rota | O que faz |
|---|---|---|
| GET | `/api/me` | Perfil, cartões, aparelhos e endereços |
| POST | `/api/me/addresses` | Cadastra endereço |
| POST | `/api/transactions` | **Checkout.** 201 com a decisão; 202 se o motor estiver fora (compra fica em análise) |
| GET | `/api/transactions` · `/{id}` | Compras do cliente (com motivos e perguntas, quando houver) |
| POST | `/api/transactions/{id}/verification` | Respostas da verificação → nova avaliação |
| POST | `/api/transactions/{id}/resolution` | Em intervenção: `cancel` ou `proceed` |
| POST | `/api/transactions/{id}/retry` | Reavalia uma compra que ficou em análise |
| POST · GET | `/api/incidents` | “Fui vítima”: cria e lista relatos |
| GET · PUT · DELETE | `/api/incidents/{id}` | Lê, edita e apaga (editar e apagar só enquanto `OPEN`) |
| GET | `/api/alerts` · `/unread-count` | Avisos do app |
| PATCH | `/api/alerts/{id}/read` | Marca como lido |
| DELETE | `/api/alerts/{id}` | Apaga aviso |

### Analista (`ANALYST` ou `ADMIN`)

| Método | Rota | O que faz |
|---|---|---|
| GET | `/api/ops/overview?days=&band=` | Indicadores do intervalo. `days` é o número de datas civis em `America/Sao_Paulo`, da meia-noite do primeiro dia até agora; `0` ou ausente é o histórico inteiro. `band` (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`) filtra a última avaliação da compra. Relatos não têm banda e entram só pela data. A resposta traz `window` com `from`, `to` e `zone` |
| GET | `/api/ops/transactions?status=&q=&days=&band=&page=&size=&sort=&dir=` | Fila paginada no banco. `status` aceita um estado ou os grupos `ATENCAO` e `AGUARDANDO`. `size` de 1 a 100. A resposta traz `items`, `total`, `pages` e `counts` |
| GET | `/api/ops/transactions/{id}` | Detalhe completo: todas as avaliações, sinais, certificado, respostas, linha do tempo |
| POST | `/api/ops/transactions/{id}/transition` | Decisão manual com justificativa (respeita a máquina de estados) |
| GET | `/api/ops/incidents` · `/{id}` | Relatos e entidades extraídas |
| POST | `/api/ops/incidents/{id}/assign` · `/close` | Assumir e encerrar (`scam` ou `not-scam`) |
| GET | `/api/ops/incidents/{id}` | Inclui `replies` e `attendance` (denunciante, quem assumiu, quem encerrou) |
| POST | `/api/ops/incidents/{id}/replies` | Resposta do analista autenticado. Corpo: `{ "body" }`. Relato aberto passa a `IN_REVIEW`. Encerrado responde 409. Auditoria: `INCIDENT_REPLIED`, separada de `INCIDENT_ASSIGNED` e `INCIDENT_CLOSED` |
| GET | `/api/ops/intelligence` · `/{id}` | Entidades de inteligência |
| PATCH | `/api/ops/intelligence/{id}` | Revisão do analista: `{ "status", "confidence" }`. `status` é `UNVERIFIED`, `INVESTIGATING`, `CONFIRMED` ou `DISCARDED`. Auditoria `INTEL_REVIEWED` |
| GET · PUT | `/api/ops/engine/config` | Liga e desliga comportamentos e fontes já previstos pelo motor. Os pesos ficam na política, não neste endpoint. Cada alteração válida é auditada como `ENGINE_SETTING` |
| GET | `/api/ops/policies` · `/catalog` · `/{id}` · `/compare?left=&right=` | Políticas de risco. O catálogo traz sinal, peso efetivo, faixa e piso de segurança. A compra usa a versão `PUBLISHED` do perfil `fintech` |
| POST · PUT | `/api/ops/policies` · `/{id}` | Cria e edita rascunho (`DRAFT` ou `REJECTED`). Peso fora da faixa é recusado |
| POST | `/api/ops/policies/{id}/submit` | Envia o rascunho para aprovação (`PENDING`) |
| POST | `/api/ops/policies/{id}/simulate` | Corpo `{ "txId" }`. Recalcula com os pesos da versão e não grava a compra |
| GET | `/api/ops/ml` | Laboratório: modelos, experimentos, sugestões e contagem da sombra. As métricas vêm do relatório gravado |
| GET | `/api/ops/ml/experiments/{id}` | Status de um experimento |
| POST | `/api/ops/ml/experiments` | Corpo `{ "examples", "seed" }`. `examples` entre 200 e 4000. A resposta volta na hora; o treino segue no servidor. Um experimento por vez (409 se já houver um) |
| GET | `/api/ops/audit?page=&size=&action=&q=` | Página da auditoria, mais recentes primeiro (`id` decrescente). `size` de 1 a 100 |
| GET | `/api/ops/audit/export?action=&q=` | CSV de todos os eventos filtrados, até 5000. O cabeçalho `X-Export-Truncated` avisa se o recorte foi cortado |
| GET | `/api/ops/customers` · `/{id}/devices` | Clientes e aparelhos |
| POST | `/api/ops/alerts` | Envia aviso ao app de um cliente |
| POST | `/api/ops/demo/scenarios/{id}/run` | Roda um cenário pelo caminho real do checkout |
| POST | `/api/ops/demo/reset` | Reinicia os dados da demonstração |

### Administração (`ADMIN`)

| Método | Rota | O que faz |
|---|---|---|
| GET · POST | `/api/admin/users` | Lista e cria usuários |
| GET · PUT · DELETE | `/api/admin/users/{id}` | Lê, edita (nome, papel, ativo, senha) e exclui |
| POST | `/api/admin/policies/{id}/approve` · `/reject` · `/publish` · `/rollback` | Aprova, rejeita, publica ou restaura política. Analista recebe 403 |
| POST | `/api/admin/ml/models/{id}/approve` · `/reject` | Aprova só modelo com validação `PASS`. Rejeita um modelo que não esteja aprovado |
| POST | `/api/admin/ml/suggestions/{id}/draft` | Copia a sugestão de pesos para um rascunho fintech. Não publica |
| POST | `/api/admin/outcomes` | Corpo `{ "txId", "label" }`. `label` é `FRAUD`, `LEGIT` ou `INCONCLUSIVE`. Entra em treinos futuros, não treina na hora |
| POST | `/api/admin/ml/models/{id}/decision` | Recusa o uso do modelo na decisão. A resposta traz `decisionAuthorized: false` |

### Integração (`ANALYST` ou `ADMIN`, organização do token)

A organização não é um campo do corpo. Ela sai do usuário autenticado. Outra empresa recebe 404.

| Método | Rota | O que faz |
|---|---|---|
| GET | `/api/v1/partner/status` | Organização do token e o dono da decisão (`risk-engine`) |
| POST | `/api/v1/partner/assessments` | Avalia um contexto. Exige `Idempotency-Key`. A decisão é a do motor. O modelo, se estiver aprovado, volta em `machineLearning` com `affectsDecision: false`. Limite de 30 chamadas por minuto por organização |
| GET | `/api/v1/partner/assessments/{id}` | Lê uma avaliação da própria organização |

## risk-engine (porta 8081, interno)

| Método | Rota | Acesso | O que faz |
|---|---|---|---|
| POST | `/api/v1/assessments` | JWT de serviço (`SERVICE`) ou `ANALYST` | Avalia o contexto de uma compra |
| GET | `/api/v1/model` | Público | Catálogo de sinais e regras |
| GET | `/actuator/health` | Público | Saúde do serviço |

## Exemplo: checkout com indução

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"ana.martins@correio.example","password":"contexto"}' | jq -r .token)

curl -s -X POST localhost:8080/api/transactions -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"items":[{"productId":"prod-001","quantity":1}],"addressId":"ADDR-PICKUP-880","deviceId":"DEV-ANA-W11","declaredOrigin":"link"}'
```

Resposta (resumida):

```json
{
  "id": "TX-2026-000184",
  "status": "CONTEXT_REQUIRED",
  "amount": 4799.90,
  "reasons": [
    { "label": "O valor está bem acima do que você costuma gastar", "detail": "R$ 4.799,90 · 9,8× o ticket médio de R$ 490,00", "points": 14 },
    { "label": "A compra está sendo feita em um dispositivo que você nunca usou", "points": 8 }
  ],
  "verification": {
    "questions": [
      { "key": "origin", "prompt": "Você chegou nesta compra por um link que alguém enviou?",
        "options": [{ "id": "asked", "label": "Sim, alguém me enviou" }, { "id": "self", "label": "Não, eu encontrei sozinho" }] }
    ]
  }
}
```
