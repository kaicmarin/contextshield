package com.contextshield.core.service;

import com.contextshield.core.domain.*;
import com.contextshield.core.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Inteligência entre casos: cada relato vira entidades (telefone, link, perfil, destino, vendedor).
 * Quando uma compra nova usa um destino ou vendedor já citado por OUTRO cliente, o motor recebe essa relação.
 */
@Service
public class IntelligenceService {

    private final IntelEntityRepository entities;
    private final IntelMentionRepository mentions;
    private final IncidentRepository incidents;
    private final AddressRepository addresses;
    private final SellerRepository sellers;
    private final MerchantRepository merchants;
    private final TransactionItemRepository items;
    private final CustomerRepository customers;

    public IntelligenceService(IntelEntityRepository entities, IntelMentionRepository mentions, IncidentRepository incidents,
                               AddressRepository addresses, SellerRepository sellers, MerchantRepository merchants,
                               TransactionItemRepository items, CustomerRepository customers) {
        this.entities = entities;
        this.mentions = mentions;
        this.incidents = incidents;
        this.addresses = addresses;
        this.sellers = sellers;
        this.merchants = merchants;
        this.items = items;
        this.customers = customers;
    }

    record Mention(String id, String kind, String value, String sub, String reason) {}

    private static final String[] MONTHS = {"jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"};

    public Optional<Address> matchAddressText(String text) {
        String t = IntelText.strip(text);
        if (t.isEmpty()) return Optional.empty();
        return addresses.findAll().stream().filter(a -> t.contains(IntelText.strip(a.getLine1()))).findFirst();
    }

    private Mention destination(Address a, String reason) {
        return new Mention(IntelText.destinationEntityId(a.getId()), "destination",
                a.getLine1() + (a.getLine2() != null ? " — " + a.getLine2() : ""), a.getLabel() + " · " + a.getDistrict() + ", " + a.getCity(), reason);
    }

    private Optional<Mention> seller(String sellerId, String reason) {
        return sellers.findById(sellerId).map(s -> new Mention(IntelText.sellerEntityId(s.getId()), "seller", s.getName(),
                "Vendedor NEXA desde " + MONTHS[s.getSince().getMonthValue() - 1] + " " + s.getSince().getYear(), reason));
    }

    private List<String> sellerIds(Transaction tx) {
        return items.findByTxId(tx.getId()).stream().map(TransactionItem::getSellerId).filter(Objects::nonNull).distinct().toList();
    }

    /** Tudo o que um relato cita. Mesma regra do protótipo (incidentMentions). */
    List<Mention> incidentMentions(Incident inc, Transaction tx) {
        List<Mention> out = new ArrayList<>();
        String text = String.join(" \n ", Arrays.asList(inc.getContactHandle(), inc.getContactLink(), inc.getDestinationText(), inc.getDescription())
                .stream().filter(Objects::nonNull).toList());
        for (String d : IntelText.phones(text))
            out.add(new Mention(IntelText.phoneEntityId(d), "phone", IntelText.formatPhone(d), "Telefone citado em relato", "Telefone informado no relato " + inc.getId()));
        for (String host : IntelText.domains(text))
            if (!IntelText.OFFICIAL_DOMAINS.contains(host))
                out.add(new Mention(IntelText.domainEntityId(host), "domain", host, "Domínio citado em relato", "Link informado no relato " + inc.getId()));
        for (IntelText.AccountCue cue : IntelText.ACCOUNT_CUES)
            if (cue.re().matcher(text).find())
                out.add(new Mention(cue.id(), "account", cue.value(), "Identidade usada no contato", "Nome usado pelo contato no relato " + inc.getId()));

        Set<String> destIds = new LinkedHashSet<>();
        if (tx != null) addresses.findById(tx.getAddressId())
                .filter(a -> !a.getCustomerIds().contains(inc.getCustomerId())).ifPresent(a -> destIds.add(a.getId()));
        if (inc.getDestinationText() != null) matchAddressText(inc.getDestinationText())
                .filter(a -> !a.getCustomerIds().contains(inc.getCustomerId())).ifPresent(a -> destIds.add(a.getId()));
        for (String id : destIds) addresses.findById(id).ifPresent(a -> out.add(destination(a, "Destino de entrega citado no relato " + inc.getId())));

        if (tx != null) {
            for (String sid : sellerIds(tx)) seller(sid, "Vendedor da compra relatada em " + inc.getId()).ifPresent(out::add);
            merchants.findById(tx.getMerchantId()).filter(m -> !m.isMarketplace()).ifPresent(m -> out.add(new Mention(
                    "ENT-" + m.getId(), "merchant", m.getName(), m.getCategory(), "Estabelecimento da compra relatada em " + inc.getId())));
        }
        return out;
    }

