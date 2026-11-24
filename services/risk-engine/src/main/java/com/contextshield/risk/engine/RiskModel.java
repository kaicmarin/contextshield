package com.contextshield.risk.engine;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Catálogo do motor: faixas, fontes, sinais e regras de política.
 * É a mesma tabela do protótipo em JavaScript (regras-v1.7), agora no servidor.
 */
public final class RiskModel {
    private RiskModel() {}

    public static final String ENGINE_VERSION = "regras-v2.0";
    public static final int BASE_SCORE = 22;
    public static final int MIN_CONFIDENCE = 40;

    public record Band(String band, int min, int max, String label, String decision) {}

    public static final List<Band> BANDS = List.of(
            new Band("LOW", 0, 29, "Baixo", "Aprovar"),
            new Band("MEDIUM", 30, 54, "Médio", "Aprovar com alerta"),
            new Band("HIGH", 55, 79, "Alto", "Pedir contexto e intervir"),
            new Band("CRITICAL", 80, 100, "Muito alto", "Bloquear"));

    public static Band bandFor(int score) {
        return BANDS.stream().filter(b -> score >= b.min() && score <= b.max()).findFirst()
                .orElse(BANDS.get(BANDS.size() - 1));
    }

    public record Source(String id, String label, String provenance, boolean hasProvider) {}

    public static final List<Source> SOURCES = List.of(
            new Source("history", "Histórico do cliente", "Perfil de gasto do emissor: ticket médio, relacionamento, frequência.", true),
            new Source("device", "Telemetria do dispositivo", "Um único SDK produz identificação, sessão e localização aproximada.", true),
            new Source("checkout", "Dados do checkout", "Endereço, itens e valor informados no pedido.", false),
            new Source("merchant", "Cadastro do vendedor", "Tempo de atividade, reputação e reclamações.", true),
            new Source("customer", "Contexto declarado", "Autodeclarado: pode ser omitido ou orientado por terceiros.", false),
            new Source("intelligence", "Inteligência", "Entidades extraídas de relatos de outros clientes.", true));

