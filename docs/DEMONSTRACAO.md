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

A flag `context-when-unknown` continua desligada. Ligada, ela não interrompe toda compra habitual. R12 só pede verificação quando já existe um sinal de atenção calculado, como horário atípico, aparelho novo ou anúncio. O score não muda. Há teto de frequência (`context-probe-max`, padrão 1 em 24 horas). Uma simulação em `GET /api/ops/engine/context-probe` conta o efeito e não grava a compra. Se o prazo de 30 minutos vencer, a compra continua em `CONTEXT_REQUIRED`. A falta de resposta não aprova.

Uma negativa completa, sem roteiro ou relato independente, deixa a verificação em `INCONCLUSIVE`. A resposta é declarada. O motor ainda aplica as regras que já existiam, inclusive `VER_CLEAR`, e pode liberar com alerta. Isso não confirma a negativa.

## E — Modelo e motor

Abra a compra no Operations. A decisão exibida é a do motor. A sombra mostra o estágio, o identificador do modelo, o algoritmo, a versão da biblioteca, a previsão e a explicação. `affectsDecision` fica falso. O modelo contextual só entra nessa lista depois de uma aprovação explícita para sombra. Essa aprovação não exige a meta de aceite e não autoriza o modelo a decidir. Em Modelos, as contagens são operacionais. Precisão e recall não aparecem sem rótulos revisados.

## F — Pesos

O laboratório mostra a sugestão da busca. Ela não está publicada. Se o falso positivo caiu e o falso negativo não, o texto diz isso. Os pisos de segurança continuam dentro dos limites.

## G — Publicar e reverter

O analista cria um rascunho e não consegue publicar. O administrador aprova, publica e pode restaurar a versão anterior. A publicação gera outra versão. A política vazia reproduz o motor original.

## H — Outra organização

O analista da organização `market-demo` não lê a compra, a fila, o relato, a inteligência, a visão geral, a auditoria, a política fintech nem o modelo da `nexa-demo`. A lista vem vazia, a consulta responde 404, ou a auditoria mostra só os eventos da própria organização. Ele vê a política de marketplace, porque ela já pertence a essa organização. O catálogo da loja NEXA continua compartilhado. Clientes e aparelhos da demonstração também. Isso não é multiempresa comercial.