    private void upsert(Mention m, LocalDateTime at) {
        IntelEntity e = entities.findById(m.id()).orElseGet(() -> {
            IntelEntity n = new IntelEntity();
            n.setId(m.id());
            n.setKind(m.kind());
            n.setValue(m.value());
            n.setSub(m.sub());
            n.setFirstSeen(at);
            n.setLastSeen(at);
            n.setStatus("UNVERIFIED");
            n.setEvidenceSource("relato");
            return n;
        });
        if (at.isBefore(e.getFirstSeen())) e.setFirstSeen(at);
        if (at.isAfter(e.getLastSeen())) e.setLastSeen(at);
        entities.save(e);
    }

    @Transactional
    public void recordIncident(Incident inc, Transaction tx) {
        for (Mention m : incidentMentions(inc, tx)) {
            upsert(m, inc.getCreatedAt());
            IntelMention im = new IntelMention();
            im.setEntityId(m.id());
            im.setIncidentId(inc.getId());
            im.setTxId(tx != null && Set.of("destination", "seller", "merchant").contains(m.kind()) ? tx.getId() : null);
            im.setCustomerId(inc.getCustomerId());
            im.setReason(m.reason());
            im.setAt(inc.getCreatedAt());
            mentions.save(im);
        }
    }

    /** Uma compra nova que usa destino ou vendedor já relatado vira "tentativa relacionada". */
    @Transactional
    public void recordAttempt(Transaction tx) {
        List<Mention> ms = new ArrayList<>();
        addresses.findById(tx.getAddressId()).filter(a -> !a.getCustomerIds().contains(tx.getCustomerId()))
                .ifPresent(a -> ms.add(destination(a, "Destino de entrega da " + tx.getId())));
        for (String sid : sellerIds(tx)) seller(sid, "Vendedor da " + tx.getId()).ifPresent(ms::add);
        for (Mention m : ms) {
            if (activeIncidentIds(m.id(), null).isEmpty()) continue;
            if (mentions.existsByEntityIdAndTxId(m.id(), tx.getId())) continue;
            upsert(m, tx.getCreatedAt());
            IntelMention im = new IntelMention();
            im.setEntityId(m.id());
            im.setTxId(tx.getId());
            im.setCustomerId(tx.getCustomerId());
            im.setReason(m.reason());
            im.setAt(tx.getCreatedAt());
            mentions.save(im);
        }
    }

    /** Relatos que contam para a inteligência: não encerrados como "sem relação com golpe" e anteriores a "at". */
    private List<Incident> activeIncidents(String entityId, LocalDateTime at) {
        Set<String> ids = mentions.findByEntityId(entityId).stream().map(IntelMention::getIncidentId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        return incidents.findAllById(ids).stream()
                .filter(i -> !"not-scam".equals(i.getClosedAs()))
                .filter(i -> at == null || !i.getCreatedAt().isAfter(at))
                .sorted(Comparator.comparing(Incident::getCreatedAt))
                .toList();
    }

    private List<String> activeIncidentIds(String entityId, LocalDateTime at) {
        return activeIncidents(entityId, at).stream().map(Incident::getId).toList();
    }

    String association(String kind, String entityId, int customers) {
        if ("merchant".equals(kind)) return "não indicativa";
        if ("seller".equals(kind)) {
            var s = sellers.findById(entityId.replaceFirst("^ENT-", ""));
            if (s.isEmpty() || Set.of("oficial", "platinum", "gold").contains(s.get().getTier())) return "não indicativa";
        }
        return customers >= 2 ? "forte" : "moderada";
    }

    private int distinctCustomers(String entityId, LocalDateTime at) {
        Set<String> c = new HashSet<>();
        activeIncidents(entityId, at).forEach(i -> c.add(i.getCustomerId()));
        mentions.findByEntityId(entityId).stream().filter(m -> m.getIncidentId() == null)
                .filter(m -> at == null || m.getAt().isBefore(at)).forEach(m -> c.add(m.getCustomerId()));
        return c.size();
    }

    /** Relações que vão para o motor de risco (equivalente a relationsForTx do protótipo). */
    public List<Map<String, Object>> relationsFor(Transaction tx, LocalDateTime at) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<String[]> candidates = new ArrayList<>();
        addresses.findById(tx.getAddressId()).filter(a -> !a.getCustomerIds().contains(tx.getCustomerId()))
                .ifPresent(a -> candidates.add(new String[]{IntelText.destinationEntityId(a.getId()), "destination"}));
        for (String sid : sellerIds(tx)) candidates.add(new String[]{IntelText.sellerEntityId(sid), "seller"});
        for (String[] c : candidates) {
            List<Incident> incs = activeIncidents(c[0], at);
            if (incs.isEmpty()) continue;
            List<String> incidentIds = incs.stream().map(Incident::getId).toList();
            List<String> owners = incs.stream().map(Incident::getCustomerId).filter(id -> !id.equals(tx.getCustomerId())).distinct().toList();
            boolean cross = !owners.isEmpty();
            String names = owners.stream().map(id -> customers.findById(id).map(Customer::getName).orElse(id)).collect(Collectors.joining(", "));
            String reason = "destination".equals(c[1])
                    ? "Destino citado em " + String.join(", ", incidentIds) + (cross ? " por " + names : "")
                    : "Vendedor presente em " + String.join(", ", incidentIds) + (cross ? " (" + names + ")" : "");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", c[1]);
            r.put("entityId", c[0]);
            r.put("reason", reason);
            r.put("association", association(c[1], c[0], distinctCustomers(c[0], at)));
            r.put("crossCustomer", cross);
            r.put("incidentIds", incidentIds);
            out.add(r);
        }
        return out;
    }

