package com.contextshield.core.service;

import com.contextshield.core.config.CurrentUser;
import com.contextshield.core.domain.*;
import com.contextshield.core.repo.*;
import com.contextshield.core.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Fluxo da compra: cria → avalia no risk-engine → aplica a decisão → (verificação) → reavalia → resolve.
 * Toda mudança de status passa por transition(), que respeita a máquina de estados e grava o histórico.
 */
@Service
public class TransactionService {

    public static final String NEXA = "MER-001";

    private final TransactionRepository txs;
    private final TransactionItemRepository items;
    private final AssessmentRecordRepository assessments;
    private final VerificationRepository verifications;
    private final StatusChangeRepository statusLog;
    private final ProductRepository products;
    private final DeviceRepository devices;
    private final AddressRepository addresses;
    private final CardRepository cards;
    private final EngineInputBuilder inputs;
    private final RiskEngineClient engine;
    private final AlertService alerts;
    private final AuditService audit;
    private final IntelligenceService intel;
    private final MachineLearningService learning;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TransactionService(TransactionRepository txs, TransactionItemRepository items, AssessmentRecordRepository assessments,
                              VerificationRepository verifications, StatusChangeRepository statusLog, ProductRepository products,
                              DeviceRepository devices, AddressRepository addresses, CardRepository cards,
                              EngineInputBuilder inputs, RiskEngineClient engine, AlertService alerts, AuditService audit,
                              IntelligenceService intel, MachineLearningService learning, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.txs = txs;
        this.items = items;
        this.assessments = assessments;
        this.verifications = verifications;
        this.statusLog = statusLog;
        this.products = products;
        this.devices = devices;
        this.addresses = addresses;
        this.cards = cards;
        this.inputs = inputs;
        this.engine = engine;
        this.alerts = alerts;
        this.audit = audit;
        this.intel = intel;
        this.learning = learning;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record Line(String productId, int quantity) {}

    public record Draft(String customerId, String deviceId, String addressId, List<Line> items, String declaredOrigin,
                        String coachScript, String scenario, String merchantId, BigDecimal amount, String description,
                        String unavailable, LocalDateTime at, Long fixedSeq) {}

    public static String txId(long seq) {
        return String.format("TX-2026-%06d", seq);
    }

    private long nextSeq() {
        Long n = jdbc.queryForObject("select nextval('tx_seq')", Long.class);
        return n == null ? System.currentTimeMillis() : n;
    }

    // ---------------------------------------------------------------- criação + primeira avaliação

    @Transactional
    public Transaction create(Draft d, String actor) {
        var device = devices.findById(d.deviceId()).orElseThrow(() -> ApiException.badRequest("Dispositivo inválido"));
        if (!device.getCustomerId().equals(d.customerId())) throw ApiException.badRequest("Este dispositivo não pertence ao cliente");
        var address = addresses.findById(d.addressId()).orElseThrow(() -> ApiException.badRequest("Endereço de entrega inválido"));
        if (d.declaredOrigin() != null && !Set.of("self", "ad", "link", "guided").contains(d.declaredOrigin()))
            throw ApiException.badRequest("Origem declarada inválida");

        LocalDateTime at = d.at() != null ? d.at() : LocalDateTime.now().withNano(0);
        Transaction tx = new Transaction();
        tx.setId(txId(d.fixedSeq() != null ? d.fixedSeq() : nextSeq()));
        tx.setCustomerId(d.customerId());
        tx.setMerchantId(d.merchantId() != null ? d.merchantId() : NEXA);
        tx.setDeviceId(device.getId());
        tx.setAddressId(address.getId());
        tx.setCardId(cards.findByCustomerId(d.customerId()).stream().findFirst().map(Card::getId).orElse(null));
        tx.setDeclaredOrigin(d.declaredOrigin());
        tx.setCoachScript(d.coachScript() == null || d.coachScript().isBlank() ? null : d.coachScript().trim());
        tx.setScenario(d.scenario());
        tx.setUnavailable(d.unavailable());
        tx.setStatus(TransactionStatus.RECEIVED);
        tx.setCreatedAt(at);
        tx.setUpdatedAt(at);

        List<TransactionItem> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        if (d.items() != null && !d.items().isEmpty()) {
            for (Line l : d.items()) {
                if (l.quantity() < 1 || l.quantity() > 10) throw ApiException.badRequest("Quantidade deve ficar entre 1 e 10");
                Product p = products.findById(l.productId()).orElseThrow(() -> ApiException.badRequest("Produto inexistente: " + l.productId()));
                TransactionItem it = new TransactionItem();
                it.setTxId(tx.getId());
                it.setProductId(p.getId());
                it.setSellerId(p.getSellerId());
                it.setName(p.getName());
                it.setQuantity(l.quantity());
                it.setUnitPrice(p.getPrice());
                lines.add(it);
                total = total.add(p.getPrice().multiply(BigDecimal.valueOf(l.quantity())));
            }
            String first = lines.get(0).getName();
            tx.setDescription(lines.size() == 1 ? first : first + " e mais " + (lines.size() - 1));
        } else {
            if (d.amount() == null || d.amount().signum() <= 0) throw ApiException.badRequest("Informe itens ou um valor positivo");
            total = d.amount();
            tx.setDescription(d.description());
        }
        tx.setAmount(total.setScale(2, RoundingMode.HALF_UP));
        txs.save(tx);
        items.saveAll(lines);
        record(tx, null, TransactionStatus.RECEIVED, "Pedido recebido do checkout", actor, at);
        transition(tx, TransactionStatus.ANALYZING, "Enviado ao motor de risco", "Sistema", at);
        audit.log(actor, "TRANSACTION_CREATED", tx.getId(), tx.getDescription() + " · " + AlertService.brl(tx.getAmount()), at);
        try {
            return evaluate(tx, "initial", null, at);
        } catch (RiskEngineClient.EngineUnavailable e) {
            // Falha segura: a compra fica gravada em ANALYZING, sem aprovação. O cliente pode tentar de novo.
            audit.log("core-api", "ENGINE_UNAVAILABLE", tx.getId(), "Compra mantida em análise", at);
            return tx;
        }
    }

    /** Chama o risk-engine, grava a avaliação e aplica a decisão como novo status. */
    private Transaction evaluate(Transaction tx, String stage, Map<String, String> answers, LocalDateTime at) {
        Map<String, Object> input = inputs.build(tx, stage, at, answers);
        JsonNode a = engine.assess(input);
        AssessmentRecord r = new AssessmentRecord();
        r.setTxId(tx.getId());
        r.setStage(stage);
        r.setScore(a.path("score").asInt());
        r.setBand(a.path("band").asText());
        r.setLevel(a.path("level").asText());
        r.setConfidence(a.path("confidence").asInt());
        r.setProposedDecision(a.path("proposedDecision").asText());
        r.setDecision(a.path("decision").asText());
        r.setRuleId(a.path("ruleId").asText());
        r.setRuleText(a.path("ruleText").asText());
        r.setFragility(a.path("certificate").path("status").asText(null));
        r.setDurationMs(a.path("durationMs").asDouble());
        r.setEngine(a.path("engine").asText());
        r.setPayload(a.toString());
        r.setCreatedAt(at);
        assessments.save(r);

        TransactionStatus to = TransactionStatus.fromDecision(r.getDecision());
        transition(tx, to, r.getRuleText(), "Motor de risco", at);
        audit.log("risk-engine", "DECISION", tx.getId(), r.getDecision() + " · score " + r.getScore() + " · " + r.getRuleId(), at);
        intel.recordAttempt(tx);
        learning.shadow(tx.getId(), input, r.getDecision(), stage);
        if ("REQUEST_CONTEXT".equals(r.getDecision())) openVerification(tx.getId(), r.getRuleText(), at);
        return tx;
    }

    private void openVerification(String txId, String reason, LocalDateTime at) {
        Verification v = verifications.findById(txId).orElseGet(Verification::new);
        v.setTxId(txId);
        v.setState("REQUESTED");
        v.setReason(reason);
        v.setRequestedAt(at);
        v.setExpiresAt(at.plusMinutes(30));
        verifications.save(v);
    }

    // ---------------------------------------------------------------- verificação e intervenção

    private static final Map<String, Set<String>> VALID_ANSWERS = Map.of(
            "origin", Set.of("self", "asked"),
            "contact", Set.of("no", "phone", "message"),
            "address", Set.of("mine", "given"),
            "coached", Set.of("yes", "no"),
            "anchor", Set.of("home", "script", "unsure"),
            "device", Set.of("mine", "other", "unsure"),
            "amount", Set.of("chose", "asked", "unsure"));

    @Transactional
    public Transaction verify(String txId, Map<String, String> answers, CurrentUser user, LocalDateTime when) {
        Transaction tx = forCustomer(txId, user);
        if (tx.getStatus() != TransactionStatus.CONTEXT_REQUIRED)
            throw ApiException.conflict("Esta compra não está aguardando verificação (status " + tx.getStatus() + ")");
        if (answers == null || answers.isEmpty()) throw ApiException.badRequest("Responda pelo menos uma pergunta");
        LocalDateTime at = when != null ? when : LocalDateTime.now().withNano(0);
        Verification existing = verifications.findById(tx.getId()).orElse(null);
        if (existing != null && "EXPIRED".equals(existing.getState()))
            throw ApiException.conflict("A verificação expirou. A falta de resposta não libera a compra.");
        if (existing != null && existing.getExpiresAt() != null && existing.getExpiresAt().isBefore(at)) {
            existing.setState("EXPIRED");
            verifications.save(existing);
            throw ApiException.conflict("A verificação expirou. A falta de resposta não libera a compra.");
        }
        Map<String, String> clean = new LinkedHashMap<>();
        answers.forEach((k, v) -> {
            if (!VALID_ANSWERS.containsKey(k) || !VALID_ANSWERS.get(k).contains(v))
                throw ApiException.badRequest("Resposta inválida para " + k);
            clean.put(k, v);
        });
        boolean complete = clean.keySet().containsAll(Set.of("origin", "contact", "address", "coached"));
        Verification v = existing == null ? new Verification() : existing;
        v.setTxId(tx.getId());
        try {
            v.setAnswers(mapper.writeValueAsString(clean));
        } catch (Exception e) {
            throw ApiException.badRequest("Respostas inválidas");
        }
        v.setAnsweredAt(at);
        v.setState(complete ? "ANSWERED" : "INCONCLUSIVE");
        verifications.save(v);
        audit.log(user.email(), "VERIFICATION_ANSWERED", tx.getId(), (complete ? "completa" : "incompleta"), at);
        Transaction done = evaluate(tx, "recalculated", clean, at);
        v.setState(complete ? "COMPLETED" : "INCONCLUSIVE");
        verifications.save(v);
        return done;
    }

    @Transactional
    public Transaction resolve(String txId, String choice, CurrentUser user, LocalDateTime when) {
        Transaction tx = forCustomer(txId, user);
        LocalDateTime at = when != null ? when : LocalDateTime.now().withNano(0);
        switch (choice) {
            case "cancel" -> {
                if (tx.getStatus() != TransactionStatus.INTERVENTION && tx.getStatus() != TransactionStatus.CONTEXT_REQUIRED)
                    throw ApiException.conflict("Só é possível cancelar uma compra pausada ou aguardando confirmação");
                transition(tx, TransactionStatus.CANCELLED, "Cliente interrompeu a compra", user.name(), at);
            }
            case "proceed" -> {
                if (tx.getStatus() != TransactionStatus.INTERVENTION)
                    throw ApiException.conflict("Só uma compra pausada pode seguir com confirmação");
                transition(tx, TransactionStatus.APPROVED_WITH_ALERT, "Cliente confirmou que a compra é dele após o alerta", user.name(), at);
            }
            default -> throw ApiException.badRequest("Escolha 'cancel' ou 'proceed'");
        }
        audit.log(user.email(), "CUSTOMER_" + choice.toUpperCase(), tx.getId(), null, at);
        return tx;
    }

    /** Tentativa de nova análise quando o motor estava fora do ar (status ainda ANALYZING). */
    @Transactional
    public Transaction retry(String txId, CurrentUser user) {
        Transaction tx = forCustomer(txId, user);
        if (tx.getStatus() != TransactionStatus.ANALYZING) throw ApiException.conflict("Esta compra já foi analisada");
        return evaluate(tx, "initial", null, LocalDateTime.now().withNano(0));
    }

    // ---------------------------------------------------------------- status

    public void transition(Transaction tx, TransactionStatus to, String reason, String actor, LocalDateTime at) {
        TransactionStatus from = tx.getStatus();
        if (from == to) throw ApiException.conflict("A compra já está em " + to + " (evento repetido)");
        if (!from.canGoTo(to))
            throw ApiException.conflict(from.isTerminal()
                    ? from + " é final; " + to + " não pode reabrir a compra"
                    : "Transição " + from + " → " + to + " não existe");
        if (from == TransactionStatus.CONTEXT_REQUIRED && (to == TransactionStatus.APPROVED || to == TransactionStatus.APPROVED_WITH_ALERT)) {
            var last = assessments.findFirstByTxIdOrderByIdDesc(tx.getId());
            if (last.isEmpty() || !"recalculated".equals(last.get().getStage()))
                throw ApiException.conflict("Aprovar depois de pedir contexto exige risco recalculado");
            if (to == TransactionStatus.APPROVED && !"LOW".equals(last.get().getBand()))
                throw ApiException.conflict("Aprovação direta exige risco recalculado baixo");
        }
        tx.setStatus(to);
        tx.setUpdatedAt(at);
        txs.save(tx);
        record(tx, from, to, reason, actor, at);
        alerts.onStatus(tx, to, at);
    }

    private void record(Transaction tx, TransactionStatus from, TransactionStatus to, String reason, String actor, LocalDateTime at) {
        StatusChange c = new StatusChange();
        c.setTxId(tx.getId());
        c.setFromStatus(from == null ? null : from.name());
        c.setToStatus(to.name());
        c.setReason(reason);
        c.setActor(actor);
        c.setAt(at);
        statusLog.save(c);
    }

    @Transactional
    public Transaction analystTransition(String txId, String to, String reason, CurrentUser user) {
        Transaction tx = txs.findById(txId).orElseThrow(() -> ApiException.notFound("Transação"));
        TransactionStatus target;
        try {
            target = TransactionStatus.valueOf(to);
        } catch (Exception e) {
            throw ApiException.badRequest("Status desconhecido: " + to);
        }
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("Informe o motivo da mudança");
        LocalDateTime at = LocalDateTime.now().withNano(0);
        transition(tx, target, reason, user.name(), at);
        audit.log(user.email(), "STATUS_CHANGED", txId, tx.getStatus() + " · " + reason, at);
        return tx;
    }

    public Transaction forCustomer(String txId, CurrentUser user) {
        Transaction tx = txs.findById(txId).orElseThrow(() -> ApiException.notFound("Transação"));
        if (!user.isStaff() && !tx.getCustomerId().equals(user.customerId())) throw ApiException.notFound("Transação");
        return tx;
    }
}
