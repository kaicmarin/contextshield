# Testes

Resumo do que a orientação da A3 pede e onde cada item é provado.

| Requisito da A3 | Como testamos | Resultado |
|---|---|---|
| CRUD de usuários | `PersistenceTest.crudDeUsuario`, `IncidentAlertAdminApiTest.crudDeUsuariosPeloAdmin` | ✅ |
| CRUD de transações | `PersistenceTest.crudDeTransacaoComItensEmCascata`, `CheckoutFlowTest` (9 testes) | ✅ |
| CRUD de alertas | `PersistenceTest.crudDeAviso`, `IncidentAlertAdminApiTest.avisosDoClienteListarLerApagar`, CRUD de relatos | ✅ |
| Integridade dos dados | `PersistenceTest`: banco recusa chave estrangeira inexistente, valor negativo e e-mail duplicado; histórico de estados consistente | ✅ |
| Endpoints com requisições válidas e inválidas | `AuthApiTest`, `CheckoutFlowTest.validaEntradaDoCheckout`, `RiskApiTest` (400 com campos, 401, 403, 404, 409) | ✅ |
| Tratamento de erros | Corpo de erro uniforme; motor fora do ar não aprova (`motorForaDoArNaoAprovaACompra`) | ✅ |
| Tempo de resposta em carga normal e pico | `perf/load.mjs` | ✅ p95 57 ms (normal) e 364 ms (pico) |
| Usabilidade das mensagens de alerta | Avaliação heurística + varredura automática de acessibilidade + roteiro de teste com usuários | ✅ automática; ⏳ com usuários (roteiro abaixo) |

## 1. Testes funcionais (backend)

```bash
mvn verify
```

**101 testes, 0 falhas** (execução de 02/11/2026).

**111 testes, 0 falhas** em 24/11/2026, com `mvn -pl services/core-api -am test` no JDK 21. O acréscimo sobre os 109 anteriores é o modo sombra com modelo real e o isolamento entre organizações.

**126 testes, 0 falhas** na execução seguinte do mesmo comando, ainda em 24/11/2026. Entraram a verificação seletiva, o teto de frequência, a negativa inconclusiva, a sombra contextual, a aprovação de modelo contextual sem liberar decisão e a consulta cruzada de visão geral, auditoria, política e modelo. O relatório está em `docs/RELATORIO-PROTOTIPO.md`.

**129 testes, 0 falhas** na execução de 24/11/2026 do mesmo comando. Os três testes novos conferem que a fraude que nega ajuda usa o mesmo vetor da compra habitual, que outra semente repete o empate e que um corte mais baixo no modelo publicado marca compras habituais. Nenhum modelo foi substituído.

**137 testes, 0 falhas** na execução seguinte, com os três serviços novos. Entraram a equivalência do modelo publicado, a recusa de treino na requisição, o indicador que nasce não verificado, o caso que não vira rótulo de treino e a porta da core-api que não abre investigação para o cliente. Docker não estava instalado, então a suíte de contêiner não rodou.

