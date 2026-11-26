# UI IT — 2026-11-25 04:40

Ambiente: PostgreSQL `5433`, Core `:18080`, frontend `server.mjs` `:5173` (`API_URL=http://127.0.0.1:18080`).

| Área | Resultado |
|------|-----------|
| NEXA (login Ana + catálogo `.nx-card`) | OK |
| Aureon (home logada) | OK |
| Operations — transações | OK |
| Operations — inteligência | OK |
| Operations — modelos ML | OK |
| Operations — investigações | OK |

Comando: `BASE=http://127.0.0.1:5173 node frontend/scripts/ui-it.mjs`