package com.contextshield.core.service;

import com.contextshield.core.config.CurrentUser;
import com.contextshield.core.domain.AppUser;
import com.contextshield.core.domain.Incident;
import com.contextshield.core.domain.IncidentReply;
import com.contextshield.core.domain.Transaction;
import com.contextshield.core.domain.TransactionStatus;
import com.contextshield.core.repo.AppUserRepository;
import com.contextshield.core.repo.AuditEntryRepository;
import com.contextshield.core.repo.IncidentReplyRepository;
import com.contextshield.core.repo.IncidentRepository;
import com.contextshield.core.repo.IntelMentionRepository;
import com.contextshield.core.repo.TransactionRepository;
import com.contextshield.core.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** Relatos de golpe ("Fui vítima"): criação pelo cliente, triagem e encerramento pelo analista. */
@Service
public class IncidentService {

    public static final Set<String> TYPES = Set.of("guided", "message", "link", "impersonation", "unrecognized", "other");
    public static final Set<String> CHANNELS = Set.of("phone", "whatsapp", "sms", "email", "social", "site", "none");

    private final IncidentRepository repo;
    private final TransactionRepository txs;
    private final TransactionService txService;
    private final IntelligenceService intel;
    private final IntelMentionRepository mentions;
    private final AlertService alerts;
    private final AuditService audit;
    private final AuditEntryRepository auditEntries;
    private final IncidentReplyRepository replies;
    private final AppUserRepository users;
    private final JdbcTemplate jdbc;

    public IncidentService(IncidentRepository repo, TransactionRepository txs, TransactionService txService,
                           IntelligenceService intel, IntelMentionRepository mentions, AlertService alerts,
                           AuditService audit, AuditEntryRepository auditEntries, IncidentReplyRepository replies,
                           AppUserRepository users, JdbcTemplate jdbc) {
        this.repo = repo;
        this.txs = txs;
        this.txService = txService;
        this.intel = intel;
        this.mentions = mentions;
        this.alerts = alerts;
        this.audit = audit;
        this.auditEntries = auditEntries;
        this.replies = replies;
        this.users = users;
        this.jdbc = jdbc;
    }

    public record ReplyView(long id, String incidentId, String txId, String body, String authorEmail, String authorName,
                            String serviceStatus, LocalDateTime createdAt) {}

    public record Person(String email, String name) {}

    /** Denunciante, quem assumiu o atendimento e quem tomou a decisão de encerrar. */
    public record Attendance(String complainantId, String complainantName, Person attendant, Person decision) {}

    public record Draft(String customerId, String txId, String type, String channel, String description,
                        String contactHandle, String contactLink, String destinationText, LocalDateTime occurredAt,
                        boolean consent) {}

    public static String incidentId(long seq) {
        return String.format("CS-2026-%06d", seq);
    }

    private void validate(Draft d) {
        if (!d.consent()) throw ApiException.badRequest("É preciso autorizar o uso do relato para proteger outros clientes");
        if (!TYPES.contains(d.type())) throw ApiException.badRequest("Tipo de relato inválido");
        if (!CHANNELS.contains(d.channel())) throw ApiException.badRequest("Canal inválido");
        if (d.description() == null || d.description().trim().length() < 15)
            throw ApiException.badRequest("Conte com um pouco mais de detalhe o que aconteceu (mínimo 15 caracteres)");
    }