    public static Source source(String id) {
        return SOURCES.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    public record SignalSpec(String id, String source, String label, String client, String weight,
                             int confidence, List<String> dependsOn, String rationale) {}

    private static SignalSpec s(String id, String source, String label, String client, String weight, int conf,
                                String rationale, String... deps) {
        return new SignalSpec(id, source, label, client, weight, conf, List.of(deps), rationale);
    }

    public static final List<SignalSpec> SIGNALS = List.of(
            s("AMOUNT_WITHIN_HABIT", "history", "Valor compatível com o hábito", null, "−2", 85, "Até 1,5× o ticket médio dos últimos 90 dias."),
            s("AMOUNT_ABOVE_HABIT", "history", "Valor acima do hábito", "O valor está bem acima do que você costuma gastar", "+5 / +10 / +14", 85, "1,5–2,5× → +5; 2,5–4× → +10; acima de 4× → +14."),
            s("HISTORY_SPARSE", "history", "Histórico insuficiente", null, "+3", 50, "Menos de 3 compras em 90 dias: o hábito não está bem definido."),
            s("LONG_TENURE", "history", "Relacionamento longo", null, "−1", 90, "Cliente há mais de 3 anos."),
            s("UNUSUAL_HOUR", "history", "Horário atípico", "A compra acontece em um horário incomum para você", "+5", 70, "Entre 23h e 6h."),
            s("KNOWN_DEVICE", "device", "Dispositivo conhecido", null, "−4", 80, "Dispositivo marcado como confiável pelo cliente."),
            s("SESSION_CONSISTENT", "device", "Sessão consistente", null, "−2", 70, "Navegação compatível com o uso habitual."),
            s("LOCATION_CONSISTENT", "device", "Localização compatível", null, "−1", 60, "Localização aproximada na cidade do cliente."),
            s("NEW_DEVICE", "device", "Dispositivo novo", "A compra está sendo feita em um dispositivo que você nunca usou", "+8", 80, "Primeiro uso deste dispositivo pelo cliente."),
            s("SESSION_ANOMALY", "device", "Sessão curta com dados colados", null, "+4 (½ se dispositivo novo)", 60, "Mesma telemetria do dispositivo novo: conta pela metade quando os dois aparecem.", "NEW_DEVICE"),
            s("HABITUAL_DESTINATION", "checkout", "Destino habitual", null, "−3", 90, "Endereço do cliente já usado antes ou residencial."),
            s("NEW_DESTINATION_OWN", "checkout", "Primeira entrega em endereço do cliente", "É a primeira entrega neste endereço", "+8", 85, "Endereço cadastrado, mas nunca usado em compras."),
            s("NEW_DESTINATION_THIRD", "checkout", "Destino fora do cadastro do cliente", "O endereço de entrega não está entre os seus endereços", "+8", 90, "Endereço sem vínculo com o cliente."),
            s("PICKUP_POINT", "checkout", "Ponto de retirada", null, "+3", 90, "Retirada em ponto comercial, sem vínculo com o cliente."),
            s("ESTABLISHED_SELLER", "merchant", "Vendedor estabelecido", null, "−2", 85, "Mais de 2 anos de atividade e boa reputação."),
            s("RECENT_SELLER", "merchant", "Vendedor recente", "O vendedor começou a vender há poucos dias", "+6", 85, "Menos de 60 dias de atividade."),
            s("SELLER_HIGH_CLAIMS", "merchant", "Reclamações acima da média", null, "+5", 75, "Taxa de reclamações acima de 3%."),
            s("ORIGIN_SELF", "customer", "Origem declarada: própria", null, "−1", 55, "Autodeclarado. Peso baixo porque pode ser orientado."),
            s("ORIGIN_AD", "customer", "Origem declarada: anúncio", null, "+2", 55, "Anúncios de terceiros podem levar a ofertas enganosas."),
            s("ORIGIN_LINK", "customer", "Origem declarada: link recebido", "A compra começou por um link que você recebeu", "+5", 60, "Links recebidos são o início comum de roteiros de indução."),
            s("ORIGIN_GUIDED", "customer", "Origem declarada: indicação de terceiro", "Outra pessoa indicou esta compra", "+7", 60, "Ajuda de terceiros não é fraude, mas pede contexto."),
            s("VER_ASKED", "customer", "Verificação: alguém pediu a compra", "Você contou que alguém pediu esta compra", "+12 (½ se já declarado)", 70, "Mesma informação da origem declarada, quando ela já indicava terceiro.", "ORIGIN_GUIDED"),
            s("VER_CONTACT", "customer", "Verificação: contato prévio", "Alguém entrou em contato com você antes da compra", "+6 (½ se já declarado)", 70, "Confirma a origem declarada; não é evidência nova quando ela já existe.", "ORIGIN_LINK", "ORIGIN_GUIDED"),
            s("VER_ADDRESS_GIVEN", "customer", "Verificação: endereço passado por terceiro", "O endereço foi passado por outra pessoa", "+8 (½ com destino fora do cadastro)", 75, "Mesmo fato do destino fora do cadastro, visto pelo cliente.", "NEW_DESTINATION_THIRD"),
            s("VER_CLEAR", "customer", "Verificação sem sinais", null, "−8", 45, "Autodeclarado e sujeito a orientação: confiança baixa."),
            s("VER_COACHED", "customer", "Alguém orientou as respostas", "Você contou que alguém disse o que responder aqui", "+4", 75, "A pessoa admite que o questionário foi ensaiado."),
            s("SCRIPT_ECHO", "customer", "Respostas repetem o roteiro da mensagem", "As respostas negam ajuda, e a mensagem pedia exatamente isso", "+12", 80, "A negação coincide com a frase que mandava esconder a orientação."),
            s("ANCHOR_FROM_SCRIPT", "customer", "Resposta usa o endereço do roteiro", "A resposta escolheu o endereço da entrega como se fosse a sua casa", "+1", 80, "A casa do cadastro não aparece na mensagem; escolher o destino no lugar dela indica roteiro."),
            s("EVIDENCE_DIVERGENCE", "customer", "Resposta diverge dos dados da compra", null, "+6", 80, "Cliente reconhece um endereço que não é dele e é ponto de retirada."),
            s("DESTINATION_IN_INCIDENT", "intelligence", "Destino citado em relato de outro cliente", "Este endereço de entrega aparece em relatos recentes de outras pessoas", "+28", 85, "Endereço específico, confirmado em incidente de outro cliente."),
            s("SELLER_IN_INCIDENT", "intelligence", "Vendedor presente em relato de outro cliente", null, "+10", 70, "Relação moderada: o vendedor pode ter sido usado sem saber."));

    private static final Map<String, SignalSpec> SIGNAL_INDEX =
            SIGNALS.stream().collect(java.util.stream.Collectors.toMap(SignalSpec::id, x -> x));

    public static SignalSpec signal(String id) {
        SignalSpec spec = SIGNAL_INDEX.get(id);
        if (spec == null) throw new IllegalArgumentException("Sinal desconhecido: " + id);
        return spec;
    }

    public record Rule(String id, String name, String condition, String outcome) {}

    public static final List<Rule> RULES = List.of(
            new Rule("R1", "Risco muito alto", "Score ≥ 80", "BLOCK"),
            new Rule("R2", "Orientação confirmada", "Verificação indica que alguém pediu a compra e houve contato prévio", "INTERVENE"),
            new Rule("R3", "Confiança insuficiente", "Confiança < 40: pede contexto se score ≥ 30; após contexto, intervém se score ≥ 55", "REQUEST_CONTEXT"),
            new Rule("R4", "Risco alto após contexto", "Score 55–79 depois da verificação", "INTERVENE"),
            new Rule("R5", "Risco alto sem contexto", "Score 55–79 na primeira leitura", "REQUEST_CONTEXT"),
            new Rule("R6", "Orientação declarada", "Cliente declarou link recebido ou indicação de terceiro", "REQUEST_CONTEXT"),
            new Rule("R7", "Risco médio", "Score 30–54", "APPROVE_WITH_ALERT"),
            new Rule("R8", "Risco baixo", "Score < 30", "APPROVE"),
            new Rule("R9", "Questionário ensaiado", "A pessoa diz que alguém orientou o que responder", "INTERVENE"),
            new Rule("R10", "Espelho do roteiro", "A negação repete a frase da mensagem e o destino casa com o roteiro", "INTERVENE"),
            new Rule("R11", "Endereço do roteiro", "A pessoa aponta o destino da compra como se fosse a casa do cadastro", "INTERVENE"),
            new Rule("R12", "Contexto desconhecido", "Política ligada: a compra parece habitual e não há confirmação sobre contato de terceiro", "REQUEST_CONTEXT"));

    public static Rule rule(String id) {
        return RULES.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    public record DecisionMeta(String id, String label, String client, int order) {}

    public static final List<DecisionMeta> DECISIONS = List.of(
            new DecisionMeta("APPROVE", "Aprovar", "Compra aprovada", 0),
            new DecisionMeta("APPROVE_WITH_ALERT", "Aprovar com alerta", "Compra aprovada com aviso", 1),
            new DecisionMeta("REQUEST_CONTEXT", "Pedir contexto", "Confirmação necessária", 2),
            new DecisionMeta("INTERVENE", "Intervir", "Compra pausada", 3),
            new DecisionMeta("BLOCK", "Bloquear", "Compra interrompida", 4));

    public static DecisionMeta decision(String id) {
        return DECISIONS.stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }

    /** Pedir contexto, intervir e bloquear protegem o cliente; aprovar libera. */
    public static boolean isProtective(String decision) {
        return decision(decision).order() >= 2;
    }

    public record FlagSpec(String id, String label, String description) {}

    public static final List<FlagSpec> FLAGS = List.of(
            new FlagSpec("intelligence-matching", "Relações da inteligência na decisão", "Destinos e vendedores citados em relatos de outros clientes entram como sinais."),
            new FlagSpec("dependency-discount", "Desconto de evidências dependentes", "Sinais que repetem o mesmo fato contam pela metade."),
            new FlagSpec("context-on-declared-guidance", "Contexto para orientação declarada (R6)", "Quando o cliente declara link ou indicação, a verificação é solicitada."),
            new FlagSpec("guidance-floor", "Intervenção mínima com orientação confirmada (R2)", "Se a verificação confirma pedido de terceiro com contato prévio, a compra pausa."),
            new FlagSpec("script-mirror", "Espelho do roteiro (R9, R10 e R11)", "Se a mensagem mandou o que responder e a resposta segue o roteiro, a compra pausa."),
            new FlagSpec("context-when-unknown", "Verificação seletiva quando o contexto é desconhecido (R12)", "Desligada por padrão. Quando ligada, só pede verificação se a compra de score baixo também tiver um sinal de atenção já calculado. Compra habitual sem esse sinal segue aprovada. O score não muda."));

    public static Optional<String> describeWeight(String signalId) {
        return Optional.ofNullable(SIGNAL_INDEX.get(signalId)).map(SignalSpec::weight);
    }
}
