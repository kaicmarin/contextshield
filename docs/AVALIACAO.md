# Avaliação do projeto: original × nova versão

Escrito para o Bruno, Eric e Thiago decidirem o rumo antes da entrega de 29/11. Sem rodeio: a ideia é boa, a execução original não atende a A3, e esta versão resolve isso mantendo a lógica.

## 1. O objetivo real

Não é “fazer um antifraude”. É **passar na A3 com nota alta e com três pessoas capazes de defender o projeto oralmente**. A orientação avalia:

1. Aplicação Java com REST, em microsserviços.
2. Interface com conceitos de usabilidade.
3. Banco relacional ou Mongo **na nuvem**, aplicação **publicada**.
4. **JWT**.
5. Testes funcionais (CRUD de usuários, transações e alertas; integridade; endpoints válidos e inválidos) e não funcionais (usabilidade das mensagens; tempo de resposta em carga normal e de pico).
6. GitHub; Docker é desejável.
7. Relatório em formato de artigo, slides, apresentação de 20 minutos e posts no LinkedIn.

Tudo que não serve a essa lista é custo.

## 2. O projeto original (o .zip)

### Pontos fortes
- **O tema é ótimo e pouco explorado.** Golpe em que a vítima compra por conta própria é real, grave e difícil. Chama atenção da banca.
- **A lógica do motor é sofisticada e coerente**: sinais com peso e confiança, desconto de evidências dependentes, regras R1–R11, envelope de segurança que só endurece, certificado de robustez, perguntas de verificação, relatos que viram inteligência para proteger outros clientes. É a parte mais valiosa e foi mantida **100%**.
- A história da demonstração (Ana, Mariana, o ponto de retirada) é boa para apresentar.

### Pontos fracos
| Problema | Por que pesa na A3 |
|---|---|
| **O motor roda no navegador, em JavaScript.** O Spring Boot só guarda dados | O critério central é “aplicação Java com REST”. A parte inteligente não está no Java. E no navegador o cliente pode ler e alterar a regra |
| **Banco H2 em memória** | A A3 pede banco na nuvem; H2 apaga tudo ao reiniciar |
| **Sem JWT, sem deploy, sem Docker** | Três itens explícitos da orientação |
| **Sem teste de desempenho, sem CRUD completo testado** | Dois blocos inteiros da rubrica de testes |
| **Um serviço só** | A A3 fala em microsserviços |
| **Escopo explodiu**: Pix, boleto, investimentos, “Chaos Lab”, mais de 11 mil linhas de front | Nada disso é pedido. Cada tela extra é algo que alguém precisa saber explicar |
| **Código com cara de gerado de uma vez** (poucos commits, numa noite; pasta de ferramenta de IA no histórico do git) | A oral é **individual**. Se a banca perguntar “por que essa linha?” e ninguém souber, a nota cai para todos. E histórico de commits concentrado é exatamente o que professor olha |

### Custos escondidos
- Tempo gasto polindo telas que não contam ponto, enquanto JWT, nuvem e testes (que contam) não existem.
- Dependência de uma pessoa que “sabe onde está tudo”.

## 3. Quem já resolveu algo parecido

| Quem | O que faz | O que aproveitamos |
|---|---|---|
| **Bancos do Reino Unido (Confirmation of Payee + modelo de reembolso APP)** | Antes de pagar, o app pergunta o motivo do pagamento e mostra avisos específicos para cada tipo de golpe | Perguntar o contexto no checkout e mostrar aviso sob medida, não genérico |
| **Nubank, Itaú, Inter (Brasil)** | “Modo rua”, limite noturno, pausa de Pix suspeito com ligação de confirmação | Respostas intermediárias (pausar, confirmar) em vez de só aprovar/negar |
| **Sardine, BioCatch, Feedzai** | Detectam sinais comportamentais de quem está sendo guiado (hesitação, chamada ativa durante a sessão, cópia e cola) | Inspiração para os sinais de “orientação”; ficam como próximo passo, porque exigem SDK no aparelho |
| **Stripe Radar** | Regras explicáveis + score, com o motivo visível para o lojista | A explicação de cada decisão no painel |
| **Golpe.org / bases de denúncia** | Base colaborativa de telefones e links usados em golpes | Relatos viram inteligência que protege o próximo cliente (cenário Ana → Mariana) |

O que **não** funcionou no mercado: bloquear tudo que parece estranho (clientes abandonam o banco) e avisos genéricos (“cuidado com golpes”), que ninguém lê. Por isso o motor tem cinco saídas e as mensagens citam o motivo concreto.

