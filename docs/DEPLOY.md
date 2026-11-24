# Deploy na nuvem

A A3 pede banco **na nuvem** e a aplicação publicada. O caminho mais curto e gratuito é o **Render**, que lê o `render.yaml` deste repositório e cria tudo de uma vez: o PostgreSQL gerenciado e os três serviços a partir dos Dockerfiles.

## Quando criar

O **PostgreSQL gratuito do Render expira 30 dias depois de criado** (depois disso há 14 dias para pagar antes de ser apagado, mas o banco já para de funcionar). Criado em 30/10, ele morreria exatamente em 29/11. Por isso:

- **Apliquem o Blueprint entre 03/11 e 07/11** (data planejada: **05/11**). O banco fica vivo até o começo de dezembro, com folga se a apresentação mudar de dia.
- Se precisarem da URL antes (por exemplo, para um checkpoint), criem quando quiserem e **recriem o banco** depois: apagar o `contextshield-db` e sincronizar o Blueprint de novo. O Flyway cria as tabelas e o seed carrega a história da demonstração sozinho, então recriar custa minutos.
- Ou usem um PostgreSQL que não expira (Neon, abaixo) e a data deixa de importar.

## Passo a passo (Render)

1. Suba este projeto para um repositório no GitHub.
2. Em [render.com](https://render.com), entre com a conta do GitHub.
3. **New → Blueprint** e escolha o repositório. O Render mostra o que vai criar:
   - `contextshield-db` (PostgreSQL 16)
   - `contextshield-risk-engine` (motor de risco)
   - `contextshield-core-api` (API principal)
   - `contextshield-web` (front + proxy da API)
4. O Render pede dois valores (`sync: false` no `render.yaml`):
   - `RISK_ENGINE_URL` na core-api: `https://contextshield-risk-engine.onrender.com`
   - `API_URL` no front: `https://contextshield-core-api.onrender.com`
5. Clique em **Apply**. O primeiro build leva alguns minutos (Maven nas APIs; a imagem do frontend só copia os arquivos estáticos).
6. **Confiram as URLs reais** na página de cada serviço. Se o nome já existia, o Render acrescenta um sufixo (`contextshield-core-api-x1y2.onrender.com`); nesse caso corrijam as duas variáveis em *Environment* e façam *Manual Deploy*.
7. Abra a URL do `contextshield-web`. As tabelas são criadas pelo Flyway e a história da demonstração é carregada sozinha na primeira subida.

O `JWT_SECRET` é gerado pelo Render e compartilhado entre o motor e a API. As credenciais do banco vêm do próprio banco gerenciado. Nenhum segredo fica no repositório.

### Por que URLs públicas entre os serviços

No plano gratuito, um serviço web **pode enviar** tráfego pela rede privada do Render (é assim que a API fala com o banco), mas **não pode receber**. Como o motor e a API também são serviços gratuitos, a API chama o motor pela URL pública (HTTPS) e o front faz o mesmo com a API. O token de serviço (JWT) protege o motor, então ele não fica aberto. Em plano pago, dá para trocar pelas URLs internas.

### Cuidados no plano gratuito

- **Serviços dormem** depois de 15 minutos sem tráfego, e cada um leva cerca de 1 minuto para acordar. O motor só recebe tráfego quando a API o chama: se ele estiver dormindo no meio da demonstração, a compra cai na falha segura (“em análise”) em vez de ser bloqueada. **Acordem os três serviços antes de apresentar** (comandos em `DEMO.md`).
- O motor está com `RISK_ENGINE_TIMEOUT_MS=5000` na nuvem (local é 2500), porque a chamada passa pela internet.
- Memória de 512 MB por serviço: o Dockerfile já limita a JVM a 75% da memória do container.

## Alternativa: banco no Neon ou Supabase

Se preferirem um PostgreSQL que não expira, crie o banco no [Neon](https://neon.tech) (gratuito) e configure na `contextshield-core-api`:

| Variável | Valor |
|---|---|
| `DATABASE_URL` | `jdbc:postgresql://<host>/<banco>?sslmode=require` |
| `DATABASE_USER` | usuário do Neon |
| `DATABASE_PASSWORD` | senha do Neon |

`DATABASE_URL` tem prioridade sobre `DB_HOST`, `DB_PORT` e `DB_NAME`.

## Variáveis de ambiente

### core-api

| Variável | Padrão | Para quê |
|---|---|---|
| `DATABASE_URL` | montada a partir de `DB_HOST`, `DB_PORT`, `DB_NAME` | JDBC do PostgreSQL. Obrigatório na publicação |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `contextshield` | Credenciais do banco. Trocar na publicação |
| `JWT_SECRET` | segredo de desenvolvimento | Assina os tokens da API e do motor. **Obrigatório trocar antes de publicar** (32+ caracteres, o mesmo valor nos dois serviços). O padrão só serve para a máquina local e gera um aviso na subida |
| `JWT_TTL_MINUTES` | `120` | Validade do login |
| `RISK_ENGINE_URL` | `http://localhost:8081` | URL do motor (no Render, a pública) |
| `RISK_ENGINE_TIMEOUT_MS` | `2500` (Render: `5000`) | Tempo máximo de espera pelo motor |
| `CORS_ORIGINS` | `localhost:5173` | Só necessário se o front estiver em outro domínio |
| `SEED_ENABLED` | `true` | Carrega a história da demonstração. Na publicação com dados reais, usar `false` |
| `DEMO_PASSWORD` | `contexto` | Senha das contas da demonstração. Não usar essa senha fora do ambiente local |
| `APP_TZ` | `America/Sao_Paulo` | Fuso usado nas datas |

### risk-engine

| Variável | Para quê |
|---|---|
| `JWT_SECRET` | O mesmo da core-api |
| `PORT` | Porta (padrão 8081) |

### frontend (nginx)

| Variável | Para quê |
|---|---|
| `API_URL` | URL da core-api com esquema (compose: `http://core-api:8080`; Render: a URL pública) |
| `PORT` | Porta do nginx (padrão 80) |

## Conferir depois do deploy

```bash
curl https://<seu-web>.onrender.com/api/engine/model | head -c 200     # motor respondendo pela API
API=https://<seu-web>.onrender.com DURATION=20 node perf/load.mjs       # desempenho na nuvem
cd frontend && BASE=https://<seu-web>.onrender.com npm run test:e2e     # história completa
```

Guardem os números do teste de carga **na nuvem** para o relatório: eles serão maiores que os locais (rede e plano gratuito), e explicar a diferença conta ponto.

O ambiente local desta máquina usa PostgreSQL 18.6. O Flyway que acompanha o Spring Boot 3.5.16 avisa que essa versão é mais nova do que a faixa testada por ele, cujo máximo declarado é o PostgreSQL 17. As migrações V1 a V9 foram aplicadas no banco existente e são aditivas: não apagam tabela. A V9 ficou em `success`. Para um piloto, use uma versão de PostgreSQL dentro da faixa declarada pelo Flyway, ou um Flyway que liste o PostgreSQL 18. O comando `docker` não está instalado nesta máquina, então o Compose não foi executado. Os testes locais usam H2 em modo PostgreSQL e não dependem do Docker.

A Core API, quando sobe só com `mvn -pl services/core-api spring-boot:run`, usa o jar de machine-learning instalado no repositório local. Depois de mudar esse módulo, rode `mvn -pl machine-learning -DskipTests install` antes de reiniciar a API. Sem isso, a sombra contextual pode registrar falha de catálogo e a compra segue com a decisão do motor.
