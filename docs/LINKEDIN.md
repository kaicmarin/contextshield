# Posts do LinkedIn

A A3 pede um post por checkpoint, marcando a professora e a Anhembi Morumbi. Rascunhos abaixo, para cada um postar no próprio perfil (ajustem a voz). Troquem `@Professora` e `@Anhembi Morumbi` pelas marcações reais.

---

## Checkpoint 1 · ideia e problema

Uma compra pode ser legítima e, mesmo assim, não ser da vontade de quem paga.

No golpe da falsa central, alguém liga dizendo ser do banco, fala que o cartão foi clonado e pede uma “compra de validação” para cancelar a fraude. A vítima compra no próprio celular, com a própria senha, e entrega num endereço que o golpista passou. Para o sistema antifraude, é uma compra perfeita.

No projeto A3 de Sistemas Distribuídos, eu, Eric e Thiago estamos construindo o **ContextShield**: um serviço que olha o **contexto** da compra (como ela começou, para onde vai, se foge do hábito) e, em vez de só aprovar ou negar, pode avisar, perguntar ou pausar.

Próximo passo: definir a arquitetura em microsserviços.

@Professora @Anhembi Morumbi
#SistemasDistribuidos #Seguranca #Java #SpringBoot #Fraude

---

## Checkpoint 2 · arquitetura e protótipo

Checkpoint 2 do ContextShield: a arquitetura saiu do papel.

🔹 **Motor de risco** em Spring Boot, sem estado: recebe o contexto de uma compra e devolve a decisão com explicação. 31 sinais, 11 regras e um “envelope de segurança” que só pode endurecer a decisão, nunca afrouxar.
🔹 **API principal** em Spring Boot com PostgreSQL e JWT: usuários, checkout, relatos de golpe e auditoria.
🔹 **Três interfaces** em HTML, CSS e JavaScript: a loja, o app do banco e o painel do analista.

A decisão de que mais gostamos: se o motor cair, a compra **não** é aprovada. Fica em análise. Um antifraude que aprova quando falha é pior que nenhum.

@Professora @Anhembi Morumbi
#Microsservicos #SpringBoot #PostgreSQL #JWT

---

## Checkpoint 3 · testes

Checkpoint 3 do ContextShield: testar o que dissemos que funciona.

✅ 76 testes automatizados, incluindo 43 que provam que o motor em Java decide exatamente igual ao protótipo
✅ Teste de carga: com 50 usuários simultâneos, 95% das respostas em menos de 364 ms
✅ 0 violações de acessibilidade (WCAG 2 AA) em 19 telas

O melhor momento foi o teste de carga **falhar quase no limite**: a primeira rodada deu 1,08 s no pico. Faltavam índices no banco. Com a correção, o tempo caiu para um terço e a vazão subiu 50%. Teste de desempenho não é para provar que está bom, é para achar onde não está.

@Professora @Anhembi Morumbi
#Testes #Desempenho #Acessibilidade #QualidadeDeSoftware

---

## Entrega final

Apresentamos o ContextShield, nosso projeto A3 de Sistemas Distribuídos.

A cena que resume o projeto: a Ana cai no golpe da falsa central, o sistema pausa a compra e ela cancela. Ela registra o golpe no app do banco. Minutos depois, a Mariana, outra cliente, é guiada por outro golpista para o mesmo ponto de retirada. A compra dela é bloqueada na hora. **O relato de uma pessoa protegeu a outra.**

Construído com Java 21, Spring Boot, PostgreSQL, JWT, HTML, CSS, JavaScript, Docker e deploy na nuvem.

Obrigado, @Professora, pela orientação, e aos colegas Eric e Thiago pela parceria.

@Anhembi Morumbi
#SistemasDistribuidos #Java #SpringBoot #Microsservicos #Fraude #Seguranca
