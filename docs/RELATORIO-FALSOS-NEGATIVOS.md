# Falsos negativos da avaliação contextual

Data: 24/11/2026. Os números do conjunto reservado foram conferidos em `machine-learning/reports/context-study.json` antes de qualquer alteração. O diagnóstico novo está em `machine-learning/reports/false-negative-diagnosis.json`. Nenhum modelo `.ser` foi substituído e nenhuma política publicada foi alterada.

## O que o relatório antigo já dizia

Conjunto reservado, semente 9901: 400 operações, 158 fraudes. Os dois modelos contextuais tiveram precisão 1,0000, recall 0,6772, F1 0,8075, 107 acertos e 51 falsos negativos. A política padrão liberou as 158 fraudes. Quando as respostas entraram só na comparação, 107 foram retidas e 51 continuaram liberadas.

## Por que os 51 erros acontecem

Os 51 erros são as 51 operações do roteiro `fraude-que-nega`. Em todas:

- Respostas declaradas: origem própria, sem contato, endereço meu, sem orientação. São as mesmas respostas do roteiro `legitima-habitual`.
- Sinais da operação iguais aos de alguma compra habitual do mesmo conjunto: horário diurno, aparelho conhecido, destino habitual, vendedor estabelecido, origem própria, sem incidente.
- Ausente em todas: texto de orientação, incidente de destino, incidente de vendedor, aparelho novo, destino de terceiro, admissão de ajuda.
- Modelo publicado: probabilidade 0,328 a 0,408. Nas compras habituais, 0,327 a 0,405.
- Motor, antes das respostas: APPROVE, score 6.
- Motor, depois da negativa: score 0, regra S-R8, APPROVE_WITH_ALERT. As 51 compras habituais com as mesmas respostas também ficam em APPROVE_WITH_ALERT.

A distância mediana de uma negação até a compra habitual mais próxima é 0,0166. A distância mediana entre as próprias compras habituais é 0,0180. Uma negação ficou fora da faixa contínua observada nas 51 compras habituais desta amostra; os bits e as respostas continuam iguais, e isso não abre um corte sem falso positivo.

## O que dá para corrigir e o que não dá

Dá para reconhecer fraude quando a resposta admite pedido, contato ou orientação, ou quando existe script, incidente, aparelho novo ou destino diferente. Esses casos já entram nos 107 acertos.

Não dá para separar, com estes atributos, a negativa da compra habitual. Um corte de 0,40 marcaria 9 negações e 6 compras habituais. Um corte de 0,30 marcaria as 51 dos dois lados. A semente 12011, lida uma vez e não usada para ajuste, teve 72 negações no mesmo empate: o corte 0,40 marcaria 5 e também 8 compras habituais.

Para reduzir esse erro seria preciso um registro observado, não declarado: a mensagem em si, um incidente confirmado, uma mudança real de aparelho, de beneficiário ou de hábito de pagamento, ou um histórico de verificação que o sistema tenha visto. Esses campos não existem nestas 51 operações. Inventá-los mudaria o gerador, não a detecção.

## O que foi feito

O diagnóstico foi escrito em Java e passou a fazer parte da suíte. Logística, floresta, pesos, cortes e regras R1–R12 ficaram como estavam. O aprendizado continua em sombra. A suíte completa ficou em 129 testes e 0 falhas.

| Versão | Falsos negativos | Falsos positivos neste par | Recall neste par | Precisão | Decisão |
|---|---|---|---|---|---|
| Modelo publicado, corte 0,50 | 51 de 51 negações; 51 no conjunto de 158 fraudes | 0 de 51 habituais; 0 no conjunto | 0,6772 no conjunto | 1,0000 no conjunto | Mantida |
| Corte 0,40 | 42 negações ainda de fora | 6 habituais | não recalculado no conjunto | cairia | Rejeitado |
| Corte 0,30 | 0 negações de fora | 51 habituais | não recalculado no conjunto | cairia | Rejeitado |
| Alerta S-R8 depois da negativa | 0 se o alerta contasse como fraude | 51 habituais | — | — | Já existe e não separa |

A latência publicada da floresta contextual nesse conjunto é 0,0316 ms. Não há modelo novo, então a latência, a taxa de verificação e a taxa de intervenção da política padrão não mudaram. A política padrão continua sem pedir verificação numa compra habitual.
