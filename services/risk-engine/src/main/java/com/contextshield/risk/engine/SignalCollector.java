package com.contextshield.risk.engine;

import com.contextshield.risk.model.AssessmentInput;
import com.contextshield.risk.model.AssessmentInput.Answers;
import com.contextshield.risk.model.AssessmentInput.Relation;
import com.contextshield.risk.model.AssessmentInput.Seller;
import com.contextshield.risk.model.Signal;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Passo 1 do motor: transforma o contexto da compra em uma lista de sinais com peso. */
public final class SignalCollector {
    private SignalCollector() {}

    public static Signal signal(String id, int points, String detail) {
        return signal(id, points, detail, null, null);
    }

    public static Signal signal(String id, int points, String detail, String entityId, List<String> incidentIds) {
        RiskModel.SignalSpec spec = RiskModel.signal(id);
        return new Signal(id, spec.source(), spec.label(), spec.client(), detail, points, points,
                Signal.stateFor(points), spec.confidence(), false, null, entityId, incidentIds);
    }

    static double daysBetween(LocalDateTime from, LocalDateTime to) {
        return Duration.between(from, to).toMinutes() / 1440.0;
    }

    static double daysBetween(LocalDate from, LocalDateTime to) {
        return daysBetween(from.atStartOfDay(), to);
    }

    /** Provedores indisponíveis: os listados na chamada e os marcados como "down" na configuração. */
    public static List<String> downProviders(AssessmentInput in) {
        Set<String> down = new LinkedHashSet<>(in.unavailable() == null ? List.of() : in.unavailable());
        var providers = in.configOrDefault().providers();
        if (providers != null) providers.forEach((id, status) -> { if ("down".equals(status)) down.add(id); });
        return List.copyOf(down);
    }

