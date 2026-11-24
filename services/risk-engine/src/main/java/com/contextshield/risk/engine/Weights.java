package com.contextshield.risk.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pesos e limiares que a política pode trocar sem recompilar o motor.
 * A detecção de cada sinal, o desconto de dependência e a ordem das regras R1–R11
 * continuam no código. Um mapa vazio reproduz os números de hoje.
 */
public final class Weights {
    private Weights() {}

    public record Bound(String key, String label, String detail, int fallback, int min, int max, boolean safetyFloor) {}

    public static final List<Bound> CATALOG = List.of(
            b("BASE_SCORE", "Score base", "Ponto de partida, antes dos sinais.", 22, 10, 30, false),
            b("THRESHOLD_MEDIUM", "Início da faixa média", "Abaixo disto a faixa é baixa.", 30, 20, 40, false),
            b("THRESHOLD_HIGH", "Início da faixa alta", "A partir disto a primeira leitura pede contexto.", 55, 50, 65, false),
            b("THRESHOLD_BLOCK", "Bloqueio (R1)", "Não pode passar de 80: a regra de bloqueio não enfraquece.", 80, 70, 80, true),
            b("AMOUNT_WITHIN_HABIT", "Valor dentro do hábito", "Até 1,5× o ticket médio.", -2, -4, -1, false),
            b("AMOUNT_ABOVE_LOW", "Valor acima do hábito (1,5–2,5×)", "Primeiro degrau. O corte 1,5× continua no código.", 5, 2, 10, false),
            b("AMOUNT_ABOVE_MID", "Valor acima do hábito (2,5–4×)", "Segundo degrau. O corte 2,5× continua no código.", 10, 5, 20, false),
            b("AMOUNT_ABOVE_HIGH", "Valor acima do hábito (acima de 4×)", "Terceiro degrau. O corte 4× continua no código.", 14, 7, 28, false),
            b("HISTORY_SPARSE", "Histórico insuficiente", "Menos de 3 compras em 90 dias.", 3, 1, 6, false),
            b("LONG_TENURE", "Relacionamento longo", "Cliente há mais de 3 anos.", -1, -2, -1, false),
            b("UNUSUAL_HOUR", "Horário atípico", "Entre 23h e 6h. A janela continua no código.", 5, 2, 10, false),
            b("KNOWN_DEVICE", "Dispositivo conhecido", "Dispositivo marcado como confiável.", -4, -8, -1, false),
            b("SESSION_CONSISTENT", "Sessão consistente", "Navegação habitual.", -2, -4, -1, false),
            b("LOCATION_CONSISTENT", "Localização compatível", "Cidade do cliente.", -1, -2, -1, false),
            b("NEW_DEVICE", "Dispositivo novo", "Primeiro uso deste dispositivo.", 8, 4, 16, false),
            b("SESSION_ANOMALY", "Sessão curta", "A metade, quando o dispositivo também é novo, continua no código.", 4, 2, 8, false),
            b("HABITUAL_DESTINATION", "Destino habitual", "Endereço da casa ou já usado.", -3, -6, -1, false),
            b("NEW_DESTINATION_OWN", "Primeiro uso de endereço do cliente", "Endereço cadastrado, sem entrega anterior.", 8, 4, 16, false),
            b("NEW_DESTINATION_THIRD", "Destino fora do cadastro", "Endereço sem vínculo com o cliente.", 8, 4, 16, false),
            b("PICKUP_POINT", "Ponto de retirada", "Retirada comercial.", 3, 1, 6, false),
            b("ESTABLISHED_SELLER", "Vendedor estabelecido", "Mais de 2 anos e boa reputação.", -2, -4, -1, false),
            b("RECENT_SELLER", "Vendedor recente", "Menos de 60 dias. O prazo continua no código.", 6, 3, 12, false),
            b("SELLER_HIGH_CLAIMS", "Reclamações acima da média", "Taxa a partir de 3%. O corte continua no código.", 5, 2, 10, false),
            b("ORIGIN_SELF", "Origem declarada: própria", "Peso baixo: a declaração pode ser orientada.", -1, -2, -1, false),
            b("ORIGIN_AD", "Origem declarada: anúncio", "Anúncio de terceiro.", 2, 1, 4, false),
            b("ORIGIN_LINK", "Origem declarada: link", "Link recebido.", 5, 2, 10, false),
            b("ORIGIN_GUIDED", "Origem declarada: indicação", "Outra pessoa indicou a compra.", 7, 3, 14, false),
            b("VER_ASKED", "Alguém pediu a compra", "Piso de segurança: a verificação não pode zerar.", 12, 8, 24, true),
            b("VER_CONTACT", "Contato prévio", "Telefone ou mensagem antes da compra.", 6, 3, 12, false),
            b("VER_ADDRESS_GIVEN", "Endereço passado por terceiro", "A metade, quando o destino já é de terceiro, continua no código.", 8, 4, 16, false),
            b("VER_CLEAR", "Verificação sem sinais", "Confiança baixa, porque é autodeclarado.", -8, -16, -1, false),
            b("VER_COACHED", "Alguém orientou as respostas", "A pessoa admite o ensaio.", 4, 2, 8, false),
            b("SCRIPT_ECHO", "Respostas repetem o roteiro", "Piso de segurança da regra do espelho.", 12, 8, 24, true),
            b("ANCHOR_FROM_SCRIPT", "Resposta usa o endereço do roteiro", "A casa do cadastro não foi escolhida.", 1, 1, 2, false),
            b("EVIDENCE_DIVERGENCE", "Resposta diverge da compra", "Reconhece um endereço que não é dele.", 6, 3, 12, false),
            b("DESTINATION_IN_INCIDENT", "Destino em relato de outro cliente", "Piso 20: um destino denunciado não pode virar detalhe.", 28, 20, 40, true),
            b("SELLER_IN_INCIDENT", "Vendedor em relato de outro cliente", "Relação moderada.", 10, 5, 20, false));