    public record EntityView(String id, String kind, String value, String sub, String association, int reports,
                             int customers, int attempts, LocalDateTime firstSeen, LocalDateTime lastSeen,
                             List<String> incidentIds, List<String> txIds, String status, BigDecimal confidence,
                             LocalDateTime validUntil, String evidenceSource, String reviewedBy, LocalDateTime reviewedAt) {}

    public record IntelHint(int reports, String status, BigDecimal confidence, LocalDateTime validUntil) {}

    public EntityView view(IntelEntity e) {
        List<IntelMention> ms = mentions.findByEntityId(e.getId());
        List<String> incIds = activeIncidentIds(e.getId(), null);
        List<String> txIds = ms.stream().map(IntelMention::getTxId).filter(Objects::nonNull).distinct().toList();
        long attempts = ms.stream().filter(m -> m.getIncidentId() == null).count();
        int cust = distinctCustomers(e.getId(), null);
        return new EntityView(e.getId(), e.getKind(), e.getValue(), e.getSub(), association(e.getKind(), e.getId(), cust),
                incIds.size(), cust, (int) attempts, e.getFirstSeen(), e.getLastSeen(), incIds, txIds,
                e.getStatus() == null ? "UNVERIFIED" : e.getStatus(), e.getConfidence(), e.getValidUntil(),
                e.getEvidenceSource(), e.getReviewedBy(), e.getReviewedAt());
    }

    /** Revisão do analista. Não apaga menções. DISCARDED deixa de contar no verificador. */
    @Transactional
    public Optional<EntityView> review(String id, String status, BigDecimal confidence, String actor) {
        IntelEntity e = entities.findById(id).orElse(null);
        if (e == null) return Optional.empty();
        e.setStatus(status);
        e.setConfidence(confidence);
        e.setReviewedBy(actor);
        e.setReviewedAt(LocalDateTime.now());
        if (e.getEvidenceSource() == null) e.setEvidenceSource("relato");
        entities.save(e);
        return Optional.of(view(e));
    }

    /** Status e relatos para o verificador. DISCARDED e validade vencida não puxam o semáforo. */
    public IntelHint hintFor(String entityId) {
        IntelEntity e = entities.findById(entityId).orElse(null);
        if (e == null) return new IntelHint(0, null, null, null);
        String status = e.getStatus() == null ? "UNVERIFIED" : e.getStatus();
        if (e.getValidUntil() != null && e.getValidUntil().isBefore(LocalDateTime.now())) status = "UNVERIFIED";
        if ("DISCARDED".equals(status)) return new IntelHint(0, "DISCARDED", e.getConfidence(), e.getValidUntil());
        return new IntelHint(activeIncidentIds(entityId, null).size(), status, e.getConfidence(), e.getValidUntil());
    }

    public List<EntityView> list() {
        return entities.findAll().stream().map(this::view).filter(v -> v.reports() > 0)
                .sorted(Comparator.comparing(EntityView::customers).reversed().thenComparing(EntityView::lastSeen, Comparator.reverseOrder()))
                .toList();
    }

    public Optional<EntityView> find(String id) {
        return entities.findById(id).map(this::view);
    }

    public int reportsFor(String entityId) {
        return entities.existsById(entityId) ? activeIncidentIds(entityId, null).size() : 0;
    }
}
