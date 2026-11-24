# Implantação no AWS Free Plan

Nada deste guia foi executado na AWS. Não há instância, certificado nem cobrança criada por este repositório.

## O que cabe em uma EC2

Cinco processos Java, o PostgreSQL e o Caddy sobem com `docker-compose.aws-free.yml`. As portas 8080–8084 e 5432 ficam só na rede Docker. O host publica 80 e 443.

Limites pedidos no compose, ainda sem medição de contêiner porque o Docker não estava disponível nesta máquina:

| Processo | Teto |
|---|---|
| Core API | 768 MB, heap 512 MB |
| Risk Engine | 384 MB, heap 256 MB |
| ML Service | 768 MB, heap 512 MB |
| Threat Intelligence | 384 MB, heap 256 MB |
| Investigation | 384 MB, heap 256 MB |
| PostgreSQL 16 | 512 MB |
| Caddy | 64 MB |

A soma dos tetos é cerca de 3,3 GB, mais o sistema. Cabe em 8 GB se os heaps forem respeitados. Isso é um limite configurado, não uma medição.

## Conta e créditos

A documentação da AWS (Free plan, consultada em 24/11/2026) diz que uma conta nova no Free plan recebe US$ 100 em créditos e pode ganhar até mais US$ 100. O plano acaba em seis meses ou quando os créditos acabam. A conta é encerrada e o conteúdo fica retido por 90 dias. Não faça upgrade para o plano pago.

O tipo `m7i-flex.large` (2 vCPU, 8 GB) está na lista de instâncias elegíveis do Free Tier de EC2. Um artigo do AWS Builder Center de 6 de maio de 2026 informa US$ 0,09576 por hora para essa instância, cerca de US$ 70 por mês se ela ficar ligada o tempo todo. Nesse ritmo, US$ 100 cobrem cerca de 43 dias só da instância, antes de disco e IP. Seis meses ligados o tempo todo passam de US$ 200. Desligue a EC2 quando não estiver demonstrando.

Não há, nesta consulta, um preço oficial de `m7i-flex.large` em `sa-east-1`. Confirme na calculadora da AWS antes de escolher São Paulo. `us-east-1` é a região do valor citado. Dados de demonstração na Virgínia ficam fora do Brasil.

Disco gp3 e IP público podem continuar consumindo crédito com a instância parada. O console de Billing mostra o saldo. A AWS avisa por e-mail em 50%, 25% e 10% dos créditos. Crie também um alerta de orçamento em zero dólar, sem contratar suporte pago.

## Publicar

1. Crie a conta no Free plan. Não ative o plano pago.
2. Crie um par de chaves e uma EC2 `m7i-flex.large`, Amazon Linux, disco gp3 de 30 GB.
3. Security group: 22 só do seu IP, 80 e 443 para a demonstração. Não abra 5432 nem 8080–8084.
4. Entre por SSH com o usuário da AMI, não com a conta root da AWS.
5. Instale Docker e o plugin Compose pelos pacotes da distribuição.
6. Copie o repositório para a instância. Não copie `.env` de desenvolvimento para um repositório público.
7. Crie o `.env` a partir de `.env.example`, com senhas novas e `JWT_SECRET` de pelo menos 32 caracteres, diferente do valor de desenvolvimento.
8. `docker compose -f docker-compose.aws-free.yml up -d --build`
9. `docker compose -f docker-compose.aws-free.yml ps` e os health checks.

O navegador usa o mesmo host do proxy. O frontend chama `/api` no mesmo endereço. O Operations continua exigindo login de analista. Esconder a URL não substitui essa regra.

HTTPS automático do Caddy só existe depois de um domínio em `PUBLIC_HOST` e das portas 80 e 443 alcançáveis. Sem esse teste, o HTTPS não está comprovado. Sem domínio, use `PUBLIC_HOST=localhost` só dentro da máquina, ou acesse por HTTP sabendo que a demonstração não está cifrada.

## Operar e desligar

- Saúde: `scripts/aws/health.ps1` na máquina local, ou `wget` em cada health interno na EC2.
- Backup, antes de desligar de vez: `docker compose -f docker-compose.aws-free.yml exec postgres pg_dump -U contextshield -Fc contextshield > backup.dump`
- Guarde o dump e a pasta `machine-learning/models` fora da EC2. A conta encerra sozinha.
- Parar sem apagar o disco: pare a instância no console da EC2. `scripts/aws/stop.ps1` só para os contêineres.
- Subir de novo: inicie a instância e `docker compose -f docker-compose.aws-free.yml up -d`.
- Apagar a demonstração: só com autorização explícita, encerre a instância e apague o volume EBS e o IP elástico, se houver.

Não há NAT Gateway, balanceador, EKS, Fargate nem RDS neste desenho. Cada um desses seria um custo à parte e não foi criado.