    private static final Map<String, Bound> INDEX = index();

    private static Bound b(String key, String label, String detail, int fallback, int min, int max, boolean floor) {
        return new Bound(key, label, detail, fallback, min, max, floor);
    }

    private static Map<String, Bound> index() {
        Map<String, Bound> map = new LinkedHashMap<>();
        for (Bound bound : CATALOG) map.put(bound.key(), bound);
        return Map.copyOf(map);
    }

    public static Map<String, Integer> defaults() {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Bound bound : CATALOG) map.put(bound.key(), bound.fallback());
        return Map.copyOf(map);
    }

    public static Bound bound(String key) {
        Bound bound = INDEX.get(key);
        if (bound == null) throw new IllegalArgumentException("Peso desconhecido: " + key);
        return bound;
    }

    /** Valor usado pelo motor. Fora da faixa permitida, encosta no limite. Mapa vazio = comportamento atual. */
    public static int value(Map<String, Integer> weights, String key) {
        Bound bound = bound(key);
        if (weights == null) return bound.fallback();
        Integer requested = weights.get(key);
        if (requested == null) return bound.fallback();
        return Math.max(bound.min(), Math.min(bound.max(), requested));
    }

    /** Recusa, em vez de corrigir, um valor fora da faixa. Usado ao gravar uma política. */
    public static void check(String key, int requested) {
        Bound bound = bound(key);
        if (requested < bound.min() || requested > bound.max()) {
            throw new IllegalArgumentException(bound.label() + " precisa ficar entre " + bound.min() + " e " + bound.max() + ".");
        }
    }

    public static Map<String, Integer> merge(Map<String, Integer> overlay) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (overlay != null) {
            for (String key : overlay.keySet()) {
                if (!INDEX.containsKey(key)) throw new IllegalArgumentException("Peso desconhecido: " + key);
            }
        }
        for (Bound bound : CATALOG) {
            Integer requested = overlay == null ? null : overlay.get(bound.key());
            out.put(bound.key(), requested == null ? bound.fallback() : requested);
            if (requested != null) check(bound.key(), requested);
        }
        return Map.copyOf(out);
    }
}
