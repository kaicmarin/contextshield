# Roteiro de demonstração

Contas: cliente `ana.martins@correio.example`, analista `analista@contextshield.example`, administrador `admin@contextshield.example`. Senha de demonstração `contexto`. Nada aqui movimenta dinheiro.

A flag `context-when-unknown` nasce desligada. Com ela desligada, a compra habitual segue aprovada, como antes.

## A — Compra legítima

No NEXA, Ana compra o Mouse Glide (`prod-010`) para o endereço de casa, no aparelho conhecido, e declara que encontrou sozinha. A Core API grava a compra, o motor devolve score baixo e a decisão `APPROVE`. O modelo em sombra, se houver um aprovado, registra a previsão e `affectsDecision` fica falso.

## B — Fraude evidente

Uma compra declarada como vinda de link ou indicação pede verificação (R6). Se a resposta disser que alguém pediu, houve contato e orientou o questionário, o motor intervém (R2 ou R9). O bloqueio por score (`R1`) só aparece quando o score chega a 80. Não apresente bloqueio se a tela mostrar pausa.

## C — Fraude discreta

A compra tem a cara da compra habitual e a indução só existe na conversa. Com a política padrão, o motor aprova. Os 19 atributos do modelo inicial ficam iguais aos de uma compra lícita gerada com a mesma semente: 40 pares idênticos no diagnóstico. O modelo não detecta esse caso. Diga isso na apresentação.

## D — Verificação contextual

Um administrador liga `context-when-unknown` em Motor de risco. A mesma compra habitual passa a pedir verificação (R12) sem mudar o score. As perguntas deixam claro que a resposta é declarada. Se o prazo de 30 minutos vencer, a compra continua em `CONTEXT_REQUIRED`. A falta de resposta não aprova.

## E — Modelo e motor

Abra a compra no Operations. A decisão exibida é a do motor. A previsão da sombra, quando existe, aparece ao lado e não aprova nem bloqueia. Em Modelos, as contagens são operacionais. Precisão e recall não aparecem sem rótulos revisados.

## F — Pesos

O laboratório mostra a sugestão da busca. Ela não está publicada. Se o falso positivo caiu e o falso negativo não, o texto diz isso. Os pisos de segurança continuam dentro dos limites.

## G — Publicar e reverter

O analista cria um rascunho e não consegue publicar. O administrador aprova, publica e pode restaurar a versão anterior. A publicação gera outra versão. A política vazia reproduz o motor original.

## H — Outra organização

O analista da organização `market-demo` não lê a compra, a fila, o relato nem a inteligência da `nexa-demo`: a lista vem vazia ou a consulta responde 404. O identificador da empresa sai do usuário autenticado. Catálogo da loja, visão geral e auditoria ainda não estão isolados. Isso não é multiempresa comercial.
