# Relatório do protótipo — 24/11/2026

Este relatório descreve o que foi verificado no ContextShield V2 depois da regra R12, da migração V8 e da suíte de 118 testes. Não declara prontidão para produção bancária, certificação LGPD nem multiempresa comercial.

## Funcionalidades já verificadas e preservadas

- Motor de regras R1–R11, política vazia e paridade com o protótipo original: `ParityTest`, 43 testes.
- Faixas, pisos de segurança e limite de bloqueio em 80.
- Treino Smile 3.1.1 da semente 42, com os arquivos `logistic-regression.ser` e `random-forest.ser`.
- Modo sombra da operação: a falha do modelo não aprova a compra.
- PostgreSQL local com as migrações anteriores. A V9 foi acrescentada sem apagar tabela nem linha.
- Confira, NEXA e Aureon não foram refeitos. O frontend continua estático.

## Funcionalidades implementadas nesta etapa

- R12 seletiva. A flag `context-when-unknown` continua desligada. Ligada, só pede verificação em compra de score baixo que já tenha um sinal de atenção existente: horário atípico, aparelho novo, valor acima do hábito, histórico curto, anúncio, destino novo, sessão anômala, ponto de retirada, vendedor recente ou reclamações altas. A lista pode ser substituída pela configuração `context-probes`. O valor `NONE` não pede verificação. O score não muda.
- Teto de frequência na Core API: `context-probe-max` (padrão 1) em `context-probe-hours` (padrão 24). Com o teto em zero, uma compra com anúncio não entrou em R12.
- Simulação `GET /api/ops/engine/context-probe`. Não grava decisão.
- Métricas `GET /api/ops/verifications`. Pedido vencido passa a `EXPIRED` e a compra não é aprovada por isso.
- Negativa sem roteiro ou relato independente fica `INCONCLUSIVE`. O texto diz que a resposta é declarada e não prova legitimidade. O motor continua aplicando `VER_CLEAR`, que já existia.
- Aprovação explícita de modelo contextual `RECORDED` só para sombra. Não marca a meta de aceite como cumprida, não autoriza decisão e não tira o modelo de operação do estado aprovado.
- A compra no Operations mostra estágio, modelo, versão da biblioteca Smile, previsão, decisão do motor e explicação.
- Visão geral, auditoria, políticas, modelos, experimentos, sombra e relatos passam a respeitar a organização do usuário autenticado. O catálogo da loja permanece compartilhado.

## Testes

`mvn -pl services/core-api -am test` no JDK 21.0.13: **126 testes, 0 falhas**.

| Módulo | Testes |
|---|---|
| Risk engine, incluindo paridade | 53 |
| Machine learning | 6 |
| Core API | 67 |

Os arquivos `logistic-context.ser` e `random-forest-context.ser` foram recarregados no teste do módulo de machine learning. O conjunto reservado da semente 9901 não foi usado para treinar nem para escolher modelo.

## Métricas obtidas no PostgreSQL

Consulta feita com a API no ar, depois das compras abaixo. Nada foi apagado.

- Flyway na versão 9, com sucesso. PostgreSQL 18.6.
- Verificações ao fim do ensaio, lidas na tela Modelos: 10, 0 expiradas, taxa de verificação 0,25, abandono 0. A consulta intermediária, com 37 compras, tinha taxa 0,243.
- Simulação da verificação seletiva: 18 compras aprovadas examinadas, 3 passariam a pedir verificação, taxa 0,167, `persisted` falso. A flag global permaneceu desligada.
- 40 compras no banco ao final do ensaio.

## Resultados do Machine Learning

Os números da semente 42 e do estudo contextual não foram recalculados. No conjunto reservado, os modelos contextuais continuam com precisão 1,0000, recall 0,6772, F1 0,8075 e 51 falsos negativos, todos do roteiro em que a pessoa nega ajuda. Não houve dado novo para melhorar isso.

Na compra `TX-2026-000205`, com o modelo contextual 3 aprovado só para sombra:

| Estágio | Modelo | Previsão | Decisão do motor |
|---|---|---|---|
| Checkout | 1, logistic-regression, Smile 3.1.1 | LEGIT | REQUEST_CONTEXT |
| Depois das respostas | 1, logistic-regression, Smile 3.1.1 | LEGIT | INTERVENE |
| Depois das respostas | 3, logistic-regression-context, Smile 3.1.1 | FRAUD | INTERVENE |