    public static List<Signal> collect(AssessmentInput in) {
        List<Signal> out = new ArrayList<>();
        Set<String> down = Set.copyOf(downProviders(in));
        var cfg = in.configOrDefault();
        var customer = in.customer();
        var device = in.device();
        var address = in.address();
        var merchant = in.merchant();
        double amount = in.amount();
        LocalDateTime at = in.at();

        if (!down.contains("history")) {
            double avg = customer.avgTicket90d() <= 0 ? 1 : customer.avgTicket90d();
            double ratio = amount / avg;
            if (customer.purchases90d() < 3)
                out.add(signal("HISTORY_SPARSE", w(cfg, "HISTORY_SPARSE"), customer.purchases90d() + " compras nos últimos 90 dias"));
            if (ratio <= 1.5)
                out.add(signal("AMOUNT_WITHIN_HABIT", w(cfg, "AMOUNT_WITHIN_HABIT"), Fmt.brl(amount) + " · ticket médio " + Fmt.brl(avg) + " (" + Fmt.ratio(ratio) + ")"));
            else
                out.add(signal("AMOUNT_ABOVE_HABIT", ratio > 4 ? w(cfg, "AMOUNT_ABOVE_HIGH") : ratio > 2.5 ? w(cfg, "AMOUNT_ABOVE_MID") : w(cfg, "AMOUNT_ABOVE_LOW"),
                        Fmt.brl(amount) + " · " + Fmt.ratio(ratio) + " o ticket médio de " + Fmt.brl(avg)));
            if (customer.since() != null && daysBetween(customer.since(), at) > 3 * 365)
                out.add(signal("LONG_TENURE", w(cfg, "LONG_TENURE"), "Cliente desde " + Fmt.date(customer.since())));
            int hour = at.getHour();
            if (hour >= 23 || hour < 6)
                out.add(signal("UNUSUAL_HOUR", w(cfg, "UNUSUAL_HOUR"), String.format("Compra às %02dh", hour)));
        }

        if (!down.contains("device")) {
            if (device.trusted()) {
                out.add(signal("KNOWN_DEVICE", w(cfg, "KNOWN_DEVICE"), device.name() + " · " + device.detail() + ", em uso desde " + Fmt.date(device.firstSeen())));
                out.add(signal("SESSION_CONSISTENT", w(cfg, "SESSION_CONSISTENT"), "Sessão iniciada pelo próprio cliente, navegação habitual"));
                if (device.location() != null && customer.city() != null && device.location().startsWith(customer.city()))
                    out.add(signal("LOCATION_CONSISTENT", w(cfg, "LOCATION_CONSISTENT"), "Localização aproximada: " + device.location()));
            } else {
                out.add(signal("NEW_DEVICE", w(cfg, "NEW_DEVICE"), device.name() + " · " + device.detail() + ", visto pela primeira vez em " + Fmt.date(device.firstSeen())));
                out.add(signal("SESSION_ANOMALY", w(cfg, "SESSION_ANOMALY"), "Sessão de poucos minutos; endereço colado no formulário"));
            }
        }

        boolean owned = in.ownsAddress();
        if (owned && (in.addressUsedBefore() || "home".equals(address.kind())))
            out.add(signal("HABITUAL_DESTINATION", w(cfg, "HABITUAL_DESTINATION"), address.label() + ": " + address.line1()));
        else if (owned)
            out.add(signal("NEW_DESTINATION_OWN", w(cfg, "NEW_DESTINATION_OWN"), address.label() + ": " + address.line1() + " — nenhuma entrega anterior"));
        else {
            out.add(signal("NEW_DESTINATION_THIRD", w(cfg, "NEW_DESTINATION_THIRD"), address.line1()
                    + (address.line2() != null && !address.line2().isBlank() ? " — " + address.line2() : "")
                    + " · sem vínculo com o cliente"));
            if ("pickup".equals(address.kind()))
                out.add(signal("PICKUP_POINT", w(cfg, "PICKUP_POINT"), address.label() + " em " + address.district() + ", " + address.city()));
        }

        if (!down.contains("merchant")) {
            List<Seller> sellers = in.sellersOrEmpty();
            if (merchant.marketplace() && !sellers.isEmpty()) {
                List<Seller> recent = sellers.stream().filter(s -> daysBetween(s.since(), at) < 60).toList();
                List<Seller> claims = sellers.stream().filter(s -> s.claimsRate() >= 3).toList();
                for (Seller s : recent)
                    out.add(signal("RECENT_SELLER", w(cfg, "RECENT_SELLER"), s.name() + " vende na " + merchant.name() + " há "
                            + Math.max(1, Math.round(daysBetween(s.since(), at))) + " dias · " + s.sales() + " vendas"));
                for (Seller s : claims)
                    out.add(signal("SELLER_HIGH_CLAIMS", w(cfg, "SELLER_HIGH_CLAIMS"), s.name() + ": " + Fmt.decimal(s.claimsRate()) + "% de reclamações"));
                boolean established = sellers.stream().allMatch(s -> daysBetween(s.since(), at) > 2 * 365
                        && s.rating() >= 4.5 && !"novo".equals(s.tier()));
                if (recent.isEmpty() && claims.isEmpty() && established)
                    out.add(signal("ESTABLISHED_SELLER", w(cfg, "ESTABLISHED_SELLER"), sellers.stream()
                            .map(s -> s.name() + " · desde " + s.since().getYear() + " · nota " + Fmt.decimal(s.rating()))
                            .collect(Collectors.joining("; "))));
            } else if (merchant.verified() && merchant.since() != null && daysBetween(merchant.since(), at) > 2 * 365) {
                out.add(signal("ESTABLISHED_SELLER", w(cfg, "ESTABLISHED_SELLER"), merchant.name() + " · estabelecimento ativo desde " + merchant.since().getYear()));
            }
        }

        String origin = in.declaredOrigin();
        if ("self".equals(origin)) out.add(signal("ORIGIN_SELF", w(cfg, "ORIGIN_SELF"), "“Encontrei por conta própria”"));
        if ("ad".equals(origin)) out.add(signal("ORIGIN_AD", w(cfg, "ORIGIN_AD"), "“Vi um anúncio ou promoção”"));
        if ("link".equals(origin)) out.add(signal("ORIGIN_LINK", w(cfg, "ORIGIN_LINK"), "“Recebi um link por mensagem”"));
        if ("guided".equals(origin)) out.add(signal("ORIGIN_GUIDED", w(cfg, "ORIGIN_GUIDED"), "“Alguém me indicou esta compra”"));

        Answers ans = in.answers();
        if (in.recalculated() && ans != null) {
            if ("asked".equals(ans.origin())) out.add(signal("VER_ASKED", w(cfg, "VER_ASKED"), "“Não, alguém me pediu”"));
            if ("phone".equals(ans.contact()) || "message".equals(ans.contact()))
                out.add(signal("VER_CONTACT", w(cfg, "VER_CONTACT"), "phone".equals(ans.contact()) ? "“Sim, por telefone”" : "“Sim, por mensagem”"));
            if ("given".equals(ans.address())) out.add(signal("VER_ADDRESS_GIVEN", w(cfg, "VER_ADDRESS_GIVEN"), "“Me passaram este endereço”"));
            boolean denial = "self".equals(ans.origin()) && "no".equals(ans.contact()) && "mine".equals(ans.address());
            if (denial) out.add(signal("VER_CLEAR", w(cfg, "VER_CLEAR"), "Nenhuma resposta indica orientação de terceiro"));
            boolean mirror = cfg.flag("script-mirror");
            if ("yes".equals(ans.coached()) && mirror)
                out.add(signal("VER_COACHED", w(cfg, "VER_COACHED"), "“Sim, alguém me disse o que responder”"));

            String script = in.coachScript() == null ? "" : in.coachScript();
            Optional<String> instruction = mirror ? Coaching.instruction(script) : Optional.empty();
            String street = Coaching.street(address.line1());
            boolean scriptNamesAddress = instruction.isPresent() && street.length() >= 8 && script.toLowerCase().contains(street);
            boolean strangerPickup = !owned && "pickup".equals(address.kind());
            if (denial && instruction.isPresent() && (scriptNamesAddress || strangerPickup))
                out.add(signal("SCRIPT_ECHO", w(cfg, "SCRIPT_ECHO"), "A mensagem pedia: “" + instruction.get()
                        + "”. As respostas negaram ajuda, que é o que esse roteiro mandou dizer."));
            if ("mine".equals(ans.address()) && strangerPickup)
                out.add(signal("EVIDENCE_DIVERGENCE", w(cfg, "EVIDENCE_DIVERGENCE"), "Cliente diz reconhecer o endereço, mas ele não está no cadastro e é um ponto de retirada"));
            if ("script".equals(ans.anchor()) && mirror && !owned) {
                String homeLine = in.homeAddress() == null ? null : in.homeAddress().line1();
                out.add(signal("ANCHOR_FROM_SCRIPT", w(cfg, "ANCHOR_FROM_SCRIPT"), homeLine != null
                        ? "A casa no cadastro é “" + homeLine + "”. A resposta escolheu “" + address.line1() + "”, o destino desta compra."
                        : "A resposta escolheu “" + address.line1() + "” como se fosse a casa do cadastro."));
            }
        }

        if (!down.contains("intelligence") && cfg.flag("intelligence-matching")) {
            for (Relation r : in.relationsOrEmpty()) {
                if ("destination".equals(r.kind()))
                    out.add(signal("DESTINATION_IN_INCIDENT", w(cfg, "DESTINATION_IN_INCIDENT"), r.reason(), r.entityId(), r.incidentIds()));
                if ("seller".equals(r.kind()) && !"não indicativa".equals(r.association()) && r.crossCustomer())
                    out.add(signal("SELLER_IN_INCIDENT", w(cfg, "SELLER_IN_INCIDENT"), r.reason(), r.entityId(), r.incidentIds()));
            }
        }
        return out;
    }

    private static int w(AssessmentInput.EngineConfig cfg, String key) {
        return Weights.value(cfg.weights(), key);
    }
}
