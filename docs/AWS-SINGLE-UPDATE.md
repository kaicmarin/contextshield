# Atualização única na EC2 (V2 completo)

Um comando após enviar o código para a instância (sem reset de volume):

```bash
cd ~/contextshield-v2   # ou o caminho do deploy
cp -n .env.example .env  # se ainda não existir
# Confirme: SEED_ENABLED=false, JWT_SECRET, CORE_DB_PASSWORD, DB_PASSWORD

chmod +x scripts/aws/deploy-app-update.sh
./scripts/aws/deploy-app-update.sh
```

O script:

1. Faz **backup gzip** em `backups/contextshield-*.sql.gz` (volume `pgdata` intacto).
2. **Build** de core-api, proxy, risk-engine, ml-service, threat-service, investigation-service.
3. **Sobe** os serviços; Flyway na core aplica **V12–V14** (regras dinâmicas, laboratório ML, `started_at` em experimentos).
4. Verifica **health** do core (8080) e microsserviços (8081–8084).
5. Imprime a versão Flyway máxima.

## Rollback (somente manual — nunca automático)

O `deploy-app-update.sh` **só cria** backup; **não** restaura dump sobre o volume em execução.

Use restauração SQL **apenas** se houver incidente grave (migração falhou, dados corrompidos) **e** você aceita perder transações posteriores ao timestamp do arquivo escolhido:

1. Pare os apps (mantenha o volume): `docker compose -f docker-compose.aws-free.yml stop core-api proxy risk-engine ml-service threat-service investigation-service`
2. **Em janela aprovada**, restaure um backup **anterior ao problema** (não o mais recente se ele já contiver o erro):  
   `gunzip -c backups/contextshield-YYYYMMDD-HHMMSS.sql.gz | docker compose -f docker-compose.aws-free.yml exec -T postgres psql -U contextshield contextshield`
3. Suba imagem/commit anterior: `docker compose up -d`.

Arquivos `machine-learning/models/*.ser`, volume `pgdata` e `mlwork` **não são apagados** pelo deploy; rollback de modelo = voltar commit + reaprovar na UI.

## Teste Docker no GitHub Actions

1. Push na branch `main` ou abra um PR → job **`deploy-isolated`** na aba Actions.
2. Ou: **Actions → CI → Run workflow** (`workflow_dispatch`), branch `main`.
3. Artefato **`deploy-isolated-report`** contém `docs/DEPLOY-ISOLATED-TEST.json` (Flyway V12–V14, baseline de usuários/políticas, `autoRestore: false`).
4. O job `docker` usa variáveis descartáveis no workflow (não são segredos de produção).

## Não fazer na produção sem checklist

- [ ] `run-final-validation.ps1` verde no IT local (incl. E2E com Vite :5173).
- [ ] Job CI `deploy-isolated` verde no GitHub (`validate-deploy-isolated.sh`).
- [ ] `docs/ML-QUEUE-IT.log` com 3 experimentos **DONE** consecutivos.

Nenhum deploy automático é disparado por este documento.