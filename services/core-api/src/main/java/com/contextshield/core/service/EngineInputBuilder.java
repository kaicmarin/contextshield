package com.contextshield.core.service;

import com.contextshield.core.domain.*;
import com.contextshield.core.repo.*;
import com.contextshield.core.web.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Monta, a partir do banco, o contexto completo que o risk-engine precisa.
 * O motor não tem acesso ao banco: tudo o que ele sabe vem neste JSON.
 */
@Component
public class EngineInputBuilder {

    private final CustomerRepository customers;
    private final DeviceRepository devices;
    private final AddressRepository addresses;
    private final MerchantRepository merchants;
    private final SellerRepository sellers;
    private final TransactionItemRepository items;
    private final TransactionRepository transactions;
    private final IntelligenceService intel;
    private final EngineSettings settings;
    private final RiskPolicyService policies;
    private final RiskRuleService rules;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private InternalPlatformClient platform;

    @Autowired
    void bindThreat(InternalPlatformClient platform) {
        this.platform = platform;
    }

    public EngineInputBuilder(CustomerRepository customers, DeviceRepository devices, AddressRepository addresses,
                              MerchantRepository merchants, SellerRepository sellers, TransactionItemRepository items,
                              TransactionRepository transactions, IntelligenceService intel, EngineSettings settings,
                              RiskPolicyService policies, RiskRuleService rules, ObjectMapper mapper, JdbcTemplate jdbc) {
        this.customers = customers;
        this.devices = devices;
        this.addresses = addresses;
        this.merchants = merchants;
        this.sellers = sellers;
        this.items = items;
        this.transactions = transactions;
        this.intel = intel;
        this.settings = settings;
        this.policies = policies;
        this.rules = rules;
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    private static Map<String, Object> address(Address a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("label", a.getLabel());
        m.put("line1", a.getLine1());
        m.put("line2", a.getLine2());
        m.put("district", a.getDistrict());
        m.put("city", a.getCity());
        m.put("kind", a.getKind());
        m.put("customerIds", new ArrayList<>(a.getCustomerIds()));
        return m;
    }

    public Map<String, Object> build(Transaction tx, String stage, LocalDateTime at, Map<String, String> answers) {
        Customer c = customers.findById(tx.getCustomerId()).orElseThrow(() -> ApiException.notFound("Cliente"));
        Device d = devices.findById(tx.getDeviceId()).orElseThrow(() -> ApiException.notFound("Dispositivo"));
        Address a = addresses.findById(tx.getAddressId()).orElseThrow(() -> ApiException.notFound("Endereço"));
        Merchant m = merchants.findById(tx.getMerchantId()).orElseThrow(() -> ApiException.notFound("Estabelecimento"));
        Address home = addresses.findOwnedBy(c.getId()).stream().filter(x -> "home".equals(x.getKind())).findFirst().orElse(null);
        List<String> sellerIds = items.findByTxId(tx.getId()).stream().map(TransactionItem::getSellerId).filter(Objects::nonNull).distinct().toList();

        boolean usedBefore = transactions.existsByCustomerIdAndAddressIdAndIdNotAndCreatedAtBeforeAndStatusIn(
                c.getId(), a.getId(), tx.getId(), tx.getCreatedAt(),
                List.of(TransactionStatus.APPROVED, TransactionStatus.APPROVED_WITH_ALERT, TransactionStatus.INCIDENT_RECORDED));

        Map<String, Object> in = new LinkedHashMap<>();
        in.put("stage", stage);
        in.put("at", at);
        in.put("amount", tx.getAmount());
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("id", c.getId());
        customer.put("firstName", c.getFirstName() == null ? "" : c.getFirstName());
        customer.put("city", c.getCity() == null ? "" : c.getCity());
        customer.put("since", c.getSince());
        customer.put("avgTicket90d", c.getAvgTicket90d());
        customer.put("purchases90d", c.getPurchases90d());
        in.put("customer", customer);
        Map<String, Object> dev = new LinkedHashMap<>();
        dev.put("id", d.getId());
        dev.put("name", d.getName());
        dev.put("detail", d.getDetail());
        dev.put("location", d.getLocation());
        dev.put("firstSeen", d.getFirstSeen());
        dev.put("trusted", d.isTrusted());
        in.put("device", dev);
        in.put("address", address(a));
        in.put("homeAddress", home == null ? null : address(home));
        in.put("addressUsedBefore", usedBefore);
        in.put("merchant", Map.of("id", m.getId(), "name", m.getName(), "marketplace", m.isMarketplace(),
                "verified", m.isVerified(), "since", m.getSince()));
        in.put("sellers", sellerIds.stream().map(sellers::findById).flatMap(Optional::stream).map(s -> Map.of(
                "id", s.getId(), "name", s.getName(), "since", s.getSince(), "claimsRate", s.getClaimsRate(),
                "rating", s.getRating(), "tier", s.getTier(), "sales", s.getSales())).toList());
        in.put("declaredOrigin", tx.getDeclaredOrigin());
        in.put("answers", answers);
        in.put("coachScript", tx.getCoachScript());
        long organizationId = tx.getOrganizationId() == null ? 1L : tx.getOrganizationId();
        List<Map<String, Object>> relations = new ArrayList<>(intel.relationsFor(tx, at));
        if (platform != null && platform.threatEnabled()) {
            for (String sid : sellerIds) {
                try {
                    Map<String, Object> match = platform.matchIndicator(organizationId, "seller", sid, false);
                    if (Boolean.TRUE.equals(match.get("matched")) && "CONFIRMED".equals(match.get("status"))) {
                        Map<String, Object> relation = new LinkedHashMap<>();
                        relation.put("kind", "confirmed-indicator");
                        relation.put("entityId", sid);
                        relation.put("reason", "Indicador de vendedor confirmado pela inteligência de ameaças.");
                        relation.put("association", "forte");
                        relation.put("crossCustomer", false);
                        relation.put("incidentIds", List.of());
                        relations.add(relation);
                    }
                } catch (RuntimeException ignored) {
                    // Serviço fora do ar não vira prova de segurança.
                }
            }
        }
        in.put("relations", relations);
        Map<String, Object> config = new LinkedHashMap<>(settings.config());
        config.put("weights", policies.activeWeights(policies.activeProfile(), organizationId));
        String probes = settings.text("context-probes");
        if (probes != null && !probes.isBlank()) {
            config.put("contextProbes", Arrays.stream(probes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        }
        capContextProbe(config, tx.getCustomerId());
        config.put("rules", rules.activeRulePayload(organizationId));
        in.put("config", config);
        in.put("unavailable", tx.getUnavailable() == null || tx.getUnavailable().isBlank()
                ? List.of() : Arrays.asList(tx.getUnavailable().split(",")));
        return in;
    }

    @SuppressWarnings("unchecked")
    private void capContextProbe(Map<String, Object> config, String customerId) {
        Object raw = config.get("flags");
        if (!(raw instanceof Map<?, ?> flags) || !Boolean.TRUE.equals(flags.get("context-when-unknown"))) return;
        int max = Math.max(0, settings.number("context-probe-max", 1));
        int hours = Math.max(1, settings.number("context-probe-hours", 24));
        Integer recent = jdbc.queryForObject("""
                select count(*) from verification v
                join transactions t on t.id = v.tx_id
                where t.customer_id = ? and v.requested_at is not null and v.requested_at >= ?
                """, Integer.class, customerId, Timestamp.valueOf(LocalDateTime.now().minusHours(hours)));
        int seen = recent == null ? 0 : recent;
        if (seen < max) return;
        Map<String, Object> copy = new LinkedHashMap<>();
        flags.forEach((key, value) -> copy.put(String.valueOf(key), value));
        copy.put("context-when-unknown", false);
        config.put("flags", copy);
        config.put("contextProbeSuppressed", true);
        config.put("contextProbeRecent", seen);
    }

    public Map<String, String> readAnswers(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
