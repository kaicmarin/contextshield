# Propostas ainda não implementadas

Estas duas etapas ficam como desenho. Não entram no protótipo desta entrega.

## Aureon como PWA no Android

O app já é HTML, CSS e JavaScript em `frontend/banco`. Dá para instalá-lo sem reescrever a interface.

Arquivos novos, quando a banca autorizar:

- `frontend/banco/manifest.webmanifest`: nome Aureon, cor `#0e4f4f`, ícone em `mark.svg`, `display: standalone`, `start_url: /banco`.
- `frontend/sw.js`: cache só de HTML, CSS, JS e ícones. Não cachear `POST /api/check` nem checkout. Se a rede falhar, mostrar a última tela estática e avisar que a análise não foi feita.
- Registro do service worker em `frontend/assets/js/bank.js`, só em HTTPS.
- Link do manifesto em `frontend/banco/index.html`.

Notificação push só faria sentido para o aviso que o analista já envia por `POST /api/ops/alerts`. Exige permissão do navegador e um serviço de push. Não é necessário para a demonstração.

Abertura: uma animação curta do escudo, respeitando `prefers-reduced-motion`. Som só depois de um toque da pessoa, porque o navegador bloqueia áudio automático.

## Serviço separado de inteligência

Não criar agora. Telefone, domínio, relato e revisão do analista já vivem na core-api (`IntelligenceService`, `PATCH /api/ops/intelligence/{id}`). O verificador e o motor de compra leem a mesma base, com regras diferentes.

Um terceiro serviço passaria a valer se a inteligência precisasse de fila própria, de outra equipe publicando indicadores, ou de escalar sem levar a loja junto. Até lá, separar só duplicaria o `CheckService` e o cruzamento que o checkout já faz.
