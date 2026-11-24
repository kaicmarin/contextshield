package com.contextshield.core.service;

import com.contextshield.core.domain.*;
import com.contextshield.core.repo.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** Converte entidades em respostas da API: uma visão para o cliente e outra, completa, para o analista. */
@Service
public class Views {

    private final TransactionItemRepository items;
    private final AssessmentRecordRepository assessments;
    private final VerificationRepository verifications;
    private final StatusChangeRepository statusLog;
    private final AddressRepository addresses;
    private final DeviceRepository devices;
    private final CustomerRepository customers;
    private final MerchantRepository merchants;
    private final IncidentRepository incidents;
    private final ProductRepository products;
    private final MachineLearningService learning;
    private final ObjectMapper mapper;

    public Views(TransactionItemRepository items, AssessmentRecordRepository assessments, VerificationRepository verifications,
                 StatusChangeRepository statusLog, AddressRepository addresses, DeviceRepository devices,
                 CustomerRepository customers, MerchantRepository merchants, IncidentRepository incidents,
                 ProductRepository products, MachineLearningService learning, ObjectMapper mapper) {
        this.items = items;
        this.assessments = assessments;
        this.verifications = verifications;
        this.statusLog = statusLog;
        this.addresses = addresses;
        this.devices = devices;
        this.customers = customers;
        this.merchants = merchants;
        this.incidents = incidents;
        this.products = products;
        this.learning = learning;
        this.mapper = mapper;
    }

    public record Item(String productId, String name, int quantity, BigDecimal unitPrice, String image) {}

