# Machine Learning no ContextShield

Este texto explica, em português, o que foi implementado e o que foi de fato executado em 24/11/2026. Os números vêm do arquivo `machine-learning/reports/training-report.json`. Eles medem dados sintéticos. Não são taxa de detecção de fraude bancária real.

## 1. O que é Machine Learning

Machine Learning, neste trabalho, é um programa que recebe exemplos já rotulados e ajusta um modelo para prever o rótulo de um exemplo novo.

Aqui o rótulo é binário: `FRAUD` (a história do gerador é uma fraude) ou `LEGIT` (a história é uma compra lícita). O modelo não recebe o score do motor de risco e não recebe a decisão automática. A decisão do motor não é tratada como verdade.

Depois do treino, o modelo devolve uma probabilidade de fraude. O ContextShield guarda essa previsão ao lado da decisão do motor. Ela não aprova nem bloqueia a compra.

## 2. Por que Java

O ContextShield já é Java 21 e Spring Boot. Treinar em outra linguagem obrigaria a manter dois ambientes, dois formatos de modelo e um serviço extra só para a previsão.

Todo o treino, a avaliação, a gravação, a leitura e a previsão acontecem na JVM. Não há script Python, pandas, NumPy, scikit-learn nem Optuna.

## 3. Biblioteca escolhida

A biblioteca é o **Smile** (`com.github.haifengl:smile-core`), versão **3.1.1**.

O Smile 3.1.1 é Java puro, traz regressão logística e random forest, e os classificadores são serializáveis. A versão 7.x do Smile exige Java 25. O projeto fixa Java 21, então a 3.1.1 foi a linha compatível. A API foi conferida no jar baixado (`javap`), porque a documentação da linha 7 descreve métodos diferentes.

O Tribuo não foi usado.

Dependência Maven, no módulo `machine-learning`:

```xml
<dependency>
  <groupId>com.github.haifengl</groupId>
  <artifactId>smile-core</artifactId>
  <version>3.1.1</version>
</dependency>
```

O `smile-base` entra por transitividade. A Core API depende dos módulos `machine-learning` e `risk-engine` para carregar o modelo e para o otimizador chamar o avaliador real.

## 4. Como funciona a regressão logística

A regressão logística aprende um peso para cada característica e uma constante. A soma ponderada passa por uma função sigmoide e vira uma probabilidade entre 0 e 1.

No Smile 3.1.1 o ajuste é `LogisticRegression.fit(double[][], int[], lambda, tolerância, iterações)`, com o método L-BFGS. Hiperparâmetros usados:

| Parâmetro | Valor |
|---|---|
| lambda (regularização) | 0,1 |
| tolerância | 0,00001 |
| iterações máximas | 200 |

Nesta execução o treino da logística levou **384,64 ms**.

## 5. Como funciona o Random Forest

O random forest treina várias árvores de decisão. Cada árvore vê uma amostra dos exemplos e, em cada divisão, só algumas características. A previsão é o voto das árvores. A probabilidade é a fração de árvores que votou fraude.

No Smile 3.1.1 a floresta não aceita a matriz numérica direto. O código monta um `DataFrame`, marca a coluna `fraud` com `Formula.lhs("fraud")` e chama `RandomForest.fit`. Hiperparâmetros usados:

| Parâmetro | Valor |
|---|---|
| árvores | 80 |
| características por divisão (mtry) | 4 (raiz do número de características) |
| critério | Gini |
| profundidade máxima | 12 |
| nós máximos | 256 |
| tamanho mínimo do nó | 5 |
| amostragem | 1,0 |
| peso de classe | inverso da frequência: a classe fraude pesa mais |

Nesta execução o treino da floresta levou **362,48 ms**.

## 6. Como o treinamento foi executado