    @Transactional
    public Incident create(Draft d, String actor, LocalDateTime when, Long fixedSeq) {
        validate(d);
        Transaction tx = null;
        if (d.txId() != null && !d.txId().isBlank()) {
            tx = txs.findById(d.txId()).orElseThrow(() -> ApiException.badRequest("A compra informada não existe"));
            if (!tx.getCustomerId().equals(d.customerId())) throw ApiException.badRequest("A compra informada não é deste cliente");
        }
        LocalDateTime at = when != null ? when : LocalDateTime.now().withNano(0);
        Long seq = fixedSeq != null ? fixedSeq : jdbc.queryForObject("select nextval('incident_seq')", Long.class);
        Incident i = new Incident();
        i.setId(incidentId(seq));
        i.setCustomerId(d.customerId());
        i.setTxId(tx == null ? null : tx.getId());
        i.setType(d.type());
        i.setChannel(d.channel());
        i.setDescription(d.description().trim());
        i.setContactHandle(blank(d.contactHandle()));
        i.setContactLink(blank(d.contactLink()));
        i.setDestinationText(blank(d.destinationText()));
        i.setOccurredAt(d.occurredAt() != null ? d.occurredAt() : at);
        i.setStatus("OPEN");
        i.setCreatedAt(at);
        repo.save(i);

        if (tx != null && tx.getStatus().canGoTo(TransactionStatus.INCIDENT_RECORDED))
            txService.transition(tx, TransactionStatus.INCIDENT_RECORDED, "Cliente registrou o relato " + i.getId(), actor, at);
        intel.recordIncident(i, tx);
        alerts.create(d.customerId(), i.getTxId(), "info", "Recebemos seu relato " + i.getId(),
                "Um analista vai olhar o caso. O telefone, o link e o endereço que você informou já ajudam a proteger outros clientes.", at);
        audit.log(actor, "INCIDENT_CREATED", i.getId(), i.getType() + " · " + i.getChannel(), at);
        return i;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public Incident get(String id, CurrentUser user) {
        Incident i = repo.findById(id).orElseThrow(() -> ApiException.notFound("Relato"));
        if (!user.isStaff() && !i.getCustomerId().equals(user.customerId())) throw ApiException.notFound("Relato");
        return i;
    }

    public List<Incident> mine(CurrentUser user) {
        return repo.findByCustomerIdOrderByCreatedAtDesc(user.customerId());
    }

    /** O cliente pode completar o relato enquanto ninguém começou a analisar. */
    @Transactional
    public Incident update(String id, Draft d, CurrentUser user) {
        Incident i = get(id, user);
        if (!"OPEN".equals(i.getStatus())) throw ApiException.conflict("O relato já está em análise e não pode mais ser alterado");
        validate(d);
        i.setType(d.type());
        i.setChannel(d.channel());
        i.setDescription(d.description().trim());
        i.setContactHandle(blank(d.contactHandle()));
        i.setContactLink(blank(d.contactLink()));
        i.setDestinationText(blank(d.destinationText()));
        if (d.occurredAt() != null) i.setOccurredAt(d.occurredAt());
        repo.save(i);
        // Refaz as menções de inteligência com os dados novos
        mentions.deleteAll(mentions.findByIncidentId(i.getId()));
        intel.recordIncident(i, i.getTxId() == null ? null : txs.findById(i.getTxId()).orElse(null));
        audit.log(user.email(), "INCIDENT_UPDATED", id, null);
        return i;
    }

    /** O cliente pode retirar um relato aberto (ex.: registrou por engano). */
    @Transactional
    public void delete(String id, CurrentUser user) {
        Incident i = get(id, user);
        if (!user.isStaff() && !"OPEN".equals(i.getStatus())) throw ApiException.conflict("O relato já está em análise e não pode ser removido");
        mentions.deleteAll(mentions.findByIncidentId(i.getId()));
        repo.delete(i);
        audit.log(user.email(), "INCIDENT_DELETED", id, null);
    }

    @Transactional
    public Incident assign(String id, CurrentUser analyst) {
        Incident i = repo.findById(id).orElseThrow(() -> ApiException.notFound("Relato"));
        if (!"OPEN".equals(i.getStatus())) throw ApiException.conflict("O relato já está em análise ou encerrado");
        return assignAt(i, analyst.email(), LocalDateTime.now().withNano(0));
    }

    public Incident assignAt(Incident i, String actor, LocalDateTime at) {
        i.setStatus("IN_REVIEW");
        i.setAssignedAt(at);
        repo.save(i);
        audit.log(actor, "INCIDENT_ASSIGNED", i.getId(), null, at);
        return i;
    }

    @Transactional
    public Incident close(String id, String as, String note, CurrentUser analyst) {
        Incident i = repo.findById(id).orElseThrow(() -> ApiException.notFound("Relato"));
        return closeAt(i, as, note, analyst.email(), LocalDateTime.now().withNano(0));
    }

    public Incident closeAt(Incident i, String as, String note, String actor, LocalDateTime at) {
        if ("CLOSED".equals(i.getStatus())) throw ApiException.conflict("O relato já foi encerrado");
        if (!Set.of("scam", "not-scam").contains(as)) throw ApiException.badRequest("Encerramento deve ser 'scam' ou 'not-scam'");
        if (note == null || note.isBlank()) throw ApiException.badRequest("Escreva uma nota de encerramento");
        i.setStatus("CLOSED");
        i.setClosedAs(as);
        i.setCloseNote(note.trim());
        i.setClosedAt(at);
        repo.save(i);
        alerts.create(i.getCustomerId(), i.getTxId(), "info", "Seu relato " + i.getId() + " foi encerrado",
                "scam".equals(as) ? "Confirmamos o golpe. As informações do seu relato já protegem outros clientes." : "Concluímos a análise: " + note.trim(), at);
        audit.log(actor, "INCIDENT_CLOSED", i.getId(), as + " · " + note, at);
        return i;
    }

    public List<ReplyView> replies(Incident incident) {
        return replies.findByIncidentIdOrderByIdAsc(incident.getId()).stream()
                .map(r -> new ReplyView(r.getId(), r.getIncidentId(), incident.getTxId(), r.getBody(), r.getAuthorEmail(),
                        r.getAuthorName(), r.getServiceStatus(), r.getCreatedAt()))
                .toList();
    }

    public Attendance attendance(Incident incident, String complainantName) {
        return new Attendance(incident.getCustomerId(), blank(complainantName),
                person(incident.getId(), "INCIDENT_ASSIGNED"), person(incident.getId(), "INCIDENT_CLOSED"));
    }

    /**
     * Registra uma resposta de verdade. O autor é a conta autenticada.
     * Um relato aberto passa a em análise, pela mesma regra de assumir.
     * Encerrado não recebe resposta: a conclusão continua sendo o encerramento.
     */
    @Transactional
    public Incident reply(String id, String body, CurrentUser user) {
        Incident i = repo.findById(id).orElseThrow(() -> ApiException.notFound("Relato"));
        if ("CLOSED".equals(i.getStatus()))
            throw ApiException.conflict("O relato já foi encerrado. A conclusão não entra como resposta.");
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) throw ApiException.badRequest("Escreva a resposta");
        if (text.length() > 2000) throw ApiException.badRequest("A resposta passa de 2000 caracteres");
        AppUser account = users.findByEmailIgnoreCase(user.email()).filter(AppUser::isActive)
                .orElseThrow(ApiException::forbidden);
        LocalDateTime at = LocalDateTime.now().withNano(0);
        if ("OPEN".equals(i.getStatus())) i = assignAt(i, account.getEmail(), at);
        IncidentReply reply = new IncidentReply();
        reply.setIncidentId(i.getId());
        reply.setBody(text);
        reply.setAuthorEmail(account.getEmail());
        reply.setAuthorName(blank(account.getName()));
        reply.setServiceStatus(i.getStatus());
        reply.setCreatedAt(at);
        replies.save(reply);
        audit.log(account.getEmail(), "INCIDENT_REPLIED", i.getId(), i.getStatus(), at);
        return i;
    }

    private Person person(String incidentId, String action) {
        return auditEntries.findFirstByTargetAndActionOrderByIdDesc(incidentId, action)
                .map(entry -> {
                    String email = blank(entry.getActor());
                    String name = email == null ? null : users.findByEmailIgnoreCase(email).map(u -> blank(u.getName())).orElse(null);
                    return email == null ? null : new Person(email, name);
                })
                .orElse(null);
    }
}
