# Esboço do relatório (formato de artigo)

Estrutura sugerida no modelo SBC (até ~10 páginas). Cada seção traz o que escrever e de onde tirar o conteúdo neste repositório.

**Título:** ContextShield: avaliação de contexto em microsserviços para mitigar compras induzidas por engenharia social
**Autores:** Eric, Bruno, Thiago · Anhembi Morumbi · Sistemas Distribuídos e Mobile

## Resumo (150–200 palavras)
Problema (compra autorizada pela própria vítima sob orientação de terceiros) → proposta (avaliação de contexto com decisões graduais e explicáveis) → arquitetura (dois microsserviços Spring Boot, PostgreSQL, JWT, Docker, nuvem) → resultados (76 testes; p95 de 57 ms em carga normal e 364 ms em pico; 0 violações WCAG AA em 19 telas) → contribuição (relatos de vítimas como sinal para proteger outros clientes).

## 1. Introdução
- Golpes de engenharia social com pagamento autorizado (APP fraud). Citar dados da Febraban sobre golpes no Brasil e o modelo de reembolso do Reino Unido (Payment Systems Regulator, 2024).
- Por que o antifraude tradicional falha: autentica **quem**, não **por quê**.
- Objetivo e contribuições (3 itens).

## 2. Trabalhos relacionados
Use a tabela de `AVALIACAO.md` §3: Confirmation of Payee (UK), soluções de biometria comportamental (BioCatch, Sardine), regras explicáveis (Stripe Radar), bases colaborativas de denúncia. Fechar com a lacuna: decisões graduais com perguntas de verificação e efeito de rede entre vítimas.

## 3. Proposta
- Modelo de decisão: sinais, fontes, confiança, desconto de dependência, score e bandas (`ARQUITETURA.md`, “O motor de risco”).
- As cinco saídas e a máquina de estados (diagrama de estados).
- Envelope de segurança e certificado de robustez: a decisão só endurece; aprovações frágeis são marcadas.
- Inteligência a partir de relatos.

## 4. Arquitetura e implementação
- Diagrama de componentes e de sequência do checkout (`ARQUITETURA.md`).
- Justificativa da divisão em dois microsserviços e da comunicação síncrona.
- Persistência (PostgreSQL, Flyway, restrições de integridade), segurança (JWT, papéis, token de serviço, BCrypt).
- Interfaces: loja, app do banco e painel; princípios de usabilidade aplicados (`TESTES.md` §4).
- Implantação: Docker, docker-compose e Render.

## 5. Avaliação
- **5.1 Funcional:** tabela de `TESTES.md` §1 e o teste de ponta a ponta.
- **5.2 Equivalência com o protótipo:** 43/43 casos idênticos.
- **5.3 Desempenho:** metodologia (mix, usuários, duração, ambiente), tabela de resultados, e o caso dos índices (antes/depois). **Incluir também os números rodados na nuvem.**
- **5.4 Usabilidade:** heurísticas, acessibilidade automática e o resultado do teste com 5 usuários (SUS, taxa de cancelamento, compreensão da mensagem).
- **5.5 Cenários:** tabela dos 6 cenários com decisão esperada e obtida (aba Demonstração do painel).

## 6. Discussão e limitações
- Dados simulados; pesos definidos por especialistas, não aprendidos.
- O cliente pode mentir: mitigado pelo “espelho do roteiro” e pelo certificado, não eliminado.
- Comunicação síncrona: disponibilidade do motor afeta o checkout (mitigado pela falha segura).
- Privacidade: relatos com consentimento explícito; dados mínimos.

## 7. Conclusão e trabalhos futuros
- Sinal de chamada ativa durante a compra; pausa temporizada; mensageria para processar relatos; calibração estatística com dados reais; oferta do motor como API para outros emissores.

## Referências (mínimo 8)
Febraban (relatório de fraudes), Payment Systems Regulator (APP scams reimbursement), Nielsen (heurísticas), Brooke (SUS), W3C WCAG 2.1, Newman (*Building Microservices*), documentação Spring Boot e PostgreSQL, RFC 7519 (JWT).
