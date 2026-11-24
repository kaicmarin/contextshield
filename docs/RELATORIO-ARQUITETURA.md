# Cinco processos, 24/11/2026

O checkpoint anterior a esta extração é `de95f54`. O banco PostgreSQL local, os arquivos `.ser` e as políticas publicadas não foram substituídos. Nada foi criado na AWS.

## Serviços

| Serviço | Porta | O que ele decide |
|---|---|---|
| Core API | 8080 | Entrada pública, sessão, compra e orquestração. Já existia. |
| Risk Engine | 8081 | Score, regras R1–R12 e a decisão da compra. Já existia. |
| ML Service | 8082 | Inferência dos arquivos `.ser`. Não autoriza a compra. |
| Threat Intelligence | 8083 | Indicador novo: nasce `UNVERIFIED`. Não abre URL. |
| Investigation | 8084 | Caso de revisão. Confirmação não publica modelo. |

A Core API continua sendo a porta do NEXA, do Aureon e do Operations. Com `ML_SERVICE_URL`, `THREAT_SERVICE_URL` e `INVESTIGATION_SERVICE_URL` vazios, a compra e o “Confira” seguem o caminho que já estava testado. O compose da AWS preenche esses endereços.

O cálculo das mensagens do “Confira” e o relato “Fui vítima” continuam na Core API, porque dependem do catálogo e das tabelas atuais. O serviço de inteligência guarda indicadores novos. O de investigação guarda casos novos. Os dois não leem as tabelas um do outro.

## Prova local

Os três serviços novos subiram em processos separados, com H2 em memória, sem tocar o PostgreSQL 18. Os health checks responderam `UP`. Sem token, o ML Service devolveu 401. Com o token de serviço:

- a lista trouxe os quatro `.ser` e `decidesPurchase=false`;
- `logistic-regression.ser`, com `affectsDecision` enviado como verdadeiro, respondeu `LEGIT` e `affectsDecision=false`;
- um caso abriu em `OPEN`, com rótulo de treino falso, ignorando o id de organização do corpo;
- um indicador de URL interna nasceu `UNVERIFIED`, sem busca da URL.

A suíte ficou em 137 testes e 0 falhas. O Docker não está instalado, então imagem, Compose e medição de RAM em contêiner não foram executados.

## O que não mudou

Os 51 falsos negativos da semente 9901 continuam indistinguíveis de compras habituais. Indicador confirmado e caso confirmado são evidências novas quando existirem. Não foram inventados para aqueles 51 casos e não retreinaram modelo.

Uma negativa declarada aplica `VER_CLEAR`, que reduz o score, e não prova legitimidade. Se a resposta admite pedido e contato, a regra continua R2 e a decisão é intervir. A R12 segue desligada por padrão.

O segredo JWT de desenvolvimento continua só no ambiente local e o código avisa para trocá-lo antes de publicar. O guia de hospedagem está em `docs/AWS-FREE-DEPLOY.md`.
