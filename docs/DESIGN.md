# Design e movimento

Três produtos, três identidades, uma linguagem comum. A loja precisa parecer uma loja de verdade, o app precisa parecer um banco, e o painel precisa parecer uma ferramenta de trabalho. O que une os três é a família de ícones e o vocabulário de movimento.

## As três telas

| Produto | Papel na história | Identidade |
|---|---|---|
| **Loja NEXA** (`/loja`) | Onde a compra acontece | Ônix e platina, azul de ação `#0066db`. Fonte Urbanist. Vitrine inspirada em e-commerce editorial: herói em tela cheia, categorias em círculos, wordmark gigante no rodapé |
| **App Aureon** (`/banco`) | O banco da cliente, no celular | Petróleo `#0e4f4f`, dourado `#e2b552` e papel `#f6f4ee`. Fonte Onest. No computador aparece dentro de um celular; abaixo de 520 px ocupa a tela inteira |
| **Painel de operações** (`/ops`) | O analista de fraude | Barra lateral escura `#0c1117` com destaque lima `#c8f031`, conteúdo claro e denso. Fontes Geist e Geist Mono |

O banco é fictício (Aureon) de propósito: nada de nome, logo ou cor de banco real.

## Ícones próprios

`frontend/assets/js/icons-data.js` tem mais de 70 ícones desenhados para o projeto, sem biblioteca externa. `frontend/assets/js/utils/icons.js` monta o SVG. Todos usam grade de 24 e traço de 1.6 com pontas redondas. A assinatura é o **traço de contexto**: em cada ícone, uma parte (classe `.ac`) é a que acende quando o estado muda, como o sininho com aviso, o escudo que confirma ou a lente que confere.

## Movimento

Os tokens ficam em `frontend/assets/css/base.css`:

| Token | Valor | Uso |
|---|---|---|
| `--t-press` | 120 ms | Resposta ao toque (escala 0.97) |
| `--t-fast` | 160 ms | Hover, foco, troca de cor |
| `--t` | 220 ms | Abrir e fechar elementos pequenos |
| `--t-slow` | 320 ms | Gavetas, folhas e transições de página |
| `--ease-out` / `--ease-arrive` | curvas de saída | Tudo que entra na tela |
| `--ease-drawer` | curva de gaveta | Carrinho, folha do app, menu |

Regras seguidas:

- O movimento explica algo. A página do app desliza para a frente ou para trás conforme a navegação, o anel do score preenche até o valor, o grafo de decisão se desenha da fonte até a decisão e o carrinho mostra o item voando até ele.
- Nenhuma espera é falsa. O que aparece rápido aparece rápido, e esqueletos só surgem quando há uma chamada de verdade em andamento.
- Só se animam `transform` e `opacity`, nunca layout.
- Com `prefers-reduced-motion`, as animações viram troca de opacidade ou somem.

## Navegação

- **Painel:** `Ctrl K` / `⌘ K` abre a busca, que leva a qualquer compra, relato ou tela. Nas tabelas, `J` e `K` andam pelas linhas e `Enter` abre a selecionada. A barra lateral recolhe e vira trilho, e no celular vira gaveta.
- **App:** abas fixas embaixo (Início, Compras, Confira, Fui vítima) e voltar sempre no canto superior esquerdo.
- **Loja:** busca com `/`, carrinho em gaveta e checkout em três passos com progresso visível.

## Acessibilidade

`npm run test:a11y` passa o axe-core (WCAG 2 A/AA e boas práticas) em 19 telas, com zero violações. O contraste é medido depois que as animações de entrada terminam. Todo controle tem foco visível e nome acessível, e os grafos podem ser navegados pelas setas do teclado.