`affectsDecision` ficou falso nas três linhas. A logística de operação não viu a orientação porque esse vetor não inclui as respostas. A logística contextual viu as respostas declaradas e estimou fraude. Quem decidiu foi a regra R2.

## Fluxo integrado

| Caso | Compra | Resultado |
|---|---|---|
| Compra habitual, origem própria, casa, aparelho conhecido | TX-2026-000200 | APPROVED, score 11, R8. O horário era de madrugada, então havia horário atípico, e a flag seletiva estava desligada |
| Fraude evidente: origem em link e respostas de pedido, telefone, endereço passado e orientação | TX-2026-000201 | Pediu verificação e, depois das respostas, INTERVENTION, score 44, R2. Não chegou a bloqueio |
| Fraude discreta com a mesma cara da compra habitual | TX-2026-000202 | APPROVED, score 11, R8. Não foi identificada |
| Negativa sem evidência independente, origem por indicação | TX-2026-000204 | Verificação INCONCLUSIVE. O motor liberou com alerta por causa de `VER_CLEAR` |
| Previsão contextual | TX-2026-000205 | Motor INTERVENE. Sombra contextual FRAUD, sem mudar a decisão |
| Motor desligado | TX-2026-000206 | Ficou ANALYZING. Depois do reinício e do retry, APPROVED, score 11 |
| Política | rascunho 4 | Simulação não gravou a compra. Publicação levou o aparelho novo a 10. A restauração da política 1 devolveu o peso a 8. A compra seguinte, TX-2026-000207, foi APPROVED com score 11 |

## Falhas e limitações

- A fraude discreta continua aprovada. Os 19 atributos não separam esse caso de uma compra lícita.
- A negativa declarada não é tratada como prova na verificação, mas o motor ainda soma `VER_CLEAR`. Uma compra induzida que só nega ajuda pode ser liberada com alerta.
- Os 51 falsos negativos do conjunto reservado permanecem. Não foi feito novo treino para alterar essa conta.
- A primeira subida da API nesta etapa usou um jar antigo de machine learning. A sombra contextual registrou “Catálogo de características incompatível” e a compra seguiu com o motor. Depois de instalar o jar atual e reiniciar, a previsão contextual funcionou.
- O Flyway avisa que o PostgreSQL 18.6 não está na faixa testada por ele. As migrações aplicaram.
- O comando `docker` não existe nesta máquina. O Compose não foi executado.
- Não houve varredura de CVE com base nacional de vulnerabilidades. As dependências continuam as do Spring Boot 3.5.16, Smile 3.1.1 e o driver do PostgreSQL trazido por esse conjunto. Nenhuma troca de versão foi feita sem uma falha confirmada e um conserto compatível.
- Visão geral, auditoria, política, modelo e relato estão separados por organização nos testes. O catálogo da NEXA, os clientes e os aparelhos da demonstração continuam compartilhados. Isso não é multiempresa completo.

## O que não está preparado para produção bancária

- Dados de treino sintéticos e rótulos gerados.
- Segredo JWT de desenvolvimento.
- PostgreSQL 18 fora da faixa declarada pelo Flyway.
- Docker não validado neste ambiente.
- Sem revisão externa, sem base real de fraude e sem operação de plantão.
- Modelo sem autorização para decidir, de propósito.

## Evidências

- Suíte: 126 testes, 0 falhas, JDK 21.0.13, Spring Boot 3.5.16.
- Banco: `flyway_schema_history` versão 9 com sucesso; verificação de `TX-2026-000204` em `INCONCLUSIVE` com o texto de que a negativa não prova legitimidade; `TX-2026-000205` em `COMPLETED`.
- API no ar em 8080 e motor em 8081 depois do reinício. Frontend estático em 5173.
- A tela da compra `TX-2026-000205` mostrou as três linhas da sombra, a decisão R2 e o texto de que as respostas são declaradas. A tela Modelos mostrou 14 previsões sem erro, 2 falhas, latência média 0,208 ms e a taxa de verificação 0,25. Uma das falhas é o jar antigo de machine learning, registrado antes do reinício.
