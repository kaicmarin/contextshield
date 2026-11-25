package com.contextshield.core.service;

import com.contextshield.core.config.AppProperties;
import com.contextshield.core.config.CurrentUser;
import com.contextshield.core.domain.*;
import com.contextshield.core.repo.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Popula o banco na primeira subida: cadastros (sem depender do motor) e depois o histórico
 * de compras e relatos, que passa pelo risk-engine como qualquer compra real.
 */
@Service
public class SeedService {

    private static final Logger log = LoggerFactory.getLogger(SeedService.class);
    public static final String ANALYST_EMAIL = "analista@contextshield.example";
    public static final String ADMIN_EMAIL = "admin@contextshield.example";

    private final ReferenceData ref;
    private final AppProperties props;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CustomerRepository customers;
    private final AppUserRepository users;
    private final CardRepository cards;
    private final DeviceRepository devices;
    private final AddressRepository addresses;
    private final MerchantRepository merchants;
    private final SellerRepository sellers;
    private final CategoryRepository categories;
    private final ProductRepository products;
    private final TransactionRepository transactions;
    private final TransactionService txService;
    private final IncidentService incidentService;
    private final IncidentRepository incidents;
    private volatile String state = "pending";

    public SeedService(ReferenceData ref, AppProperties props, PasswordEncoder encoder, JdbcTemplate jdbc, TransactionTemplate tx,
                       CustomerRepository customers, AppUserRepository users, CardRepository cards, DeviceRepository devices,
                       AddressRepository addresses, MerchantRepository merchants, SellerRepository sellers,
                       CategoryRepository categories, ProductRepository products, TransactionRepository transactions,
                       TransactionService txService, IncidentService incidentService, IncidentRepository incidents) {
        this.ref = ref;
        this.props = props;
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.tx = tx;
        this.customers = customers;
        this.users = users;
        this.cards = cards;
        this.devices = devices;
        this.addresses = addresses;
        this.merchants = merchants;
        this.sellers = sellers;
        this.categories = categories;
        this.products = products;
        this.transactions = transactions;
        this.txService = txService;
        this.incidentService = incidentService;
        this.incidents = incidents;
    }

