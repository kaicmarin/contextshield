package com.contextshield.risk.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Tudo o que o motor precisa saber sobre uma compra. Quem monta este objeto é a core-api,
 * a partir do banco. O motor não consulta nada por fora.
 */
public record AssessmentInput(
        @NotBlank String stage,                 // "initial" ou "recalculated"
        @NotNull LocalDateTime at,
        @Positive double amount,
        @NotNull @Valid Customer customer,
        @NotNull @Valid Device device,
        @NotNull @Valid Address address,
        Address homeAddress,
        boolean addressUsedBefore,
        @NotNull @Valid Merchant merchant,
        List<Seller> sellers,
        String declaredOrigin,                  // self | ad | link | guided
        Answers answers,
        String coachScript,                     // mensagem que o cliente recebeu de fora, se houver
        List<Relation> relations,
        EngineConfig config,
        List<String> unavailable
) {
    public record Customer(@NotBlank String id, String firstName, String city, LocalDate since,
                           double avgTicket90d, int purchases90d) {}

    public record Device(String id, String name, String detail, String location,
                         LocalDateTime firstSeen, boolean trusted) {}

    public record Address(@NotBlank String id, String label, String line1, String line2, String district,
                          String city, String kind, List<String> customerIds) {}

    public record Merchant(String id, String name, boolean marketplace, boolean verified, LocalDate since) {}

    public record Seller(String id, String name, LocalDate since, double claimsRate, double rating,
                         String tier, int sales) {}

    public record Answers(String origin, String contact, String address, String coached,
                          String anchor, String device, String amount) {}

    /** Relação vinda da inteligência: destino ou vendedor citado em relato de outro cliente. */
    public record Relation(String kind, String entityId, String reason, String association,
                           boolean crossCustomer, List<String> incidentIds) {}

    public record EngineConfig(Map<String, Boolean> flags, Map<String, String> providers, Map<String, Integer> weights,
                               List<String> contextProbes, List<DynamicRule> rules) {
        public EngineConfig {
            if (weights == null) weights = Map.of();
            if (contextProbes == null) contextProbes = List.of();
            if (rules == null) rules = List.of();
        }

        public EngineConfig(Map<String, Boolean> flags, Map<String, String> providers) {
            this(flags, providers, Map.of(), List.of(), List.of());
        }

        public EngineConfig(Map<String, Boolean> flags, Map<String, String> providers, Map<String, Integer> weights) {
            this(flags, providers, weights, List.of(), List.of());
        }

        public EngineConfig(Map<String, Boolean> flags, Map<String, String> providers, Map<String, Integer> weights,
                            List<String> contextProbes) {
            this(flags, providers, weights, contextProbes, List.of());
        }

        public static EngineConfig defaults() {
            return new EngineConfig(Map.of(
                    "intelligence-matching", true,
                    "dependency-discount", true,
                    "context-on-declared-guidance", true,
                    "guidance-floor", true,
                    "script-mirror", true
            ), Map.of("history", "up", "device", "up", "merchant", "up", "intelligence", "up"),
                    Map.of(), List.of(), List.of());
        }

        public boolean flag(String id) {
            return flags != null && Boolean.TRUE.equals(flags.get(id));
        }
    }

    public AssessmentInput withConfig(EngineConfig config) {
        return new AssessmentInput(stage, at, amount, customer, device, address, homeAddress, addressUsedBefore,
                merchant, sellers, declaredOrigin, answers, coachScript, relations, config, unavailable);
    }

    public AssessmentInput withStage(String stage, Answers answers) {
        return new AssessmentInput(stage, at, amount, customer, device, address, homeAddress, addressUsedBefore,
                merchant, sellers, declaredOrigin, answers, coachScript, relations, config, unavailable);
    }

    public EngineConfig configOrDefault() {
        return config == null ? EngineConfig.defaults() : config;
    }

    public List<Seller> sellersOrEmpty() {
        return sellers == null ? List.of() : sellers;
    }

    public List<Relation> relationsOrEmpty() {
        return relations == null ? List.of() : relations;
    }

    public boolean recalculated() {
        return "recalculated".equals(stage);
    }

    public boolean ownsAddress() {
        return address.customerIds() != null && address.customerIds().contains(customer.id());
    }
}
