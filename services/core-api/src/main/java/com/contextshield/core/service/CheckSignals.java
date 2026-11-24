package com.contextshield.core.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Famílias de sinais do verificador. No máximo um ponto por família (não soma pistas da mesma história).
 * Pesos documentados em docs/CHECK.md.
 */
public final class CheckSignals {
    private CheckSignals() {}

    public record Hit(String family, String label, String explain, int weight, boolean type) {}

    public static List<Hit> detect(String raw) {
        String n = IntelText.fold(raw);
        List<Hit> out = new ArrayList<>();
        if (n.isEmpty()) return out;

        if (delivery(n))
            out.add(new Hit("delivery", "Falsa taxa ou encomenda retida",
                    "Cobra taxa ou pressiona para liberar um pacote. Empresas de entrega não pedem pagamento por link de mensagem.", 25, true));
        if (bank(n))
            out.add(new Hit("bank", "Falsa central de atendimento",
                    "Alguém se apresenta como o banco ou a loja e conduz você a “resolver” um problema.", 22, true));
        if (relative(n))
            out.add(new Hit("relative", "Falso parente ou “número novo”",
                    "Alguém finge ser um conhecido com número novo e pede dinheiro com urgência.", 22, true));
        if (prize(n))
            out.add(new Hit("prize", "Falso prêmio ou sorteio",
                    "Promete algo de valor e pede um pagamento ou uma compra para liberar.", 20, true));
        if (support(n))
            out.add(new Hit("support", "Falso suporte técnico",
                    "Pede instalação de programa, acesso remoto ou dados para “consertar” o aparelho.", 22, true));
        if (advance(n) && out.stream().noneMatch(h -> "delivery".equals(h.family())))
            out.add(new Hit("advance", "Cobrança antecipada para liberar valor",
                    "Pede um depósito ou taxa para liberar um ganho que ainda não existe.", 20, true));
        if (job(n))
            out.add(new Hit("job", "Falsa vaga ou renda extra",
                    "Oferece ganho fácil e depois pede um pagamento para “liberar” o valor.", 18, true));
        if (validation(n))
            out.add(new Hit("validation", "Link falso de validação",
                    "Um link imita um site oficial para capturar dados ou induzir um pagamento.", 16, true));
        if (authority(n) && out.stream().noneMatch(h -> "bank".equals(h.family())))
            out.add(new Hit("authority", "Falsa autoridade",
                    "Alguém se apresenta como polícia, Receita ou outro órgão para forçar uma ação.", 18, true));

        boolean typed = out.stream().anyMatch(Hit::type);
        if (payment(n)) {
            int w = typed ? 8 : 16;
            out.add(new Hit("payment", "Pedido de pagamento",
                    "Confirme por conta própria para quem vai o dinheiro. Nenhum banco pede Pix, boleto ou compra por mensagem.", w, false));
        }
        if (urgency(n))
            out.add(new Hit("urgency", "Pressão de tempo",
                    "A pressa impede que você confira o canal oficial.", 10, false));
        if (secrecy(n))
            out.add(new Hit("secrecy", "Pedido para esconder a orientação",
                    "Pedir segredo é para impedir que alguém alerte você.", 16, false));
        if (refund(n))
            out.add(new Hit("refund", "Promessa de devolução do valor",
                    "A promessa de estorno serve para tirar o medo de pagar.", 12, false));
        if (procedure(n))
            out.add(new Hit("procedure", "Compra apresentada como procedimento",
                    "Nenhum banco pede que você compre algo para cancelar outra compra.", 18, false));
        if (code(n))
            out.add(new Hit("code", "Pedido de código ou senha",
                    "Códigos e senhas são só seus. Ninguém legítimo pede.", 20, false));
        if (remote(n) && out.stream().noneMatch(h -> "support".equals(h.family())))
            out.add(new Hit("remote", "Pedido para instalar app ou compartilhar tela",
                    "Dá controle do seu aparelho a outra pessoa.", 18, false));
        if (address(n))
            out.add(new Hit("address", "Endereço de entrega indicado por terceiro",
                    "A mercadoria pode ir para quem indicou o endereço.", 12, false));
        return out;
    }

    private static boolean delivery(String n) {
        boolean obj = IntelText.has(n, "encomenda", "pacote", "pedido", "mercadoria", "sedex", "correio", "rastreio", "rastreamento");
        boolean threat = IntelText.has(n, "retida", "retido", "parada", "parado", "reten", "alfandega", "alfandegaria",
                "bloqueada", "bloqueado", "remetente", "devolvida", "devolvido");
        boolean fee = IntelText.has(n, "taxa") && (IntelText.has(n, "entrega", "liberacao", "liberar", "alfandega")
                || IntelText.has(n, "pague", "pagar", "pagamento"));
        return (obj && threat) || (obj && fee) || (IntelText.has(n, "taxa") && IntelText.has(n, "entrega", "liberacao", "liberar", "alfandega"));
    }

