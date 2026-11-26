# Autenticação — senhas individuais (ContextShield V2)

## Como funcionava antes

- Login por e-mail + senha com BCrypt e JWT (`tokenVersion` para revogação).
- O `SeedService` aplicava **um único hash** (`DEMO_PASSWORD`, padrão `contexto`) em todos os clientes, analista e admin.
- Front-end e testes assumiam essa senha compartilhada (atalhos de login, E2E, scripts IT).

## Vulnerabilidades corrigidas

| Risco | Mitigação |
|--------|-----------|
| Senha única para todas as contas de demo | Hash BCrypt **por e-mail** via `SeedCredentialLoader` |
| Senha previsível no Git/env (`contexto`) | Sem default inseguro; arquivo `SEED_CREDENTIALS_FILE` obrigatório em banco vazio |
| Seed sobrescrever senhas ao reiniciar | Se já há clientes, `seedReference()` **só ajusta** `shiftDays` e retorna |
| Sem troca de senha pelo usuário | `POST /api/me/password` com limite de tentativas, auditoria, `tokenVersion++` |
| Admin redefine senha sem invalidar sessões | `PUT /api/admin/users/{id}` com senha → `bumpTokenVersion()` + evento `PASSWORD_RESET` |
| Política fraca (8 caracteres) | Mínimo **12** caracteres (cadastro, admin, troca de senha) |
| Credenciais em respostas/listagens | `UserView` nunca inclui senha; mensagens genéricas no login |

## Arquivos principais alterados

**Backend (core-api):** `SeedService`, `SeedCredentialLoader`, `PasswordPolicy`, `PasswordChangeLimiter`, `AuthController`, `AdminController`, `AppProperties`, `application*.yml`, `seed/credentials.example.json`, `test-resources/seed/test-credentials.json`, `PasswordSecurityTest`, `TestSupport`, `AuthApiTest`.

**Frontend:** `demo-seed-passwords.js`, `ui.js` (login + alterar senha), `api/client.js`, `bank.js`, `store.js`, `ops.js`, `store-flow.js`.

**DevOps/scripts:** `docker-compose.yml`, `Get-DemoPassword.ps1`, `smoke-full.ps1`, `prove.ps1`, `it.env.example`, `perf/load.mjs`, `.gitignore`.

## Endpoints

| Método | Caminho | Descrição |
|--------|---------|-----------|
| POST | `/api/me/password` | Troca senha (atual, nova, confirmação); retorna novo JWT |
| POST | `/api/auth/login` | Inalterado (sem expor senhas) |
| PUT | `/api/admin/users/{id}` | Redefinição admin com invalidação de tokens |

## Testes automatizados

Classe `PasswordSecurityTest` cobre os requisitos 1–9 da especificação. Suite Maven `core-api` deve ser executada com:

`mvn -pl services/core-api test`

(O trabalho desta entrega rodou os testes localmente; confirme na sua máquina antes de publicar.)

## Compatibilidade com usuários existentes (AWS / IT)

- **`SEED_ENABLED=false`:** nenhuma conta é recriada nem senha alterada.
- **Banco já populado:** o seed **não** atualiza `password_hash`.
- Contas que ainda usam a senha antiga compartilhada continuam válidas até o usuário ou um admin trocar a senha.
- **Novo ambiente vazio:** exige `SEED_CREDENTIALS_FILE` apontando para JSON com uma senha por e-mail (ver `credentials.example.json`).

## Publicação segura na AWS

1. Manter `SEED_ENABLED=false` em produção (`application-prod.yml` já prevê isso).
2. **Não** executar migrações destrutivas nem reset de usuários no RDS.
3. Distribuir senhas iniciais/redefinições por canal seguro (não e-mail em texto puro).
4. Opcional: exigir troca de senha no primeiro login (não implementado nesta entrega).
5. Montar `seed-credentials` apenas em ambientes de laboratório; nunca commitar o arquivo real.

## UI — alterar senha

- **NEXA:** `/loja/conta/senha` (link no topo quando logado)
- **Aureon:** `/banco/senha` (link na home)
- **Operations:** `/ops/conta/senha` (ícone de cadeado na barra lateral)

## Pendências / riscos

- Bancos IT/local ainda com hash de `contexto` precisam de `it.env`/`SEED_CREDENTIALS_FILE` coerente com o que está no PG, ou redefinição manual por admin.
- Fluxo de “esqueci minha senha” por e-mail não foi implementado (sem envio de senha em claro).
- `demo-seed-passwords.js` e `test-credentials.json` existem **só para lab/CI**; não refletem produção AWS.

*Relatório sem valores reais de senha, conforme solicitado.*