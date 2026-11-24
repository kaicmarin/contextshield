# Roteiro da apresentação (20 minutos)

A oral é nota individual, então cada um apresenta a parte que **construiu e consegue defender**. Sugestão de divisão:

| Tempo | Quem | Parte |
|---|---|---|
| 0–3 min | Bruno | O problema e a ideia |
| 3–10 min | Eric | Demonstração ao vivo (loja → app → painel) |
| 10–15 min | Thiago | Arquitetura, microsserviços, segurança e deploy |
| 15–18 min | Bruno | Testes: funcionais, desempenho, usabilidade |
| 18–20 min | Todos | Limitações, próximos passos e perguntas |

## Antes de começar (5 minutos antes)

1. **Acordar os três serviços** (no plano gratuito eles dormem e o motor só acorda quando chamado; se ele estiver dormindo, a compra da Mariana fica “em análise” em vez de bloqueada). Rodar e esperar os três responderem `UP`:
   ```bash
   for s in contextshield-risk-engine contextshield-core-api; do curl -s --max-time 90 https://$s.onrender.com/actuator/health; echo; done
   curl -s -o /dev/null -w "%{http_code}\n" https://contextshield-web.onrender.com/
   ```
   Depois passar pelas três telas. Se algo não subir em 3 minutos, ir direto para o plano B.
2. Painel → **Demonstração → Reiniciar dados**.
3. Deixar três abas abertas: **Loja**, **App Aureon** (logado como Ana), **Painel** (logado como analista).
4. Plano B: o mesmo sistema rodando local com `docker compose up` (já de pé antes de começar) e um vídeo de 3 minutos gravado do fluxo.

## 1. O problema (3 min)

> “Golpe da falsa central: alguém liga dizendo ser do banco, diz que o cartão foi clonado e que, para cancelar, a pessoa precisa fazer uma compra ‘de validação’ e entregar num ponto de retirada. A vítima compra no próprio celular, com a própria senha. Para o banco, é uma compra perfeita.”

- Antifraude tradicional olha **quem** está comprando. Aqui é a pessoa certa. O que muda é o **contexto**: a compra foi ideia de quem?
- Nossa proposta: avaliar o contexto e, em vez de só aprovar ou negar, ter respostas intermediárias: **avisar, perguntar, pausar**.

## 2. Demonstração (7 min)

1. **Loja → Cenários da apresentação → A (compra habitual).** Pagar. Aprovada sem atrito. *“A maioria das compras não pode sentir o sistema.”*
2. **Cenário I (possível indução).** Mostrar o painel “Enquanto isso, no celular”: as mensagens do golpista, incluindo *“se perguntarem, diga que ninguém está ajudando”*.
3. Pagar. O checkout **pede contexto**. Responder como a Ana responderia sob orientação (alguém mandou o link, houve contato por mensagem, endereço foi passado).
4. Compra **pausada**. Ler a mensagem: “Ninguém do banco pede compras para cancelar nada.” **Cancelar.**
5. **App Aureon**: o aviso chegou. Ir em **Fui vítima** e registrar com o telefone e o endereço da conversa.
6. **Painel → Demonstração → rodar C/D (Mariana)**: outra cliente, outro golpista, mesmo ponto de retirada. **Bloqueada**, sem precisar perguntar nada. *“O relato da Ana protegeu a Mariana.”*
7. Abrir a transação bloqueada: sinais com pontos, regra R1, envelope de segurança, certificado de robustez.

## 3. Arquitetura (5 min)

- Dois microsserviços Spring Boot: **motor de risco** (sem estado, só decide) e **API principal** (guarda tudo, PostgreSQL). Mostrar o diagrama do README.
- **Por que separar assim**: o motor muda de regra com frequência e poderia atender outros bancos; a API cuida de dados e permissões.
- **Falha segura**: se o motor cair, a compra fica “em análise”. E se só uma fonte de dados cair, o motor perde confiança e endurece. Demonstrar: Painel → Motor de risco → desligar **Telemetria do dispositivo** e **Cadastro do vendedor** → Demonstração → rodar A e B. A compra habitual deixa de ser aprovada direto (vira “aprovada com aviso”, confiança 33%) e a B vira intervenção. **Religar as fontes depois.**
- **Segurança**: JWT com papéis, token de serviço entre os microsserviços, BCrypt, auditoria.
- **Deploy**: Docker, um `docker compose up` local e o Render na nuvem com PostgreSQL gerenciado.

## 4. Testes (3 min)

- **76 testes automatizados** (`mvn verify`), incluindo 43 que provam que o motor Java decide igual ao protótipo.
- **Desempenho**: carga normal p95 57 ms, pico (50 usuários) p95 364 ms. Contar a história do índice: o teste achou uma lentidão de 1 s e a correção cortou para um terço.
- **Usabilidade**: 0 violações de acessibilidade em 19 telas; avaliação heurística das mensagens; resultado do teste com 5 usuários (SUS).

## 5. Fechamento (2 min)

- **Limitações honestas**: dados simulados; regras definidas por nós, não aprendidas; o cliente pode mentir nas respostas (por isso o envelope de segurança e o certificado).
- **Próximos passos**: fila para processar relatos de forma assíncrona; modelo estatístico calibrado com dados reais; integração com o canal oficial do banco (push, não SMS).

## Perguntas que a banca deve fazer (e respostas curtas)

| Pergunta | Resposta |
|---|---|
| Por que não usar machine learning? | Não temos dados rotulados de golpe por indução. Regras explicáveis permitem justificar cada decisão para o cliente e para auditoria. O desenho permite trocar o score por um modelo depois. |
| E se o golpista mandar a vítima mentir nas respostas? | É o caso “espelho do roteiro” (R9–R11): se a resposta repete a frase que o golpista mandou dizer, a compra pausa. E o certificado mostra quando a aprovação depende só do que o cliente declarou. |
| Por que REST e não mensageria? | O cliente precisa da decisão no checkout, em tempo real. Mensageria entraria para o processamento posterior dos relatos. |
| Como escala? | O motor não tem estado: basta subir mais instâncias. A API escala horizontalmente com o banco como ponto de coordenação; a trava otimista (`version`) evita duas decisões simultâneas na mesma compra. |
| O que acontece se o motor cair? | A compra não é aprovada: fica “em análise” (HTTP 202) e pode ser reavaliada. Testado em `motorForaDoArNaoAprovaACompra`. |
