# Password IT 2026-11-26 01:27

| ID | Teste | OK | Detalhe |
|---|---|---|---|
| 0.1 | Login admin IT | True | HTTP 200 |
| 0.2 | Migração IT para senhas individuais | True | contas atualizadas=0 |
| 1.1 | Senhas de arquivo são diferentes | True | cliente vs admin |
| 1.2 | Login cliente e admin | True | HTTP 200/200 |
| 1.3 | Senha admin não autentica cliente | True | HTTP 401 |
| 2 | Troca de senha API | True | HTTP 200 |
| 3.1 | Token antigo invalidado | True | HTTP 401 |
| 3.2 | Senha antiga rejeitada / nova aceita | True | old=401 new=200 |
| 4 | Admin redefine senha | True | HTTP 200 |
| 4.1 | Token antigo após reset | True | overview=401 relogin=200 |
| 5 | Perfis CLIENTE/ANALYST/ADMIN | True | cli=403 ana_admin=403 ana_cfg=403 adm_cfg=200 |
| 6 | Login JSON sem senha | True | comprimento=434 |
| 7 | Usuários ativos preservados | True | active=37 |