    public String state() {
        return state;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!props.seed().enabled()) {
            state = "disabled";
            return;
        }
        tx.executeWithoutResult(s -> seedReference());
        if (transactions.count() > 0) {
            state = "done";
            return;
        }
        Thread t = new Thread(this::seedHistoryWithRetry, "seed-history");
        t.setDaemon(true);
        t.start();
    }

    private void seedHistoryWithRetry() {
        for (int attempt = 1; attempt <= 60; attempt++) {
            try {
                state = "running";
                seedHistory();
                state = "done";
                log.info("Histórico da demonstração carregado");
                return;
            } catch (RiskEngineClient.EngineUnavailable e) {
                state = "waiting-engine";
                log.info("Aguardando o risk-engine para carregar o histórico (tentativa {})", attempt);
                sleep(3000);
            } catch (Exception e) {
                state = "failed";
                log.error("Falha ao carregar o histórico da demonstração", e);
                return;
            }
        }
        state = "failed";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * O mundo da demonstração foi escrito como se "hoje" fosse 28/09/2026. Para os sinais relativos
     * (vendedor com 26 dias de loja, cliente há mais de 3 anos...) continuarem iguais em qualquer dia
     * da apresentação, todas as datas da massa são deslocadas para que esse dia caia em "hoje".
     */
    static final LocalDate STORY_DAY = LocalDate.of(2026, 9, 28);
    private long shiftDays = 0;

    private LocalDate date(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() ? null : LocalDate.parse(n.asText().substring(0, 10)).plusDays(shiftDays);
    }

    private LocalDateTime dateTime(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() ? null : LocalDateTime.parse(n.asText()).plusDays(shiftDays);
    }

    public long shiftDays() {
        return shiftDays;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    public void seedReference() {
        if (customers.count() > 0) {
            // Banco já populado: recupera o deslocamento a partir da data gravada do cliente CUST-001
            customers.findById("CUST-001").ifPresent(c -> shiftDays = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(2021, 3, 14), c.getSince()));
            return;
        }
        shiftDays = props.seed().anchorToday() ? Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(STORY_DAY, LocalDate.now())) : 0;
        String hash = encoder.encode(props.seed().demoPassword());
        for (JsonNode c : ref.get("customers")) {
            Customer x = new Customer();
            x.setId(c.path("id").asText());
            x.setName(c.path("name").asText());
            x.setFirstName(c.path("firstName").asText());
            x.setInitials(c.path("initials").asText());
            x.setEmail(c.path("email").asText());
            x.setPhoneMask(text(c, "phoneMask"));
            x.setCity(c.path("city").asText());
            x.setSince(date(c.path("since")));
            x.setSegment(text(c, "segment"));
            x.setAvgTicket90d(c.path("spend").path("avgTicket90d").decimalValue());
            x.setPurchases90d(c.path("spend").path("purchases90d").asInt());
            customers.save(x);
            users.save(user(x.getEmail(), x.getName(), AppUser.Role.CUSTOMER, x.getId(), hash));
        }
        users.save(user(ANALYST_EMAIL, "Lívia Moreira", AppUser.Role.ANALYST, null, hash));
        users.save(user(ADMIN_EMAIL, "Administrador", AppUser.Role.ADMIN, null, hash));
        for (JsonNode c : ref.get("cards")) {
            Card x = new Card();
            x.setId(c.path("id").asText());
            x.setCustomerId(c.path("customerId").asText());
            x.setLast4(c.path("last4").asText());
            x.setHolder(text(c, "holder"));
            x.setProduct(text(c, "product"));
            x.setKind(text(c, "kind"));
            x.setExpiry(text(c, "expiry"));
            x.setCreditLimit(c.path("limit").decimalValue());
            x.setAvailable(c.path("available").decimalValue());
            x.setStatus(text(c, "status"));
            cards.save(x);
        }
        for (JsonNode d : ref.get("devices")) {
            Device x = new Device();
            x.setId(d.path("id").asText());
            x.setCustomerId(d.path("customerId").asText());
            x.setName(d.path("name").asText());
            x.setDetail(text(d, "detail"));
            x.setKind(text(d, "kind"));
            x.setLocation(text(d, "location"));
            x.setFirstSeen(dateTime(d.path("firstSeen")));
            x.setLastSeen(dateTime(d.path("lastSeen")));
            x.setTrusted(d.path("trusted").asBoolean());
            devices.save(x);
        }
        for (JsonNode a : ref.get("addresses")) {
            Address x = new Address();
            x.setId(a.path("id").asText());
            x.setLabel(a.path("label").asText());
            x.setLine1(a.path("line1").asText());
            x.setLine2(text(a, "line2"));
            x.setDistrict(text(a, "district"));
            x.setCity(text(a, "city"));
            x.setZip(text(a, "zip"));
            x.setKind(a.path("kind").asText());
            a.path("customerIds").forEach(id -> x.getCustomerIds().add(id.asText()));
            addresses.save(x);
        }
        for (JsonNode m : ref.get("merchants")) {
            Merchant x = new Merchant();
            x.setId(m.path("id").asText());
            x.setName(m.path("name").asText());
            x.setLegalName(text(m, "legalName"));
            x.setDomain(text(m, "domain"));
            x.setCnpj(text(m, "cnpj"));
            x.setCity(text(m, "city"));
            x.setCategory(text(m, "category"));
            x.setSince(date(m.path("since")));
            x.setVerified(m.path("verified").asBoolean());
            x.setMarketplace(m.path("marketplace").asBoolean());
            merchants.save(x);
        }
        for (JsonNode s : ref.get("sellers")) {
            Seller x = new Seller();
            x.setId(s.path("id").asText());
            x.setName(s.path("name").asText());
            x.setInitials(text(s, "initials"));
            x.setColor(text(s, "color"));
            x.setTagline(text(s, "tagline"));
            x.setAbout(text(s, "about"));
            x.setSince(date(s.path("since")));
            x.setCity(text(s, "city"));
            x.setCnpj(text(s, "cnpj"));
            x.setSales(s.path("sales").asInt());
            x.setRating(s.path("rating").decimalValue());
            x.setReviewCount(s.path("reviewCount").asInt());
            x.setTier(s.path("tier").asText());
            x.setClaimsRate(s.path("claimsRate").decimalValue());
            sellers.save(x);
        }
        for (JsonNode c : ref.get("categories")) {
            Category x = new Category();
            x.setSlug(c.path("slug").asText());
            x.setName(c.path("name").asText());
            x.setBlurb(text(c, "blurb"));
            categories.save(x);
        }
        for (JsonNode p : ref.get("products")) {
            Product x = new Product();
            x.setId(p.path("id").asText());
            x.setSku(text(p, "sku"));
            x.setName(p.path("name").asText());
            x.setLine(text(p, "line"));
            x.setCategorySlug(p.path("categorySlug").asText());
            x.setSellerId(p.path("sellerId").asText());
            x.setPrice(p.path("price").decimalValue());
            x.setOldPrice(p.path("oldPrice").isNumber() ? p.path("oldPrice").decimalValue() : null);
            x.setInstallments(p.path("installments").asInt(1));
            x.setRating(p.path("rating").decimalValue());
            x.setReviewCount(p.path("reviewCount").asInt());
            x.setStock(p.path("stock").asInt());
            x.setDeliveryDays(p.path("deliveryDays").asInt());
            x.setImage("/products/" + p.path("kind").asText() + "-" + p.path("finish").asText() + ".jpg");
            x.setTag(text(p, "tag"));
            x.setDescription(text(p, "description"));
            products.save(x);
        }
        log.info("Cadastros da demonstração carregados");
    }

    private AppUser user(String email, String name, AppUser.Role role, String customerId, String hash) {
        AppUser u = new AppUser();
        u.setEmail(email);
        u.setName(name);
        u.setRole(role);
        u.setCustomerId(customerId);
        u.setPasswordHash(hash);
        u.setActive(true);
        u.setCreatedAt(LocalDateTime.of(2026, 8, 10, 9, 0));
        return u;
    }

    private static final CurrentUser SYSTEM = new CurrentUser("seed@contextshield", "Sistema", "ADMIN", null);

    /** Reproduz em ordem cronológica as compras e os relatos de setembro de 2026. */
    public void seedHistory() {
        record Step(LocalDateTime at, Runnable run) {}
        List<Step> steps = new ArrayList<>();
        for (JsonNode p : ref.get("seedPurchases")) {
            LocalDateTime at = dateTime(p.path("at"));
            steps.add(new Step(at, () -> tx.executeWithoutResult(s -> {
                if (transactions.existsById(TransactionService.txId(p.path("seq").asLong()))) return;
                List<TransactionService.Line> lines = new ArrayList<>();
                p.path("items").forEach(i -> lines.add(new TransactionService.Line(i.path("productId").asText(), i.path("quantity").asInt(1))));
                List<String> down = new ArrayList<>();
                p.path("providersDown").forEach(d -> down.add(d.asText()));
                var draft = new TransactionService.Draft(p.path("customerId").asText(), p.path("deviceId").asText(),
                        p.path("addressId").asText(), lines, text(p, "declaredOrigin"), null, null, text(p, "merchantId"),
                        p.path("amount").isNumber() ? p.path("amount").decimalValue() : null, text(p, "description"),
                        down.isEmpty() ? null : String.join(",", down), at, p.path("seq").asLong());
                Transaction t = txService.create(draft, "seed");
                if (t.getStatus() == TransactionStatus.ANALYZING) throw new RiskEngineClient.EngineUnavailable();
                if (p.has("answers") && t.getStatus() == TransactionStatus.CONTEXT_REQUIRED) {
                    Map<String, String> ans = new LinkedHashMap<>();
                    p.path("answers").fields().forEachRemaining(e -> ans.put(e.getKey(), e.getValue().asText()));
                    txService.verify(t.getId(), ans, SYSTEM, at.plusSeconds(40));
                }
            })));
        }
        for (JsonNode r : ref.get("seedReports")) {
            LocalDateTime at = dateTime(r.path("at"));
            steps.add(new Step(at, () -> tx.executeWithoutResult(s -> {
                if (incidents.existsById(IncidentService.incidentId(r.path("seq").asLong()))) return;
                var draft = new IncidentService.Draft(r.path("customerId").asText(),
                        TransactionService.txId(r.path("txSeq").asLong()), r.path("type").asText(), r.path("channel").asText(),
                        r.path("description").asText(), text(r, "contactHandle"), text(r, "contactLink"), text(r, "destinationText"),
                        dateTime(r.path("occurredAt")), true);
                Incident i = incidentService.create(draft, "seed", at, r.path("seq").asLong());
                if (r.has("assignedAt")) incidentService.assignAt(i, ANALYST_EMAIL, dateTime(r.path("assignedAt")));
                if (r.has("closedAt")) incidentService.closeAt(i, "not-scam",
                        Optional.ofNullable(text(r, "closeNote")).orElse("Sem relação com golpe"), ANALYST_EMAIL, dateTime(r.path("closedAt")));
            })));
        }
        steps.sort(Comparator.comparing(Step::at));
        for (Step st : steps) st.run().run();
    }

    /** Volta a demonstração ao estado inicial (mantém cadastros e usuários). */
    public void resetDynamicData() {
        tx.executeWithoutResult(s -> {
            for (String t : List.of("ml_shadow", "alert", "intel_mention", "intel_entity", "incident_reply", "incident", "verification", "assessment",
                    "status_change", "transaction_item", "transactions", "engine_setting"))
                jdbc.update("delete from " + t);
            jdbc.execute("alter sequence tx_seq restart with 184");
            jdbc.execute("alter sequence incident_seq restart with 1284");
        });
        seedHistory();
        state = "done";
    }

}
