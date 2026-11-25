# UI IT — navegador (2026-11-24)

Frontend: `http://127.0.0.1:5173` → API `http://127.0.0.1:18080`

| Área | Resultado |
|---|---|
| NEXA (`/loja`) | OK — catálogo com produtos após login Ana |
| Aureon (`/banco`) | OK — home logada (sessão cliente compartilhada) |
| Operations transações | OK — tabela com linhas |
| Operations inteligência | OK — seção «Indicadores confirmados» |
| Operations modelos (ML) | OK — cabeçalho de modelos publicados |
| Operations casos (investigações) | OK — lista de casos ou estado vazio |

Comando: `BASE=http://127.0.0.1:5173 node frontend/scripts/ui-it.mjs`