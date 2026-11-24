# Confira antes de pagar

O verificador (`POST /api/check`) analisa a **mensagem** colada no Aureon. O score abaixo é só dele. O Risk Engine continua avaliando a **compra** (R1–R11) e não usa estes pesos.

Não há busca HTTP do link (sem SSRF). Encurtador não é expandido. O texto da mensagem não é gravado em log: só tamanho, nível, score, veredito e famílias.

## Níveis

| Interno | `level` | `verdict` | Quando |
|---|---|---|---|
| Alto risco | `RED` | `alto` | Score ≥ 40 **ou** indicador `CONFIRMED` |
| Atenção | `YELLOW` | `atencao` | Score ≥ 16, encurtador/semelhante, ou relato `UNVERIFIED` |
| Informação insuficiente | `GRAY` | `insuficiente` | Há texto, mas nenhum sinal confiável **e** nenhum canal oficial |
| Baixo risco observado | `GREEN` | `baixo` | Canal ou domínio oficial **e** score baixo. Não é o default |

`GREEN` exige evidência positiva. “Não achei nada” é `GRAY`.

## Famílias (teto de um ponto por família)

| Família | Peso | Tipo |
|---|---|---|
| `delivery` | 25 | Falsa taxa / encomenda retida |
| `bank` | 22 | Falsa central |
| `relative` | 22 | Falso parente / número novo |
| `support` | 22 | Falso suporte técnico |
| `prize` | 20 | Falso prêmio |
| `advance` | 20 | Cobrança antecipada |
| `job` | 18 | Falsa vaga / renda extra |
| `validation` | 16 | Link de “validar dados” |
| `payment` | 8 se já houver tipo, senão 16 | Pedido de pagamento |
| `secrecy` | 16 | Pedido de segredo |
| `procedure` | 18 | Compra como “procedimento” |
| `code` | 20 | Pedido de senha / código |
| `remote` | 18 | App remoto / tela |
| `refund` | 12 | Promessa de estorno |
| `address` | 12 | Endereço indicado por terceiro |
| `urgency` | 10 | Pressão de tempo |
| `url-similar` | 24 | Host parece marca oficial |
| `url-shortener` | 18 | Encurtador conhecido |
| `url-unknown` | 4 | Host fora da lista, só se já houver tipo |
| `url-obfuscation` | 6 | Punycode ou mistura de alfabetos |
| `url-ip` | 16 | Host é um endereço IP |
| `url-userinfo` | 20 | Texto antes de `@` esconde o destino |
| `authority` | 18 | Falsa polícia, Receita ou órgão |
| `seller` | 10 | Vendedor novo ou com muitas reclamações |
| `intel` | 8 / 10 / 28 | `UNVERIFIED` / `INVESTIGATING` / `CONFIRMED` |

O texto é normalizado (acento fora, `R$` → `reais`) **só para casar regras**. A resposta devolve o original.

## Inteligência

Colunas em `intel_entity` (Flyway V4): `status`, `confidence`, `valid_until`.

| Status | No verificador |
|---|---|
| `CONFIRMED` | Pode puxar para `RED` |
| `INVESTIGATING` | Soma 10 |
| `UNVERIFIED` | Soma 8 e piso `YELLOW` |
| `DISCARDED` | Ignora |
| validade vencida | Trata como `UNVERIFIED` |

`reportsFor` usado no checkout da compra **não muda** (continua contando relatos ativos, exceto `not-scam`).

## API

Campos novos, só acréscimo: `score`, `signals[]` (`family`, `label`, `explain`, `weight`), `unverified[]`, `verdict`, `analyzedAt` (instante da análise, ISO-8601). `types`, `cues` e `level` permanecem.

O analista revisa um indicador com `PATCH /api/ops/intelligence/{id}` e corpo `{ "status", "confidence" }`. Status: `UNVERIFIED`, `INVESTIGATING`, `CONFIRMED`, `DISCARDED`. A mudança entra na auditoria como `INTEL_REVIEWED`. `DISCARDED` deixa de contar no Confira. Os dados anteriores não são apagados.

`POST /api/check` é público, com teto por IP (`contextshield.check.per-minute`, 40 em produção, 200 nos testes). Se a inteligência falhar, a resposta **não** é `GREEN`.

## Avaliação

Conjunto de desenvolvimento: `CheckSignalsTest`, `CheckServiceTest`, `CheckUrlTest`.

Holdout: `services/core-api/src/test/resources/check-eval.json` (24 casos, execução 02/11/2026). Métricas em `CheckEvaluationTest` (log). **Não** é detecção perfeita. O lote inteiro levou 71 ms neste processo; isso não é teste de carga HTTP.

Alerta = `RED` ou `YELLOW` versus o resto:

| | Valor |
|---|---|
| Precisão do alerta | 1,00 (18 / 18) |
| Recall do alerta | 1,00 (18 / 18) |
| FP / FN do alerta | 0 / 0 |
| TN | 6 |

Por classe (`expect` × `got`):

| Classe | Suporte | Precisão | Recall |
|---|---|---|---|
| RED | 6 | 0,67 | 0,67 |
| YELLOW | 12 | 0,83 | 0,83 |
| GRAY | 4 | 1,00 | 1,00 |
| GREEN | 2 | 1,00 | 1,00 |

Quatro divergências foram só entre RED e YELLOW (suporte técnico ficou em atenção; adiantamento e typo subiram a alto; burla com espaços só viu o encurtador). Conjunto pequeno: estes números não generalizam para produção.