| Classe | Testes | O que cobre |
|---|---|---|
| `risk-engine` · `ParityTest` | 43 | O motor Java decide **igual ao protótipo original** em 43 cenários gravados: sinais, score, banda, confiança, decisão proposta e final, regra e fragilidade |
| `risk-engine` · `WeightPolicyTest` | 5 | Política vazia reproduz o score antigo; peso maior de aparelho novo muda a regra; compra habitual com a flag ligada continua em R8; horário atípico pede R12 sem mudar o score; piso de segurança não deixa enfraquecer o bloqueio |
| `risk-engine` · `RiskApiTest` | 5 | Sem token → 401; token de cliente → 403; token de serviço → 200; entrada inválida → 400 com campos; catálogo público |
| `core-api` · `AuthApiTest` | 8 | Login gera JWT com papel; senha errada, rota sem token e token adulterado → 401; cliente não entra no painel → 403; rotas públicas; cadastro e e-mail repetido → 409; cadastro inválido lista campos |
| `core-api` · `CheckoutFlowTest` | 9 | Compra habitual aprovada; indução pede contexto → pausa → cliente cancela → estado final não reabre; relato de um cliente bloqueia compra de outro no mesmo destino; isolamento entre clientes; validação de entrada; resposta inválida ou fora de hora; motor fora → 202 e retry; os 6 cenários da demonstração; verificador de mensagens (indução → RED, 0800 → GREEN) |
| `core-api` · `CheckUrlTest` | 4 | Extração e classificação de host (oficial, semelhante, encurtador, desconhecido); sem HTTP |
| `core-api` · `CheckSignalsTest` | 4 | Famílias no conjunto de desenvolvimento (não é o holdout) |
| `core-api` · `CheckServiceTest` | 9 | Score, GRAY no lugar de GREEN vazio, falha segura, UNVERIFIED / CONFIRMED / DISCARDED |
| `core-api` · `CheckRateLimiterTest` | 1 | Teto da janela de 60 s |
| `core-api` · `CheckEvaluationTest` | 1 | Holdout `check-eval.json` (19 casos). Métricas no log, sem exigir 100% |
| `core-api` · `CheckApiTest` | 1 | `POST /api/check`: entrega → alerta, NEXA legítima → GRAY, 0800 → GREEN, vazio/longo → 400 |
| `core-api` · `IncidentAlertAdminApiTest` | 6 | CRUD de relato pelo cliente; relato em análise não pode ser alterado; consentimento obrigatório; avisos (listar, ler, apagar); CRUD de usuários pelo admin |
| `core-api` · `OpsQueryTest` | 4 | Filtros e paginação do painel |
| `core-api` · `PolicyAndShadowTest` | 14 | Catálogo de pesos; simulação não grava a compra; sombra quebrada não muda a aprovação; outra organização não lê compra, fila, visão geral, auditoria, política fintech nem modelo; teto de frequência; negativa inconclusiva; modelo contextual em sombra; aprovação contextual sem derrubar o modelo de operação |
| `machine-learning` · `TrainingTest` | 2 | Treino Smile, gravação, releitura, previsão e busca de pesos sem publicar; grampos de peso |
| `machine-learning` · `ContextStudyTest` | 4 | Vetores idênticos na fraude discreta; resposta ausente; treino sem usar o conjunto reservado; releitura de `logistic-context.ser` e `random-forest-context.ser` |
| `machine-learning` · `FalseNegativeDiagnosisTest` | 3 | As 51 negações do conjunto 9901 coincidem com compras habituais; a semente 12011 repete o empate; o corte 0,5 erra as 51 e um corte menor marca compra habitual |
| `core-api` · `PersistenceTest` | 6 | CRUD direto nos repositórios e restrições do banco |

Os testes de API sobem a aplicação inteira (Spring Boot + Flyway + H2 em modo PostgreSQL) e usam o motor de risco real em memória, então exercitam o mesmo código que roda em produção.

O frontend estático tem ainda `npm run test:unit` (formatação, busca do catálogo, validação de cartão e erros HTTP) e `npm run test:smoke` (as páginas abrem sem erro de JavaScript). O teste de história completa continua em `npm run test:e2e` e precisa da API no ar.

## 2. Teste de ponta a ponta pela interface

```bash
cd frontend && BASE=http://localhost:8088 npm run test:e2e
```

Reproduz a história da apresentação no navegador (Playwright):

```
✓ checkout com sinais de indução pede contexto
✓ respostas orientadas pausam a compra
✓ cliente cancela e nada é cobrado
✓ relato registrado no app do banco
✓ compra de outra cliente para o mesmo destino é bloqueada
✓ painel mostra a regra R1 na compra bloqueada
Fluxo completo OK
```

## 3. Desempenho

```bash
node perf/load.mjs            # API=http://... para outro endereço; DURATION=60 para fases maiores
```

Mix de uso: 60% vitrine, 25% checkout (passa pelo motor de risco), 15% consulta de compras. Cada usuário virtual espera 50–200 ms entre ações. Fases de 20 s, depois de um aquecimento.

Ambiente: os três serviços na mesma máquina (4 vCPU, 15 GB), PostgreSQL 16 local.