## 4. A nova versão (esta)

Mesma lógica, mesma história, arquitetura que atende a A3 item por item.

| Requisito | Original | Esta versão |
|---|---|---|
| Java + REST + microsserviços | Spring só guarda dados; motor em JS | **2 microsserviços Spring Boot**: motor de risco (sem estado) e API principal |
| Banco na nuvem | H2 em memória | **PostgreSQL** com Flyway; blueprint do Render pronto |
| JWT | Não | **JWT com papéis** (cliente, analista, admin) + token de serviço entre microsserviços |
| Docker | Não | Dockerfile por serviço + `docker compose up` (testado) |
| CRUD e integridade testados | Parcial | **76 testes**, incluindo restrições do banco |
| Endpoints válidos/inválidos | Parcial | 400 com campos, 401, 403, 404, 409 testados |
| Desempenho | Não | Teste de carga com números (p95 57 ms normal, 364 ms pico) e uma otimização real encontrada pelo teste |
| Usabilidade | Não medida | 0 violações de acessibilidade em 19 telas + avaliação heurística + roteiro com usuários |
| Motor igual ao original | — | **43 de 43** casos idênticos ao protótipo |
| Tamanho | ~11 mil linhas de front | ~5,9 mil de front, ~5,3 mil de Java, ~0,7 mil de teste |

Cortado de propósito: Pix, boleto, investimentos, Chaos Lab. Se a banca perguntar, a resposta é “o motor recebe qualquer pagamento com contexto; escolhemos cartão para aprofundar”.

## 5. Uma versão alternativa (mais simples)

Se o grupo achar que não dá para dominar dois microsserviços até 29/11:

- **Um serviço Spring Boot** com o motor dentro (pacote separado), PostgreSQL, JWT.
- **Só duas telas**: checkout e painel.
- Mesmo conjunto de testes.

Atende a A3 “no mínimo” (perde o ponto de microsserviços) e é muito mais fácil de explicar. **Não recomendo**, porque a separação já está pronta e testada, mas é o plano B se faltar tempo para estudar.

## 6. O que eu faria se o projeto fosse meu

1. **Adotar esta versão como a oficial** e subir para um repositório novo no GitHub, com cada um fazendo commits da parte que vai apresentar (ver divisão em `DEMO.md`). O histórico precisa mostrar trabalho dos três.
2. **Cada um estudar a sua parte a ponto de alterar algo ao vivo.** Teste bom: mudar o peso de um sinal no motor, rodar `mvn test` e explicar por que o `ParityTest` quebrou.
3. **Deploy no Render entre 03/11 e 07/11** (o banco gratuito expira 30 dias depois de criado; em 30/10 ele morreria no dia da apresentação) e rodar o teste de carga **na nuvem** para o relatório. Antes de apresentar, acordar os serviços (`DEMO.md`).
4. **Fazer o teste com 5 usuários** (roteiro em `TESTES.md`). É barato e é o que diferencia “dizemos que é usável” de “medimos”.
5. **No relatório**, vender a ideia que ninguém mais vai ter: *a compra é legítima, mas não é da vontade de quem paga*. E o efeito de rede: o relato da Ana bloqueia o golpe na Mariana.

### Ideias que valem mais do que mais telas
- **Sinal de chamada ativa**: no celular, o app sabe se há uma ligação em curso durante a compra. É o sinal mais forte de golpe guiado e é usado por bancos do Reino Unido. Fica como trabalho futuro no artigo.
- **“Pausa de 1 hora”** para compras de alto valor com contexto suspeito: o golpista perde o controle da vítima quando ela desliga o telefone.
- **API para outros bancos**: o motor sem estado é um produto B2B (é como Sardine e Feedzai ganham dinheiro). Bom para o slide de “modelo de negócio”.

## 7. Próximos passos (em ordem)

| Quando | O quê | Quem |
|---|---|---|
| Esta semana | Ler o README e rodar `docker compose up` cada um na sua máquina | Todos |
| Esta semana | Criar o repositório no GitHub e fazer os commits por área | Todos |
| Até 30/10 | Cada um apresentar a sua parte para os outros dois (ensaio técnico) | Todos |
| 05/11 | Deploy no Render (não antes de 03/11, por causa da validade do banco) | Thiago |
| Até 07/11 | Teste com 5 usuários e SUS | Eric |
| Até 14/11 | Relatório (artigo) e slides | Bruno (texto), todos revisam |
| 20/11 | Ensaio cronometrado de 20 minutos | Todos |
| 29/11 | Apresentação | Todos |
