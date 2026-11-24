package com.contextshield.risk.engine;

import com.contextshield.risk.model.Assessment.Option;
import com.contextshield.risk.model.Assessment.Question;
import com.contextshield.risk.model.Assessment.QuestionPlan;
import com.contextshield.risk.model.Assessment.Trigger;
import com.contextshield.risk.model.AssessmentInput;
import com.contextshield.risk.model.Signal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Quando a decisão é "pedir contexto", escolhe até 5 perguntas a partir dos sinais que dispararam.
 * Cada pergunta nasce de uma hipótese ("a compra veio de um link"), não de um questionário fixo.
 */
public final class QuestionPlanner {
    private QuestionPlanner() {}

    static final int MAX_QUESTIONS = 5;

    private record Ctx(AssessmentInput in, Set<String> ids, Optional<String> instruction, Question anchor,
                       boolean owned, boolean link, boolean guided) {}

    private record TriggerSpec(String id, String name, String quiet, Predicate<Ctx> when,
                               Function<Ctx, String> because, Function<Ctx, Question> build) {}

    private static List<Option> opts(String... pairs) {
        List<Option> out = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) out.add(new Option(pairs[i], pairs[i + 1]));
        return out;
    }

    private static Question q(String key, String id, String hypothesis, String prompt, String help, String quote,
                              boolean showAddress, List<Option> options) {
        return new Question(key, id, hypothesis, prompt, help, quote, showAddress, options);
    }

    private static final List<TriggerSpec> TRIGGERS = List.of(
            new TriggerSpec("link-origin", "Link declarado", "A compra não foi declarada como vinda de um link.",
                    c -> c.ids().contains("ORIGIN_LINK") || "link".equals(c.in().declaredOrigin()),
                    c -> "A compra foi declarada como vinda de um link.",
                    c -> q("origin", "link-origin", "A compra foi declarada como vinda de um link.",
                            "Você chegou nesta compra por um link que alguém enviou?",
                            "Um link recebido não prova golpe. Queremos saber se a decisão foi sua.", null, false,
                            opts("asked", "Sim, alguém me enviou", "self", "Não, eu encontrei sozinho"))),
            new TriggerSpec("guided-origin", "Indicação declarada", "A compra não foi declarada como indicação de outra pessoa.",
                    c -> !c.ids().contains("ORIGIN_LINK") && !"link".equals(c.in().declaredOrigin())
                            && (c.ids().contains("ORIGIN_GUIDED") || "guided".equals(c.in().declaredOrigin())),
                    c -> "A compra foi declarada como indicação de outra pessoa.",
                    c -> q("origin", "guided-origin", "A compra foi declarada como indicação de outra pessoa.",
                            "Foi outra pessoa quem pediu esta compra?",
                            "Indicação não é golpe. A pausa só acontece se o restante confirmar orientação.", null, false,
                            opts("asked", "Sim, alguém me pediu para comprar", "self", "Não, a decisão foi minha"))),
            new TriggerSpec("prior-contact", "Contato anterior", "Não há link, indicação nem frase de roteiro que peça essa pergunta.",
                    c -> c.link() || c.guided() || c.instruction().isPresent(),
                    c -> "Este padrão costuma começar com uma ligação ou uma mensagem.",
                    c -> q("contact", "prior-contact", "Este padrão costuma começar com uma ligação ou uma mensagem.",
                            "Alguém entrou em contato com você antes desta compra?",
                            "Por exemplo, alguém dizendo ser do banco, da loja ou de uma transportadora.", null, false,
                            opts("no", "Não", "phone", "Sim, por telefone", "message", "Sim, por mensagem"))),
            new TriggerSpec("script-line", "Frase de roteiro", "Nenhuma mensagem vista nesta compra manda o que responder.",
                    c -> c.instruction().isPresent(),
                    c -> "A mensagem antecipa o que responder nesta tela.",
                    c -> q("coached", "script-line", "A mensagem antecipa o que responder nesta tela.",
                            "Alguém te disse o que responder aqui?", "Conte o que aconteceu, não o que mandaram dizer.",
                            c.instruction().orElse(null), false,
                            opts("no", "Não, estou respondendo por conta própria", "yes", "Sim, alguém me orientou a responder"))),
            new TriggerSpec("home-anchor", "Casa fora da mensagem", "A entrega é a casa do cadastro, ou a mensagem já cita essa casa.",
                    c -> c.anchor() != null,
                    c -> "O destino desta compra não é a casa do cadastro, e essa casa não aparece na mensagem.",
                    Ctx::anchor),
            new TriggerSpec("unknown-address", "Endereço sem vínculo", "O endereço está no cadastro, ou a pergunta da casa já cobre o destino.",
                    c -> !c.owned() && c.anchor() == null,
                    c -> "O endereço de entrega não está no cadastro.",
                    c -> q("address", "unknown-address", "O endereço de entrega não está no cadastro.",
                            "Você reconhece este endereço de entrega?", "", null, true,
                            opts("mine", "Sim, é meu ou de alguém que conheço", "given", "Me passaram este endereço"))),
            new TriggerSpec("new-device", "Aparelho novo", "O aparelho desta compra já é conhecido na conta.",
                    c -> c.ids().contains("NEW_DEVICE"),
                    c -> c.in().device().name() + " não é um aparelho conhecido desta conta.",
                    c -> q("device", "new-device", c.in().device().name() + " não é um aparelho conhecido desta conta.",
                            "O " + c.in().device().name() + " (" + c.in().device().detail() + ") é seu?",
                            c.in().device().location() != null ? "A localização aproximada desta sessão é " + c.in().device().location() + "." : "",
                            null, false,
                            opts("mine", "Sim, este aparelho é meu", "other", "Não, tem outra pessoa comigo aqui", "unsure", "Não tenho certeza"))),
            new TriggerSpec("unknown-asked", "Pedido de terceiro desconhecido", "Já há link, indicação ou roteiro que explica o pedido.",
                    QuestionPlanner::unknownContext,
                    c -> "Não há registro de ligação ou mensagem. A declaração de compra própria não confirma isso.",
                    c -> q("origin", "unknown-asked", "Não há registro de contato de terceiro.",
                            "Alguém pediu que você realizasse esta operação?",
                            "A resposta é sua declaração. Ela não prova fraude nem legitimidade.", null, false,
                            opts("asked", "Sim, alguém pediu", "self", "Não, a decisão foi minha"))),
            new TriggerSpec("unknown-contact", "Contato não registrado", "Já há link, indicação ou roteiro que explica o contato.",
                    QuestionPlanner::unknownContext,
                    c -> "O sistema não viu uma ligação. Só o cliente pode dizer se ela aconteceu.",
                    c -> q("contact", "unknown-contact", "Não há evidência de ligação ou mensagem nesta operação.",
                            "Você recebeu uma ligação ou mensagem dizendo que sua conta ou entrega estava em risco?",
                            "Se ninguém entrou em contato, responda não. A ausência desta resposta não libera a compra.", null, false,
                            opts("no", "Não", "phone", "Sim, por telefone", "message", "Sim, por mensagem"))),
            new TriggerSpec("unknown-coached", "Orientação para ignorar alerta", "Já há uma mensagem vista que antecipa a resposta.",
                    QuestionPlanner::unknownContext,
                    c -> "Não há mensagem arquivada. A pergunta existe porque o contexto está desconhecido.",
                    c -> q("coached", "unknown-coached", "Não há mensagem arquivada dizendo o que responder.",
                            "Alguém orientou você a ignorar um alerta ou a responder de um jeito combinado?",
                            "Admitir a orientação não encerra a análise sozinha.", null, false,
                            opts("no", "Não", "yes", "Sim, alguém me orientou"))),
            new TriggerSpec("amount-habit", "Valor acima do hábito", "O valor cabe no gasto habitual da conta.",
                    c -> c.ids().contains("AMOUNT_ABOVE_HABIT"),
                    c -> "O valor está acima do gasto habitual de " + c.in().customer().firstName() + ".",
                    c -> q("amount", "amount-habit", "O valor está acima do gasto habitual de " + c.in().customer().firstName() + ".",
                            "Esta compra é " + Fmt.brl(c.in().amount()) + ". Você escolheu esse valor?",
                            "O ticket médio desta conta nos últimos 90 dias fica em torno de " + Fmt.brl(c.in().customer().avgTicket90d()) + ".",
                            null, false,
                            opts("chose", "Sim, eu escolhi", "asked", "Alguém pediu este produto ou este valor", "unsure", "Não sei dizer"))));

    /** Pergunta "qual destes é a sua casa?": só aparece quando a casa não está no roteiro do golpista. */
    static Question anchorPrompt(AssessmentInput in) {
        var home = in.homeAddress();
        var delivery = in.address();
        if (home == null || delivery == null) return null;
        String homeStreet = Coaching.street(home.line1());
        String otherStreet = Coaching.street(delivery.line1());
        if (homeStreet.length() < 8 || otherStreet.length() < 8 || homeStreet.equals(otherStreet)) return null;
        String script = in.coachScript() == null ? "" : in.coachScript().toLowerCase();
        if (script.contains(homeStreet)) return null;
        boolean namesDelivery = script.contains(otherStreet);
        if (Coaching.instruction(script).isEmpty() && !namesDelivery) return null;
        Option homeOpt = new Option("home", home.line1());
        Option scriptOpt = new Option("script", delivery.line1());
        List<Option> options = new ArrayList<>(Coaching.stableFlip(in.customer().id() + ":" + delivery.id())
                ? List.of(scriptOpt, homeOpt) : List.of(homeOpt, scriptOpt));
        options.add(new Option("unsure", "Não tenho certeza agora"));
        return q("anchor", "home-anchor", "O destino desta compra não é a casa do cadastro, e essa casa não aparece na mensagem.",
                "Qual destes é a sua casa, a que já estava no seu cadastro?",
                "Escolha o endereço que já era seu antes desta conversa. Se não lembrar, diga que não tem certeza.",
                null, false, options);
    }

    static String situation(AssessmentInput in, Set<String> ids, Optional<String> instruction) {
        List<String> bits = new ArrayList<>();
        if (ids.contains("NEW_DEVICE")) bits.add("o aparelho " + in.device().name() + " não é um dos aparelhos conhecidos de " + in.customer().firstName());
        if (ids.contains("AMOUNT_ABOVE_HABIT")) bits.add("o valor " + Fmt.brl(in.amount()) + " passa do gasto habitual, perto de " + Fmt.brl(in.customer().avgTicket90d()));
        if (ids.contains("PICKUP_POINT") || ids.contains("NEW_DESTINATION_THIRD")) bits.add("a entrega em " + in.address().line1() + " não está no cadastro");
        else if (ids.contains("NEW_DESTINATION_OWN")) bits.add("a entrega em " + in.address().line1() + " ainda não tinha sido usada");
        if (instruction.isPresent()) bits.add("há uma mensagem dizendo o que responder nesta tela");
        if (ids.contains("ORIGIN_LINK")) bits.add("o pedido foi marcado como vindo de um link");
        if (ids.contains("ORIGIN_GUIDED")) bits.add("o pedido foi marcado como indicação de outra pessoa");
        if (ids.contains("DESTINATION_IN_INCIDENT")) bits.add("este destino aparece em relato de outro cliente");
        String origin = in.declaredOrigin();
        if ((origin == null || origin.isBlank() || "self".equals(origin)) && !ids.contains("ORIGIN_LINK") && !ids.contains("ORIGIN_GUIDED"))
            bits.add("não há registro de ligação ou mensagem; a compra foi declarada como própria e isso ainda não foi verificado");
        if (bits.isEmpty()) return "Nada nesta compra foge do cadastro ou do gasto habitual. Nenhum gatilho de contexto disparou.";
        return "O que pode estar acontecendo, com o que já sabemos: " + String.join("; ", bits) + ".";
    }

    private static boolean unknownContext(Ctx c) {
        if (c.link() || c.guided() || c.instruction().isPresent()) return false;
        String origin = c.in().declaredOrigin();
        return origin == null || origin.isBlank() || "self".equals(origin);
    }

    public static QuestionPlan plan(AssessmentInput in, List<Signal> signals) {
        Set<String> ids = signals.stream().map(Signal::id).collect(Collectors.toSet());
        Optional<String> instruction = Coaching.instruction(in.coachScript());
        Ctx ctx = new Ctx(in, ids, instruction, anchorPrompt(in), in.ownsAddress(),
                ids.contains("ORIGIN_LINK") || "link".equals(in.declaredOrigin()),
                ids.contains("ORIGIN_GUIDED") || "guided".equals(in.declaredOrigin()));
        List<Question> questions = new ArrayList<>();
        List<Trigger> fired = new ArrayList<>();
        List<Trigger> quiet = new ArrayList<>();
        for (TriggerSpec t : TRIGGERS) {
            if (!t.when().test(ctx)) {
                quiet.add(new Trigger(t.id(), t.name(), t.quiet(), false, null));
                continue;
            }
            Question question = t.build().apply(ctx);
            boolean duplicate = questions.stream().anyMatch(x -> x.key().equals(question.key()));
            boolean held = questions.size() >= MAX_QUESTIONS || duplicate;
            fired.add(new Trigger(t.id(), t.name(), t.because().apply(ctx), !held,
                    held ? (duplicate ? "Outro gatilho já puxou esta pergunta." : "A verificação mostra no máximo " + MAX_QUESTIONS + " perguntas.") : null));
            if (!held) questions.add(question);
        }
        if (questions.isEmpty()) {
            questions.add(q("origin", "context-open", "A compra pediu contexto e nenhuma hipótese mais específica apareceu.",
                    "Você iniciou esta compra por conta própria?", "Queremos entender como a compra começou.", null, false,
                    opts("self", "Sim, fui eu que decidi comprar", "asked", "Não, alguém me pediu")));
            fired.add(new Trigger("context-open", "Contexto sem hipótese específica", "Nenhum gatilho mais específico disparou.", true, null));
        }
        return new QuestionPlan(situation(in, ids, instruction), questions, fired, quiet);
    }
}