| Fase | Usuários | Requisições | Vazão | p50 | p95 | p99 | Erros | Meta p95 |
|---|---|---|---|---|---|---|---|---|
| Normal | 10 | 1.379 | 68,5 req/s | 6,8 ms | **56,6 ms** | 81,2 ms | 0% | 500 ms ✅ |
| Pico | 50 | 4.909 | 242,4 req/s | 28,9 ms | **364,4 ms** | 558,8 ms | 0% | 1.500 ms ✅ |

Por operação, no pico: checkout p95 164 ms (inclui a chamada ao motor); vitrine p95 < 60 ms; consulta de compras p95 607 ms.

### O que o teste encontrou (e o que mudamos)

A primeira rodada passou na meta, mas por pouco: **p95 de 1.085 ms no pico**, com a consulta de compras em 1,45 s. A causa era a falta de índices: cada linha da lista buscava a última avaliação da compra varrendo a tabela inteira, e a lista crescia com a carga.

Correção: migração `V2__indexes.sql` (índices em `transactions`, `assessment`, `status_change`, `alert`, `incident`…) e limite de 50 compras na lista do cliente e 300 na fila do analista.

| | p95 no pico | Vazão no pico |
|---|---|---|
| Antes | 1.085 ms | 161 req/s |
| Depois | **364 ms** | **242 req/s** |

Próximo passo, se precisar: a lista ainda faz uma consulta por linha (N+1). Uma consulta única com `JOIN` levaria a consulta para a faixa do checkout.

Resultado bruto: `perf/results/2026-10-16-depois-dos-indices.json`.

## 4. Usabilidade e acessibilidade

### Varredura automática

```bash
cd frontend && BASE=http://localhost:8088 npm run test:a11y
```

axe-core com as regras WCAG 2.0/2.1 A e AA e boas práticas, em **19 telas** (loja, app do banco logado e deslogado, todas as telas do painel). Resultado: **0 violações**.

A primeira varredura achou problemas de contraste (texto cinza claro sobre fundo creme, rótulos do menu lateral), ordem de títulos e uma tela sem região principal. Todos foram corrigidos.

### Avaliação heurística das mensagens de alerta

Revisamos cada mensagem que o cliente vê com as heurísticas de Nielsen:

| Heurística | Como as mensagens atendem |
|---|---|
| Visibilidade do estado | Toda compra mostra o estado em linguagem do cliente (“Pausada para sua proteção”, “Aguardando sua confirmação”) e o mesmo estado aparece na loja e no app |
| Linguagem do usuário | Nenhum termo técnico: “score”, “regra R5” e “sinal” só aparecem no painel do analista. O cliente vê motivos como “O valor está bem acima do que você costuma gastar” |
| Controle e liberdade | Na intervenção, o cliente pode cancelar (ação principal) ou continuar, desde que confirme que ninguém está orientando |
| Prevenção de erros | Continuar exige marcar a confirmação; apagar relato ou usuário pede confirmação |
| Reconhecer em vez de lembrar | As perguntas de verificação mostram o endereço e o aparelho em questão, em vez de pedir que o cliente lembre |
| Ajuda e recuperação | Toda mensagem de risco diz o que fazer: ligar para a central oficial (0800), registrar em “Fui vítima” |

Cuidados de interface aplicados: foco visível em todos os controles, alvos de toque de 40 px ou mais, rótulos ligados aos campos (`label` + `aria-describedby` para dica e erro), anúncio de mudanças por `aria-live`, respeito a `prefers-reduced-motion`, números tabulares nas tabelas e layout testado em 390 px de largura.

### Roteiro para teste com usuários (a fazer antes da banca)

Com 5 pessoas fora do curso (o suficiente para achar a maioria dos problemas de usabilidade):

1. Contexto: “Você recebeu mensagens de alguém dizendo ser do seu banco. Siga as instruções e faça a compra.”
2. Tarefa: fazer a compra do cenário I na loja.
3. Observar, sem ajudar: a pessoa entende por que a compra parou? Escolhe cancelar? Encontra o “Fui vítima”?
4. Ao final, aplicar o questionário SUS (10 perguntas) e perguntar: “Com suas palavras, o que o banco quis dizer?”

Metas: 4 de 5 cancelam a compra; 4 de 5 explicam o motivo; SUS médio ≥ 70.
