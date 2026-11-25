package com.contextshield.core.web;

import com.contextshield.core.config.AppProperties;
import com.contextshield.core.config.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import com.contextshield.core.domain.*;
import com.contextshield.core.repo.*;
import com.contextshield.core.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** Painel do analista (papel ANALYST ou ADMIN). */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final TransactionRepository txs;
    private final TransactionService txService;
    private final IncidentRepository incidents;
    private final IncidentService incidentService;
    private final IntelligenceService intel;
    private final AuditEntryRepository auditRepo;
    private final AuditService audit;
    private final EngineSettings settings;
    private final MetricsService metrics;
    private final CustomerRepository customers;
    private final AlertService alerts;
    private final SeedService seed;
    private final ReferenceData ref;
    private final DeviceRepository devices;
    private final Views views;
    private final OrganizationService organizations;

    private final QueueService queue;
    private final PolicySimulation simulation;
    private final AppProperties props;

    public OpsController(TransactionRepository txs, TransactionService txService, IncidentRepository incidents,
                         IncidentService incidentService, IntelligenceService intel, AuditEntryRepository auditRepo,
                         AuditService audit, EngineSettings settings, MetricsService metrics, CustomerRepository customers,
                         AlertService alerts, SeedService seed, ReferenceData ref, DeviceRepository devices, Views views,
                         OrganizationService organizations, QueueService queue, PolicySimulation simulation,
                         AppProperties props) {
        this.txs = txs;
        this.txService = txService;
        this.incidents = incidents;
        this.incidentService = incidentService;
        this.intel = intel;
        this.auditRepo = auditRepo;
        this.audit = audit;
        this.settings = settings;
        this.metrics = metrics;
        this.customers = customers;
        this.alerts = alerts;
        this.seed = seed;
        this.ref = ref;
        this.devices = devices;
        this.views = views;
        this.organizations = organizations;
        this.queue = queue;
        this.simulation = simulation;
        this.props = props;
    }

    @GetMapping("/verifications")
    public Map<String, Object> verifications() {
        return metrics.verificationSummary(organizations.idOf(Auth.user()));
    }

    @GetMapping("/engine/context-probe")
    public Map<String, Object> contextProbe() {
        return simulation.contextProbeImpact(Auth.user());
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(required = false) Integer days,
                                        @RequestParam(required = false) String band) {
        if (days != null && (days < 0 || days > MetricsService.MAX_DAYS))
            throw ApiException.badRequest("O período precisa estar entre 0 e " + MetricsService.MAX_DAYS + " dias");
        if (band != null && !band.isBlank() && !Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(band))
            throw ApiException.badRequest("Nível de risco desconhecido");
        Map<String, Object> m = new LinkedHashMap<>(metrics.overview(days, band, organizations.idOf(Auth.user())));
        m.put("seed", seed.state());
        return m;
    }

    // ------------------------------------------------------------ transações

    @GetMapping("/transactions")
    public Map<String, Object> transactions(@RequestParam(required = false) String status,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(required = false) Integer days,
                                            @RequestParam(required = false) String band,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "25") int size,
                                            @RequestParam(defaultValue = "createdAt") String sort,
                                            @RequestParam(defaultValue = "desc") String dir) {
        if (page < 0) throw ApiException.badRequest("A página começa em 0");
        if (size < 1 || size > 100) throw ApiException.badRequest("O tamanho da página fica entre 1 e 100");
        if (days != null && (days < 0 || days > MetricsService.MAX_DAYS))
            throw ApiException.badRequest("O período precisa estar entre 0 e " + MetricsService.MAX_DAYS + " dias");
        if (band != null && !band.isBlank() && !Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(band))
            throw ApiException.badRequest("Nível de risco desconhecido");
        if (status != null && !status.isBlank()
                && !QueueService.GROUPS.containsKey(status)
                && !Set.of("RECEIVED", "ANALYZING", "APPROVED", "APPROVED_WITH_ALERT", "CONTEXT_REQUIRED",
                "INTERVENTION", "BLOCKED", "CANCELLED", "INCIDENT_RECORDED").contains(status))
            throw ApiException.badRequest("Status desconhecido: " + status);
        long organizationId = organizations.idOf(Auth.user());
        Map<String, Object> result = queue.page(status, q, days, band, page, size, sort, dir, organizationId);
        if (sqlMissesRows(result, q, days, band)) {
            var loaded = txs.findTop300ByOrderByCreatedAtDesc().stream()
                    .filter(t -> organizationId == t.getOrganizationId())
                    .map(views::summary).toList();
            if (!loaded.isEmpty()) fillFromEntities(result, loaded, status, page, size);
        }
        return result;
    }

    /** A consulta SQL não viu compras que o repositório vê. A fila usa as entidades e aplica o mesmo filtro. */
    private boolean sqlMissesRows(Map<String, Object> result, String q, Integer days, String band) {
        if (q != null && !q.isBlank() || days != null && days > 0 || band != null && !band.isBlank()) return false;
        Object counts = result.get("counts");
        long seen = 0;
        if (counts instanceof Map<?, ?> map && map.get("ALL") instanceof Number n) seen = n.longValue();
        return seen == 0 && txs.count() > 0;
    }

    private void fillFromEntities(Map<String, Object> result, List<Views.TxSummary> loaded, String status, int page, int size) {
        List<Views.TxSummary> filtered = loaded.stream().filter(t -> statusMatches(t.status(), status)).toList();
        int from = Math.min(page * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("ALL", (long) loaded.size());
        counts.put("ATENCAO", loaded.stream().filter(t -> statusMatches(t.status(), "ATENCAO")).count());
        counts.put("AGUARDANDO", loaded.stream().filter(t -> statusMatches(t.status(), "AGUARDANDO")).count());
        loaded.stream().collect(java.util.stream.Collectors.groupingBy(Views.TxSummary::status, java.util.stream.Collectors.counting()))
                .forEach(counts::put);
        result.put("total", filtered.size());
        result.put("pages", (int) Math.ceil(filtered.size() / (double) size));
        result.put("items", filtered.subList(from, to));
        result.put("counts", counts);
    }

    private static boolean statusMatches(String value, String status) {
        if (status == null || status.isBlank()) return true;
        var group = QueueService.GROUPS.get(status);
        return group != null ? group.contains(value) : status.equals(value);
    }

    @GetMapping("/transactions/{id}")
    public Views.OpsTx transaction(@PathVariable String id) {
        Transaction tx = txs.findById(id).orElseThrow(() -> ApiException.notFound("Transação"));
        organizations.requireSame(Auth.user(), tx.getOrganizationId());
        return views.forOps(tx);
    }

    public record TransitionRequest(@NotBlank String to, @NotBlank @Size(max = 500) String reason) {}

    @PostMapping("/transactions/{id}/transition")
    public Views.OpsTx transition(@PathVariable String id, @Valid @RequestBody TransitionRequest req) {
        Transaction tx = txs.findById(id).orElseThrow(() -> ApiException.notFound("Transação"));
        organizations.requireSame(Auth.user(), tx.getOrganizationId());
        return views.forOps(txService.analystTransition(id, req.to(), req.reason(), Auth.user()));
    }

    // ------------------------------------------------------------ relatos

    @GetMapping("/incidents")
    public List<Incident> incidents(@RequestParam(required = false) String status) {
        long organizationId = organizations.idOf(Auth.user());
        var all = incidents.findAllByOrderByCreatedAtDesc().stream()
                .filter(i -> organizationId == i.getOrganizationId())
                .toList();
        return status == null || status.isBlank() ? all : all.stream().filter(i -> i.getStatus().equals(status)).toList();
    }

    @GetMapping("/incidents/{id}")
    public Map<String, Object> incident(@PathVariable String id) {
        Incident incident = incidents.findById(id).orElseThrow(() -> ApiException.notFound("Relato"));
        organizations.requireSame(Auth.user(), incident.getOrganizationId(), "Relato");
        return incidentView(incident);
    }

    public record ReplyRequest(@NotBlank @Size(max = 2000) String body) {}

    @PostMapping("/incidents/{id}/replies")
    public Map<String, Object> reply(@PathVariable String id, @Valid @RequestBody ReplyRequest req) {
        Incident current = incidents.findById(id).orElseThrow(() -> ApiException.notFound("Relato"));
        organizations.requireSame(Auth.user(), current.getOrganizationId(), "Relato");
        Incident updated = incidentService.reply(id, req.body(), Auth.user());
        return incidentView(updated);
    }

    private Map<String, Object> incidentView(Incident i) {
        var customer = customers.findById(i.getCustomerId()).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("incident", i);
        out.put("customer", customer);
        out.put("transaction", i.getTxId() == null ? null : txs.findById(i.getTxId()).map(views::summary).orElse(null));
        out.put("entities", intel.list().stream().filter(e -> e.incidentIds().contains(i.getId())).toList());
        out.put("replies", incidentService.replies(i));
        out.put("attendance", incidentService.attendance(i, customer == null ? null : customer.getName()));
        return out;
    }

    @PostMapping("/incidents/{id}/assign")
    public Incident assign(@PathVariable String id) {
        Incident current = incidents.findById(id).orElseThrow(() -> ApiException.notFound("Relato"));
        organizations.requireSame(Auth.user(), current.getOrganizationId(), "Relato");
        return incidentService.assign(id, Auth.user());
    }

    public record CloseRequest(@NotBlank @Pattern(regexp = "scam|not-scam") String as, @NotBlank @Size(max = 1000) String note) {}

    @PostMapping("/incidents/{id}/close")
    public Incident close(@PathVariable String id, @Valid @RequestBody CloseRequest req) {
        Incident current = incidents.findById(id).orElseThrow(() -> ApiException.notFound("Relato"));
        organizations.requireSame(Auth.user(), current.getOrganizationId(), "Relato");
        return incidentService.close(id, req.as(), req.note(), Auth.user());
    }

    // ------------------------------------------------------------ inteligência, auditoria, motor

    @GetMapping("/intelligence")
    public List<IntelligenceService.EntityView> intelligence() {
        return intel.listFor(organizations.idOf(Auth.user()));
    }

    @GetMapping("/intelligence/{id}")
    public IntelligenceService.EntityView entity(@PathVariable String id) {
        return intel.findFor(id, organizations.idOf(Auth.user())).orElseThrow(() -> ApiException.notFound("Entidade"));
    }

    public record IntelReviewRequest(
            @NotBlank @Pattern(regexp = "UNVERIFIED|INVESTIGATING|CONFIRMED|DISCARDED") String status,
            @DecimalMin("0") @DecimalMax("1") BigDecimal confidence) {}

    @PatchMapping("/intelligence/{id}")
    public IntelligenceService.EntityView reviewEntity(@PathVariable String id, @Valid @RequestBody IntelReviewRequest req) {
        var before = intel.findFor(id, organizations.idOf(Auth.user())).orElseThrow(() -> ApiException.notFound("Entidade"));
        var after = intel.review(id, req.status(), req.confidence(), Auth.user().email())
                .orElseThrow(() -> ApiException.notFound("Entidade"));
        audit.log(Auth.user().email(), "INTEL_REVIEWED", id, before.status() + " -> " + req.status());
        return after;
    }

    @GetMapping("/audit")
    public Map<String, Object> audit(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size,
                                     @RequestParam(required = false) String action,
                                     @RequestParam(required = false) String q) {
        long organizationId = organizations.idOf(Auth.user());
        var result = auditRepo.search(organizationId, blankToNull(action), like(q), auditPage(page, size));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", result.getNumber());
        out.put("size", result.getSize());
        out.put("total", result.getTotalElements());
        out.put("pages", result.getTotalPages());
        out.put("actions", auditRepo.actions(organizationId));
        out.put("items", result.getContent());
        return out;
    }

    /** Todos os eventos filtrados, até 5000, em CSV. Não é só a página visível. */
    @GetMapping(value = "/audit/export", produces = "text/csv")
    public ResponseEntity<String> exportAudit(@RequestParam(required = false) String action,
                                              @RequestParam(required = false) String q) {
        var page = auditRepo.search(organizations.idOf(Auth.user()), blankToNull(action), like(q), auditPage(0, 5000, 5000));
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("quando;quem;acao;alvo;detalhe\n");
        for (AuditEntry entry : page.getContent()) {
            csv.append(csvCell(entry.getAt())).append(';')
                    .append(csvCell(entry.getActor())).append(';')
                    .append(csvCell(entry.getAction())).append(';')
                    .append(csvCell(entry.getTarget())).append(';')
                    .append(csvCell(entry.getDetail())).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"auditoria-contextshield.csv\"")
                .header("X-Export-Count", String.valueOf(page.getNumberOfElements()))
                .header("X-Export-Truncated", String.valueOf(page.getTotalElements() > page.getNumberOfElements()))
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv.toString());
    }

    private static org.springframework.data.domain.Pageable auditPage(int page, int size) {
        return auditPage(page, size, 100);
    }

    private static org.springframework.data.domain.Pageable auditPage(int page, int size, int max) {
        if (page < 0) throw ApiException.badRequest("A página começa em 0");
        if (size < 1 || size > max) throw ApiException.badRequest("O tamanho da página fica entre 1 e " + max);
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String like(String value) {
        String text = blankToNull(value);
        return text == null ? null : "%" + text.toLowerCase() + "%";
    }

    private static String csvCell(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (text.startsWith("=") || text.startsWith("+") || text.startsWith("-") || text.startsWith("@")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    @GetMapping("/engine/config")
    public Map<String, Object> engineConfig() {
        return settings.config();
    }

    public record SettingRequest(@NotBlank String key, boolean enabled) {}

    @PutMapping("/engine/config")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> updateEngineConfig(@Valid @RequestBody SettingRequest req) {
        CurrentUser u = Auth.user();
        try {
            if (EngineSettings.FLAGS.contains(req.key())) settings.setFlag(req.key(), req.enabled());
            else settings.setProvider(req.key(), req.enabled());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        audit.log(u.email(), "ENGINE_SETTING", req.key(), String.valueOf(req.enabled()));
        return settings.config();
    }

    // ------------------------------------------------------------ clientes e avisos

    @GetMapping("/customers")
    public List<Customer> customers() {
        return customers.findAll(org.springframework.data.domain.Sort.by("name"));
    }

    public record AlertRequest(@NotBlank String customerId, @NotBlank @Size(max = 160) String title, @NotBlank @Size(max = 2000) String body) {}

    @PostMapping("/alerts")
    @ResponseStatus(HttpStatus.CREATED)
    public Alert sendAlert(@Valid @RequestBody AlertRequest req) {
        if (!customers.existsById(req.customerId())) throw ApiException.badRequest("Cliente inexistente");
        CurrentUser u = Auth.user();
        audit.log(u.email(), "ALERT_SENT", req.customerId(), req.title());
        return alerts.create(req.customerId(), null, "info", req.title(), req.body(), LocalDateTime.now().withNano(0));
    }

    // ------------------------------------------------------------ demonstração

    public record RunRequest(Map<String, String> answers, String choice) {}

    /** Roda um cenário completo pelo mesmo caminho do checkout (útil na banca e no teste de carga). */
    @PostMapping("/demo/scenarios/{id}/run")
    @Transactional
    public Views.OpsTx run(@PathVariable String id, @RequestBody(required = false) RunRequest req) {
        JsonNode sc = ref.scenario(id).orElseThrow(() -> ApiException.notFound("Cenário"));
        List<TransactionService.Line> lines = new ArrayList<>();
        sc.path("cart").forEach(l -> lines.add(new TransactionService.Line(l.path("productId").asText(), l.path("quantity").asInt(1))));
        LocalDateTime at = scenarioTime(sc);
        var draft = new TransactionService.Draft(sc.path("customerId").asText(), sc.path("deviceId").asText(),
                sc.path("addressId").asText(), lines, sc.path("declaredOrigin").asText(), ReferenceData.coachScript(sc), id,
                null, null, null, null, at, null);
        CurrentUser u = Auth.user();
        Transaction tx = txService.create(draft, u.email());
        CurrentUser asCustomer = new CurrentUser(u.email(), u.name(), "ADMIN", sc.path("customerId").asText());
        if (tx.getStatus() == TransactionStatus.CONTEXT_REQUIRED) {
            Map<String, String> answers = new LinkedHashMap<>();
            if (req != null && req.answers() != null) answers.putAll(req.answers());
            else sc.path("answers").fields().forEachRemaining(e -> answers.put(e.getKey(), e.getValue().asText()));
            tx = txService.verify(tx.getId(), answers, asCustomer, at.plusSeconds(40));
        }
        if (tx.getStatus() == TransactionStatus.INTERVENTION && req != null && req.choice() != null)
            tx = txService.resolve(tx.getId(), req.choice(), asCustomer, at.plusSeconds(75));
        return views.forOps(tx);
    }

    /**
     * O cenário roda no horário do roteiro (canonicalAt), na data mais recente em que esse horário já passou.
     * Assim o resultado é o mesmo num ensaio às 23h e na banca à tarde: o sinal de horário incomum não muda a história.
     */
    static LocalDateTime scenarioTime(JsonNode sc) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        String canonical = sc.path("canonicalAt").asText("");
        if (canonical.length() < 19) return now;
        LocalDateTime at = now.toLocalDate().atTime(LocalDateTime.parse(canonical).toLocalTime());
        return at.isAfter(now) ? at.minusDays(1) : at;
    }

    @PostMapping("/demo/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> reset() {
        if (!props.seed().enabled() && !props.seed().allowDemoReset()) {
            throw ApiException.forbidden("Reinício da demonstração desabilitado neste ambiente");
        }
        CurrentUser u = Auth.user();
        seed.resetDynamicData();
        audit.log(u.email(), "DEMO_RESET", null, null);
        return Map.of("status", "ok", "transactions", txs.count());
    }

    @GetMapping("/customers/{id}/devices")
    public List<Device> devices(@PathVariable String id) {
        return devices.findByCustomerIdOrderByTrustedDescFirstSeenAsc(id);
    }
}