    private static boolean bank(String n) {
        boolean pose = IntelText.has(n, "central", "atendimento", "funcionario", "funcionaria")
                && IntelText.has(n, "banco", "seguranca", "fraude", "cartao");
        boolean alert = IntelText.has(n, "compra") && IntelText.has(n, "suspeita", "reconhecida", "reconhecido");
        boolean setor = n.contains("setor de fraude") || n.contains("central de seguranca") || n.contains("central de atendimento");
        return pose || alert || setor;
    }

    private static boolean relative(String n) {
        boolean kin = IntelText.has(n, "mae", "pai", "filho", "filha", "tio", "tia", "irmao", "irma");
        boolean ask = IntelText.has(n, "ajuda", "ajudar", "preciso", "pix", "transferir");
        boolean newNumber = n.contains("numero novo") || n.contains("troquei de numero");
        return newNumber || (kin && ask);
    }

    private static boolean prize(String n) {
        return IntelText.has(n, "premio", "sorteado", "sorteada", "sorteio", "resgate", "resgatar")
                || n.contains("voce ganhou") || n.contains("vc ganhou");
    }

    private static boolean support(String n) {
        boolean tech = IntelText.has(n, "suporte", "tecnico", "antivirus", "windows", "microsoft", "formatar");
        boolean remote = IntelText.has(n, "anydesk", "teamviewer", "remoto", "compartilhar");
        return (tech && (remote || IntelText.has(n, "instale", "instalar", "acesso", "senha"))) || (tech && remote);
    }

    private static boolean advance(String n) {
        return (IntelText.has(n, "adiantamento", "sinal") && IntelText.has(n, "liberar", "liberacao", "receber"))
                || (IntelText.has(n, "deposito") && IntelText.has(n, "previo", "antecipado", "antecipada"));
    }

    private static boolean payment(String n) {
        return IntelText.has(n, "pague", "pagar", "pagamento", "pix", "boleto", "deposito", "depositar",
                "reais", "transferir", "transfira", "transferencia");
    }

    private static boolean urgency(String n) {
        return IntelText.has(n, "urgente", "urgentemente", "imediatamente", "agora")
                || n.contains("agora mesmo") || n.contains("ultima chance") || n.contains("hoje ainda")
                || n.contains("nao desligue") || n.matches(".*\\bhoje\\b.*")
                || n.matches(".*\\bem ate \\d+.*");
    }

    private static boolean secrecy(String n) {
        return n.contains("nao conte") || n.contains("nao fale") || n.contains("nao comente")
                || IntelText.has(n, "sigilo")
                || n.contains("ninguem pode saber") || n.contains("ninguem precisa saber")
                || n.contains("ninguem esta ajudando") || n.contains("responda que") || n.contains("diga que");
    }

    private static boolean refund(String n) {
        return n.contains("estorn") || n.contains("sera devolvid") || IntelText.has(n, "reembolso", "reembolsado")
                || n.contains("dinheiro volta") || n.contains("volta na proxima fatura");
    }

    private static boolean procedure(String n) {
        return n.contains("procedimento de seguranca")
                || (IntelText.has(n, "cancelar") && IntelText.has(n, "operacao", "compra"))
                || n.contains("compra de teste") || n.contains("compra de seguranca") || n.contains("compra para ativacao");
    }

    private static boolean code(String n) {
        return (IntelText.has(n, "codigo") && IntelText.has(n, "sms", "verificacao", "chegou"))
                || IntelText.has(n, "senha", "token");
    }

    private static boolean remote(String n) {
        return (IntelText.has(n, "instale", "instalar") && IntelText.has(n, "app", "aplicativo"))
                || n.contains("acesso remoto") || n.contains("compartilhar tela") || n.contains("compartilhe a tela");
    }

    private static boolean address(String n) {
        return n.contains("ponto de retirada") || n.contains("use este endereco") || n.contains("use esse endereco")
                || n.contains("use o endereco") || n.contains("retirada em") || n.contains("entregue na")
                || n.contains("entregar em") || n.contains("entregar no");
    }

    private static boolean job(String n) {
        return n.contains("renda extra") || n.contains("ganhe por dia") || n.contains("ganhe por tarefa")
                || (IntelText.has(n, "vaga") && IntelText.has(n, "home", "homeoffice", "emprego", "remunerada"))
                || (IntelText.has(n, "tarefas") && IntelText.has(n, "pagas", "remuneradas"));
    }

    private static boolean authority(String n) {
        return IntelText.has(n, "policia", "delegacia", "delegado", "receita", "tribunal", "procuradoria")
                && IntelText.has(n, "mandado", "bloqueio", "bloqueada", "regularize", "regularizar", "multa", "cpf", "pix", "pague");
    }

    private static boolean validation(String n) {
        boolean click = IntelText.has(n, "clique", "acesse", "acessar", "entre") && IntelText.has(n, "link");
        boolean data = IntelText.has(n, "validar", "regularizar")
                || (IntelText.has(n, "atualizar") && IntelText.has(n, "dados", "cadastro"));
        return click || data || n.contains("evitar o bloqueio") || n.contains("evitar bloqueio");
    }
}