    public record TxSummary(String id, String customerId, String customerName, String description, BigDecimal amount,
                            String status, Integer score, String band, String decision, String ruleId, String fragility,
                            String declaredOrigin, String scenario, LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record ClientSignal(String label, String detail, int points) {}

    public record CustomerTx(String id, String description, BigDecimal amount, String status, List<Item> items,
                             Map<String, Object> address, String merchant, LocalDateTime createdAt,
                             List<ClientSignal> reasons, JsonNode verification, Integer score, String band) {}

    public record OpsTx(TxSummary summary, List<Item> items, Map<String, Object> address, Map<String, Object> device,
                        Map<String, Object> customer, String merchant, String coachScript, List<JsonNode> assessments,
                        Map<String, String> answers, String verificationState, String verificationReason,
                        List<StatusChange> statusLog, List<Incident> incidents,
                        List<Map<String, Object>> shadow) {}

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    public List<Item> items(String txId) {
        return items.findByTxId(txId).stream().map(i -> new Item(i.getProductId(), i.getName(), i.getQuantity(), i.getUnitPrice(),
                i.getProductId() == null ? null : products.findById(i.getProductId()).map(Product::getImage).orElse(null))).toList();
    }

    public Map<String, Object> address(String id) {
        return addresses.findById(id).map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("label", a.getLabel());
            m.put("line1", a.getLine1());
            m.put("line2", a.getLine2());
            m.put("district", a.getDistrict());
            m.put("city", a.getCity());
            m.put("zip", a.getZip());
            m.put("kind", a.getKind());
            m.put("owners", a.getCustomerIds());
            return m;
        }).orElse(Map.of());
    }

    public TxSummary summary(Transaction t) {
        var last = assessments.findFirstByTxIdOrderByIdDesc(t.getId());
        String name = customers.findById(t.getCustomerId()).map(Customer::getName).orElse(t.getCustomerId());
        return new TxSummary(t.getId(), t.getCustomerId(), name, t.getDescription(), t.getAmount(), t.getStatus().name(),
                last.map(AssessmentRecord::getScore).orElse(null), last.map(AssessmentRecord::getBand).orElse(null),
                last.map(AssessmentRecord::getDecision).orElse(null), last.map(AssessmentRecord::getRuleId).orElse(null),
                last.map(AssessmentRecord::getFragility).orElse(null), t.getDeclaredOrigin(), t.getScenario(),
                t.getCreatedAt(), t.getUpdatedAt());
    }

    /** Até 4 motivos que o cliente entende, com os sinais de roteiro sempre na frente (clientSignals do protótipo). */
    private List<ClientSignal> reasons(JsonNode payload) {
        List<JsonNode> ranked = new ArrayList<>();
        payload.path("signals").forEach(s -> {
            if (s.hasNonNull("clientLabel") && s.path("points").asInt() > 0) ranked.add(s);
        });
        ranked.sort((a, b) -> b.path("points").asInt() - a.path("points").asInt());
        Set<String> pinnedIds = Set.of("SCRIPT_ECHO", "VER_COACHED", "ANCHOR_FROM_SCRIPT");
        List<JsonNode> pinned = ranked.stream().filter(s -> pinnedIds.contains(s.path("id").asText())).toList();
        List<JsonNode> ordered = new ArrayList<>(pinned);
        ranked.stream().filter(s -> !pinned.contains(s)).forEach(ordered::add);
        return ordered.stream().limit(Math.max(4, pinned.size()))
                .map(s -> new ClientSignal(s.path("clientLabel").asText(), s.path("detail").asText(), s.path("points").asInt())).toList();
    }

    public CustomerTx forCustomer(Transaction t) {
        var last = assessments.findFirstByTxIdOrderByIdDesc(t.getId());
        JsonNode payload = last.map(a -> parse(a.getPayload())).orElse(mapper.createObjectNode());
        JsonNode verification = null;
        if (t.getStatus() == TransactionStatus.CONTEXT_REQUIRED) {
            JsonNode plan = payload.path("verification");
            if (!plan.isMissingNode()) {
                var v = mapper.createObjectNode();
                v.set("situation", plan.path("situation"));
                v.set("questions", plan.path("questions"));
                verification = v;
            }
        }
        String merchant = merchants.findById(t.getMerchantId()).map(Merchant::getName).orElse("");
        return new CustomerTx(t.getId(), t.getDescription(), t.getAmount(), t.getStatus().name(), items(t.getId()),
                address(t.getAddressId()), merchant, t.getCreatedAt(), reasons(payload), verification,
                last.map(AssessmentRecord::getScore).orElse(null), last.map(AssessmentRecord::getBand).orElse(null));
    }

    public OpsTx forOps(Transaction t) {
        List<JsonNode> list = assessments.findByTxIdOrderByIdAsc(t.getId()).stream().map(a -> {
            JsonNode n = parse(a.getPayload());
            if (n.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) n).put("recordId", a.getId());
            return n;
        }).toList();
        Verification found = verifications.findById(t.getId()).orElse(null);
        String verificationState = found == null || found.getState() == null ? "NOT_REQUIRED" : found.getState();
        String verificationReason = found == null ? null : found.getReason();
        Map<String, String> answers = null;
        if (found != null && found.getAnswers() != null && !found.getAnswers().isBlank()) {
            try {
                answers = mapper.readValue(found.getAnswers(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            } catch (Exception e) {
                answers = Map.of();
            }
        }
        Map<String, Object> device = devices.findById(t.getDeviceId()).map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("name", d.getName());
            m.put("detail", d.getDetail());
            m.put("location", d.getLocation());
            m.put("trusted", d.isTrusted());
            m.put("firstSeen", d.getFirstSeen());
            return m;
        }).orElse(Map.of());
        Map<String, Object> customer = customers.findById(t.getCustomerId()).map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("city", c.getCity());
            m.put("since", c.getSince());
            m.put("segment", c.getSegment());
            m.put("avgTicket90d", c.getAvgTicket90d());
            m.put("purchases90d", c.getPurchases90d());
            return m;
        }).orElse(Map.of());
        String merchant = merchants.findById(t.getMerchantId()).map(Merchant::getName).orElse("");
        return new OpsTx(summary(t), items(t.getId()), address(t.getAddressId()), device, customer, merchant, t.getCoachScript(),
                list, answers, verificationState, verificationReason, statusLog.findByTxIdOrderByIdAsc(t.getId()), incidents.findByTxId(t.getId()),
                learning.rows(t.getId()));
    }

    public ArrayNode emptyArray() {
        return mapper.createArrayNode();
    }
}