Na raiz do projeto, com o JDK 21:

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
mvn -pl machine-learning exec:java "-Dexec.args=4000 42 machine-learning"
```

O primeiro número é a quantidade de operações. O segundo é a semente. O terceiro é a pasta de saída.

A execução de 24/11/2026 terminou com sucesso em **7,29 s** de experimento (26,4 s contando a subida do Maven). Java `21.0.13.0.2+1-LTS`. O Maven avisou que duas threads do pool do Smile continuaram vivas depois do processo; o relatório e os arquivos já tinham sido gravados.

Para repetir só o teste menor (400 operações, semente 7, diretório temporário):

```powershell
mvn -pl machine-learning test
```

Esse teste também treina de verdade. Ele não substitui o relatório de 4000 operações.

## 7. Quais dados foram utilizados

Não havia uma tabela de fraudes confirmadas por analista. O treino usou o gerador Java `SyntheticCases`, semente **42**, **4000** operações pedidas e geradas.

| Conjunto | Quantidade |
|---|---|
| Geradas | 4000 |
| Inconclusivas, excluídas antes da divisão | 99 |
| Fraudes discretas, fora do modelo e dentro da busca de pesos | 41 |
| Fraudes utilizáveis pelo modelo | 1312 |
| Legítimas utilizáveis pelo modelo | 2548 |
| Soma conferida (`accountedExamples`) | 4000 |
| Treino | 1930 (656 fraudes, 1274 legítimas) |
| Teste do modelo | 772 (262 fraudes, 510 legítimas) |
| Busca de pesos | 599 |
| Confirmação da busca | 600 |
| Generalização, outra mistura, fora do treino | 800 (434 fraudes, 366 legítimas) |
| Resultados confirmados por analista acrescentados ao treino | 0 |

A divisão é estratificada: 50% treino, 15% busca, 15% confirmação, 20% teste. A semente da divisão é `42` xor `0x5DEECE66`. O teste não entra na busca de pesos.

As 19 características existem antes da decisão. Nenhuma é o score, a regra ou a resposta da verificação:

`amountRatio`, `purchases90d`, `tenureYears`, `unusualHour`, `newDevice`, `locationMatch`, `habitualDest`, `newOwnDest`, `thirdDest`, `pickup`, `sellerRecent`, `sellerClaims`, `sellerEstablished`, `originSelf`, `originAd`, `originLink`, `originGuided`, `destIncident`, `sellerIncident`.

O rótulo vem do roteiro do gerador (a pessoa foi induzida ou não), não das regras R1–R11. Os roteiros incluem compra habitual, aparelho novo, valor alto para a própria casa, madrugada lícita, compra lícita com vários sinais, presente, link de conhecido, indução, falso atendente, falsa cobrança, destino denunciado, vendedor suspeito, vendedor novo com compra própria, caso ambíguo e fraude discreta.

A fraude discreta parece um presente na primeira tela. O modelo não a vê no treino nem no teste, porque a mentira só aparece nas respostas. Ela entra só na busca e na confirmação de pesos, para o motor real.

Planilha gerada: `machine-learning/datasets/sintetico-semente-42.csv` (519 104 bytes).

## 8. Como os resultados são avaliados

O conjunto de teste ficou de fora do treino. Para cada modelo o código calcula precisão, recall, F1, acurácia (só registrada), matriz de confusão, falsos positivos, falsos negativos, PR-AUC (precisão média), erro de calibração em 5 faixas e tempo médio de inferência.

A escolha entre os modelos usa F1 e, no empate, o recall. Acurácia não decide. Se a diferença de F1 e de recall for menor que 0,01, o relatório marca empate prático.

Resultado no teste de 778 operações (24/11/2026):

| Métrica | Regressão logística | Random forest |
|---|---|---|
| Precisão | 1,0000 | 1,0000 |
| Recall | 0,7252 | 0,7252 |
| F1 | 0,8407 | 0,8407 |
| Falsos positivos | 0 | 0 |
| Falsos negativos | 72 | 72 |
| Verdadeiros positivos | 190 | 190 |
| Verdadeiros negativos | 510 | 510 |
| PR-AUC | 0,9316 | 0,9282 |
| Erro de calibração | 0,0176 | 0,0516 |
| Inferência média | 0,0022 ms | 0,0275 ms |

Os dois empatam no teste misturado. A logística calibra melhor e responde mais rápido. Isso não escolhe um vencedor para produção.

Uma execução anterior, com roteiros fáceis de separar, chegou a F1 1,00. Esse número foi descartado como evidência. Nesta execução o teste inclui fraude com cara de compra habitual. Os 72 falsos negativos são esses casos: o vetor não contém a resposta da verificação, e os dois modelos chamam a operação de legítima.

No conjunto de generalização (800 operações, outra mistura, sem uso no treino) os dois modelos ficaram com recall 0, F1 0 e 434 falsos negativos. Acertaram as 366 legítimas com sinais. O PR-AUC ficou 1,0000 na logística e 0,9689 na floresta: a ordem das probabilidades ainda separa os grupos, mas o corte de 0,5 classifica todo mundo como legítimo. O erro de calibração passou de 0,39. Esse é o resultado honesto: o modelo não vê a indução quando ela só aparece na conversa.

## 9. Como os modelos são salvos

O Smile 3.1.1 marca o classificador como `Serializable`. `SavedModel` grava o objeto com `ObjectOutputStream`, junto do esquema das características. Na leitura, o código recusa o arquivo se a lista de nomes for diferente de `Features.NAMES`.

Arquivos desta execução:

| Modelo | Caminho | Tamanho |
|---|---|---|
| Regressão logística | `machine-learning/models/logistic-regression.ser` | 1 382 bytes |
| Random forest | `machine-learning/models/random-forest.ser` | 64 123 bytes |
| Relatório | `machine-learning/reports/training-report.json` | 6 386 bytes |

Depois de gravar, o programa releu os dois arquivos e comparou as previsões com as do modelo ainda em memória. `reloadMatchedTraining` ficou `true`.

Os arquivos continuam no disco depois que o processo termina. Nenhum dos dois foi aprovado para o modo sombra (`approved: false`).

## 10. Como o Risk Engine utiliza os modelos

O motor continua calculando o score e aplicando R1–R11. A Core API, depois de gravar essa decisão, chama `MachineLearningService.shadow`.

O serviço só carrega um modelo com status `APPROVED` no banco. Sem modelo aprovado, ele não faz nada. Com modelo aprovado, monta o mesmo vetor de 19 características, executa a inferência e grava a previsão, a versão e o tempo em `ml_shadow`.

O treino não roda dentro do checkout. Um experimento é uma tarefa em segundo plano, uma de cada vez, com no máximo 4000 operações.

## 11. Como funciona o modo sombra

Sombra significa comparação, não mistura.

- A decisão que o cliente vê é a do motor.
- A previsão do modelo fica registrada ao lado.
- Uma falha de carga, um vetor inválido ou um arquivo ausente gera um aviso e, se der, uma linha de erro em `ml_shadow`. A compra segue.
- O score do modelo não é somado ao score do motor.

Enquanto nenhum administrador aprovar um modelo com validação `PASS`, a sombra fica ociosa. O painel mostra zero previsões. Isso é o estado real, não um gráfico vazio disfarçado de métrica.

## 12. Como o otimizador estuda os pesos

O otimizador é busca aleatória estruturada em Java. Não é Optuna.

Ele pega a política atual (no treino de linha de comando, o mapa vazio, que reproduz os pesos de hoje), sorteia de 3 a 6 pesos editáveis e soma ou subtrai 1 ou 2, respeitando mínimo, máximo e piso de segurança. São **36** candidatas. Cada uma passa pelo `RiskAssessor` real.

Custo = `5 × falso negativo + 1 × falso positivo`.

- Falso negativo: fraude liberada (`APPROVE` ou `APPROVE_WITH_ALERT`).
- Falso positivo: operação legítima que não foi liberada.

A candidata só vira sugestão se o custo cair na busca e, na confirmação, o custo e os falsos negativos não piorarem. Ela não é publicada.

Nesta execução:

| Conjunto | Falsos negativos | Falsos positivos | Custo |
|---|---|---|---|
| Busca, pesos atuais | 74 | 78 | 448 |
| Busca, melhor candidata | 74 | 72 | 442 |
| Confirmação, pesos atuais | 85 | 69 | 494 |
| Confirmação, melhor candidata | 85 | 65 | 490 |

A sugestão reduziu custo nos dois conjuntos e não aumentou falso negativo. Overlay sugerido, não aplicado:

| Chave | Valor sugerido |
|---|---|
| SELLER_IN_INCIDENT | 11 |
| THRESHOLD_HIGH | 56 |
| VER_ADDRESS_GIVEN | 10 |
| ORIGIN_AD | 1 |
| AMOUNT_ABOVE_MID | 9 |
| ORIGIN_SELF | −1 |

`published` ficou `false`. O falso negativo não caiu: a candidata só reduziu falso positivo. Isso é um indício no gerador, não uma mudança pronta para a loja.

## 13. Como o Operations apresenta sugestões

O painel estático ganhou duas seções, no mesmo visual do Operations:

- **Políticas de risco** (`/ops/politicas`): catálogo dos sinais, peso, faixa, piso de segurança, rascunho, envio para aprovação, simulação de uma transação já gravada, histórico. Aprovar, rejeitar, publicar e restaurar só aparecem para administrador.
- **Laboratório** (`/ops/laboratorio`): modelos importados do relatório real, precisão, recall, F1, falsos positivos e negativos, comparação com o empate registrado, sugestão de pesos e pedido de novo experimento. O pedido devolve na hora e o treino segue no servidor. A página consulta o status. O navegador não executa comando do sistema.

A compra da loja usa a política publicada do perfil `fintech`. Marketplace e e-commerce são rascunhos de demonstração. Não há isolamento multiempresa.

## 14. Como ocorre a validação

O portão automático foi definido no código antes desta execução (`Acceptance`):

| Critério | Limite |
|---|---|
| Fraudes no teste | pelo menos 30 |
| Legítimas no teste | pelo menos 30 |
| Precisão, recall e F1 | pelo menos 0,70 |
| Taxa de falso negativo | no máximo 0,30 |
| Inferência média | no máximo 5 ms |

Os dois modelos ficaram `PASS` neste conjunto sintético. `PASS` não publica o modelo. Publicar exige um administrador, e a aprovação recusa modelo que não esteja `PASS`. Aprovar um modelo tira o anterior de `APPROVED` e o deixa `VALIDATED`.

A validação também confere a leitura do arquivo, a igualdade das características e, no fluxo do painel, a comparação com a versão anterior quando ela existe. A primeira importação não tem versão anterior.

## 15. Por que a aprovação humana é importante

O modelo pode acertar o gerador e errar uma compra real. O otimizador pode reduzir falso positivo sem reduzir falso negativo, como aconteceu aqui. Uma publicação automática transformaria esse acerto de laboratório em regra da loja.

Por isso:

- analista cria rascunho e envia; não publica;
- administrador aprova e publica política;
- administrador aprova modelo de operação só se a validação passou;
- administrador pode aprovar um modelo contextual com validação `RECORDED` exclusivamente para sombra; a meta de aceite não é declarada como cumprida e o modelo de operação aprovado permanece;
- restaurar uma política antiga também é ato de administrador e gera versão nova;
- pesos com piso de segurança (destino em incidente, verificação pedida, eco de roteiro) não podem ser zerados;
- o limiar de bloqueio não pode passar de 80 para um número mais frouxo.

## 16. Limitações dos resultados

- Os rótulos foram inventados pelo gerador. Precisão 1,00 no teste não mede fraude de banco.
- Os roteiros se separam com facilidade nas 19 características. O empate perfeito é esperado nesse desenho e não deve ser citado como eficácia do ContextShield.
- Respostas da verificação não entram no vetor. A fraude discreta fica de fora das métricas do modelo de propósito.
- Não houve resultado confirmado por analista neste treino (`confirmedInTrain: 0`).
- O otimizador não reduziu falso negativo (continuou 15 nos dois conjuntos).
- A sombra não altera a decisão e, sem aprovação, não produz previsão.
- Os perfis marketplace e e-commerce não isolam empresas.
- O aviso de threads do Smile no fim do `exec:java` não invalida os arquivos, mas o processo demora uns 15 s a mais para encerrar.

## Critérios que o código não transforma em parâmetro

Continuam no Java, iguais ao motor anterior quando a política está vazia: detecção dos sinais, desconto de dependência (metade dos pontos), confiança das fontes, ordem R1–R11, faixas padrão 30 / 55 / 80, score-base 22 e o comportamento de falha (motor fora deixa a compra em análise, sem cobrança). A regra R12 só existe quando a flag `context-when-unknown` está ligada. Por padrão ela fica desligada e o score não muda.

## 17. Segunda avaliação, 24/11/2026

Os 19 atributos da primeira avaliação não separam uma fraude discreta de uma compra habitual feita com a mesma semente. Em 40 pares, os vetores ficaram idênticos. As respostas não entram nesse vetor. Trocar o algoritmo, inclusive por Gradient Boosting, não resolve pares idênticos. O Smile 3.1.1 também não foi estendido com outro modelo.

A segunda avaliação acrescenta seis campos, só depois da resposta: pedido declarado, contato declarado, endereço passado, orientação declarada, resposta ausente e contradição. Um zero com resposta ausente não significa negação. Os hiperparâmetros continuam os da primeira avaliação. O conjunto reservado (semente 9901, 400 operações) não entra no treino nem na ordem dos modelos. Nenhum modelo contextual foi promovido.

Desenvolvimento: 800 operações, semente 8801, 480 treino, 160 validação, 160 teste. Na validação, só com a operação, a logística ficou com F1 0,5676 e a floresta com 0,2680. Com as respostas, as duas ficaram com F1 0,8205, precisão 1,0000 e 21 falsos negativos. No teste do desenvolvimento, o F1 com respostas foi 0,8200 e os falsos negativos foram 18.

No conjunto reservado, só com a operação, a logística teve F1 0,5682 (56 falsos negativos) e a floresta 0,4098 (108). Com as respostas, as duas tiveram precisão 1,0000, recall 0,6772, F1 0,8075 e 51 falsos negativos. Os 51 são o roteiro `fraude-que-nega`: a pessoa mente e o vetor fica igual ao de uma compra lícita. A orientação declarada foi capturada. Isso continua sendo dado sintético.

No mesmo conjunto, a política padrão liberou as 158 fraudes, porque a compra habitual não pede verificação. Quando as respostas foram aplicadas só na comparação, 107 fraudes foram retidas e 51 continuaram liberadas. A flag que pede verificação nesse caso nasce desligada, para a política padrão não mudar.

Os arquivos são `models/logistic-context.ser`, `models/random-forest-context.ser` e `reports/context-study.json`. O relatório da semente 42 não foi substituído. Os dois arquivos contextuais foram relidos. O conjunto reservado não foi usado para escolher modelo.

Um administrador pode aprovar o modelo contextual só para sombra. A validação continua `RECORDED`, `decisionAuthorized` fica falso e o modelo de operação aprovado não é substituído. Na compra `TX-2026-000205`, a sombra inicial estimou `LEGIT` enquanto o motor pedia verificação. Depois das respostas, a logística de operação continuou em `LEGIT` e a logística contextual estimou `FRAUD`. O motor decidiu `INTERVENE` pela regra R2. Nenhuma previsão alterou a compra. Os 51 falsos negativos do conjunto reservado, o roteiro `fraude-que-nega`, continuam sem métrica melhor: não houve dado novo para retreinar.

## 18. Diagnóstico dos 51 falsos negativos, 24/11/2026

O relatório `reports/false-negative-diagnosis.json` releu `logistic-context.ser` e o conjunto da semente 9901. Não houve treino, corte novo nem gravação de modelo. As 400 operações e as 158 fraudes batem com `context-study.json`. Os 51 erros são as 51 operações do roteiro `fraude-que-nega`.

As 51 têm as mesmas quatro respostas de uma compra habitual lícita: origem própria, sem contato, endereço meu, sem orientação. Os 16 bits da operação também coincidem com alguma compra habitual do mesmo conjunto. A distância mediana até a compra habitual mais próxima é 0,0166; a distância mediana entre as próprias compras habituais é 0,0180. A probabilidade de fraude fica em 0,328–0,408 nas negações e em 0,327–0,405 nas compras habituais.

Um corte de 0,40 marcaria 9 negações e 6 compras habituais. Um corte de 0,30 marcaria as 51 dos dois lados. Nenhum corte foi adotado. A semente 12011, com 400 operações e 72 negações, foi lida uma vez e não entrou em ajuste: as 72 também coincidem com compras habituais, e o corte 0,40 marcaria 5 negações e 8 compras habituais.

Antes das respostas, o motor aprova as 51 com score 6. Depois da negativa, o score vai a 0 e a regra vira S-R8, alerta por fragilidade. As 51 compras habituais com as mesmas respostas também ficam em alerta. O alerta não separa as classes.

Falta, em todos esses casos, um registro observado: texto da mensagem, incidente de destino ou de vendedor, aparelho novo, destino de terceiro, ou uma resposta que admita ajuda. Sem um desses registros, a negativa e a compra habitual são a mesma observação.
