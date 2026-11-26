package com.contextshield.core.service;

import com.contextshield.core.web.ApiException;
import com.contextshield.ml.Experiment;
import com.contextshield.ml.Features;
import com.contextshield.ml.support.ExperimentFila;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fila de um experimento por vez. O pedido HTTP só enfileira; o treino roda numa thread à parte. */
@Service
public class MlLabService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MlLabService.class);
    private static final int MIN_EXAMPLES = 200;
    private static final int MAX_EXAMPLES = 4000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final RiskPolicyService policies;
    private final EngineInputBuilder inputs;
    private final com.contextshield.core.repo.TransactionRepository transactions;
    private final OrganizationService organizations;
    private final MlClient remote;
    private final LabStudyService studies;
    private final Duration maxRun = Duration.ofMinutes(20);
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);
    private final ExecutorService pool = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ml-experiment-queue");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService training = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "ml-experiment-train");
        thread.setDaemon(true);
        return thread;
    });

    public MlLabService(JdbcTemplate jdbc, ObjectMapper mapper, AuditService audit, RiskPolicyService policies,
                        EngineInputBuilder inputs, com.contextshield.core.repo.TransactionRepository transactions,
                        OrganizationService organizations, MlClient remote, LabStudyService studies) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.audit = audit;
        this.policies = policies;
        this.inputs = inputs;
        this.transactions = transactions;
        this.organizations = organizations;
        this.remote = remote;
        this.studies = studies;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!remote.enabled()) {
            ExperimentFila.recoverOnStartup(jdbc, "ml_experiment");
            kickWorker();
        }
        importReportIfEmpty();
        importContextIfMissing();
    }

    void kickWorker() {
        if (remote.enabled() || !workerRunning.compareAndSet(false, true)) return;
        pool.submit(this::drainQueue);
    }

    public Map<String, Object> overview(long organizationId) {
        if (remote.enabled()) return remote.overview(organizationId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("models", models(organizationId));
        out.put("experiments", experiments(organizationId));
        out.put("suggestions", suggestions(organizationId));
        out.put("shadow", shadowSummary(organizationId));
        out.put("confirmed", jdbc.queryForObject("select count(*) from known_outcome where label in ('FRAUD', 'LEGIT')", Integer.class));
        out.put("limitation", "Métricas de arquivo ou de experimento vêm de operações sintéticas, salvo quando a linha diz a origem. Elas não medem fraude bancária real.");
        out.put("labels", studies.labelsOverview(organizationId));
        out.put("studies", studies.studies(organizationId));
        out.put("ruleSuggestions", studies.ruleSuggestions(organizationId));
        return out;
    }

    public Map<String, Object> queue(int count, long seed, String actor) {
        if (remote.enabled()) {
            try {
                return enqueueRemote(count, seed, actor);
            } catch (org.springframework.web.client.RestClientResponseException e) {
                if (e.getStatusCode().value() == 400) throw ApiException.badRequest(downstream(e));
                if (e.getStatusCode().value() == 409) throw ApiException.conflict(downstream(e));
                throw e;
            }
        }
        if (count < MIN_EXAMPLES || count > MAX_EXAMPLES)
            throw ApiException.badRequest("A quantidade fica entre " + MIN_EXAMPLES + " e " + MAX_EXAMPLES + ".");
        long id = insertExperiment(count, seed, actor);
        audit.log(actor, "ML_EXPERIMENT", String.valueOf(id), count + " exemplos, semente " + seed);
        kickWorker();
        return experiment(id);
    }

    private Map<String, Object> enqueueRemote(int count, long seed, String actor) {
        Confirmed confirmed = confirmed();
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < confirmed.y().length; i++) {
            java.util.List<Double> features = new java.util.ArrayList<>();
            for (double value : confirmed.x()[i]) features.add(value);
            rows.add(Map.of("features", features, "label", confirmed.y()[i] == 1 ? "FRAUD" : "LEGIT"));
        }
        return remote.enqueue(organizations.idOfEmail(actor), actor, count, seed, rows);
    }

    private static String downstream(org.springframework.web.client.RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        String field = body != null && body.contains("\"message\"") ? "\"message\"" : "\"error\"";
        if (body != null && body.contains(field)) {
            int start = body.indexOf(field);
            int quote = body.indexOf('"', start + field.length());
            int end = quote < 0 ? -1 : body.indexOf('"', quote + 1);
            if (quote >= 0 && end > quote) return body.substring(quote + 1, end);
        }
        return "O serviço de modelos recusou o experimento.";
    }

    public Map<String, Object> experiment(long id) {
        List<Map<String, Object>> rows = jdbc.query("""
                select id, kind, status, seed, example_count, requested_by, created_at, finished_at, error, report_json, organization_id
                from ml_experiment where id = ?
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("organizationId", rs.getLong("organization_id"));
            row.put("kind", rs.getString("kind"));
            row.put("status", rs.getString("status"));
            row.put("seed", rs.getLong("seed"));
            row.put("examples", rs.getInt("example_count"));
            row.put("requestedBy", rs.getString("requested_by"));
            row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
            Timestamp finished = rs.getTimestamp("finished_at");
            row.put("finishedAt", finished == null ? null : finished.toLocalDateTime());
            row.put("error", rs.getString("error"));
            row.put("report", read(rs.getString("report_json")));
            return row;
        }, id);
        if (rows.isEmpty()) throw ApiException.notFound("Experimento");
        return rows.get(0);
    }

    public Map<String, Object> experiment(long id, long organizationId) {
        if (remote.enabled()) return remote.experiment(organizationId, id);
        Map<String, Object> row = experiment(id);
        if (((Number) row.get("organizationId")).longValue() != organizationId) throw ApiException.notFound("Experimento");
        return row;
    }

    public Map<String, Object> approveModel(long id, String actor) {
        if (remote.enabled()) return remote.approve(organizations.idOfEmail(actor), actor, id);
        Map<String, Object> row = jdbc.query("""
                select validation, coalesce(feature_set, 'operation') feature_set, coalesce(organization_id, 1) organization_id
                from ml_model where id = ?
                """, rs -> {
            if (!rs.next()) return null;
            Map<String, Object> found = new LinkedHashMap<>();
            found.put("validation", rs.getString("validation"));
            found.put("featureSet", rs.getString("feature_set"));
            found.put("organizationId", rs.getLong("organization_id"));
            return found;
        }, id);
        if (row == null) throw ApiException.notFound("Modelo");
        long organizationId = ((Number) row.get("organizationId")).longValue();
        if (organizationId != organizations.idOfEmail(actor)) throw ApiException.notFound("Modelo");
        String validation = String.valueOf(row.get("validation"));
        String featureSet = String.valueOf(row.get("featureSet"));
        boolean contextShadow = "context".equals(featureSet) && "RECORDED".equals(validation);
        if (!"PASS".equals(validation) && !contextShadow)
            throw ApiException.conflict("A validação automática não passou. O modelo de operação não pode ser aprovado.");
        jdbc.update("""
                update ml_model set status = 'VALIDATED'
                where status = 'APPROVED' and coalesce(feature_set, 'operation') = ? and coalesce(organization_id, 1) = ?
                """, featureSet, organizationId);
        jdbc.update("update ml_model set status = 'APPROVED', approved_by = ?, approved_at = ?, decision_authorized = false where id = ?",
                actor, Timestamp.valueOf(LocalDateTime.now().withNano(0)), id);
        String detail = contextShadow
                ? "Aprovado só para sombra. A meta de aceite não foi atingida e o modelo não decide a compra."
                : "Modo sombra. Não decide a compra.";
        audit.log(actor, "ML_APPROVED", String.valueOf(id), detail);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", "APPROVED");
        out.put("usage", "shadow");
        out.put("featureSet", featureSet);
        out.put("decisionAuthorized", false);
        out.put("acceptanceGatePassed", "PASS".equals(validation));
        out.put("message", detail);
        return out;
    }

    public Map<String, Object> refuseDecisionUse(long id, String actor) {
        if (remote.enabled()) return Map.of("id", id, "decisionAuthorized", false,
                "message", "A aprovação experimental não autoriza o modelo a decidir. A compra continua com o motor de regras.");
        requireModel(id, actor);
        jdbc.update("update ml_model set decision_authorized = false where id = ?", id);
        audit.log(actor, "ML_DECISION_REFUSED", String.valueOf(id), "O modelo continua sem poder aprovar ou bloquear.");
        return Map.of("id", id, "decisionAuthorized", false,
                "message", "A aprovação experimental não autoriza o modelo a decidir. A compra continua com o motor de regras.");
    }

    public Map<String, Object> rejectModel(long id, String actor) {
        if (remote.enabled()) return remote.reject(organizations.idOfEmail(actor), actor, id);
        requireModel(id, actor);
        int n = jdbc.update("update ml_model set status = 'REJECTED' where id = ? and status <> 'APPROVED'", id);
        if (n == 0) throw ApiException.conflict("Desaprove o modelo em uso antes, ou ele não existe.");
        audit.log(actor, "ML_REJECTED", String.valueOf(id), null);
        return Map.of("id", id, "status", "REJECTED");
    }

    public Map<String, Object> recordOutcome(String txId, String label, String actor) {
        if (!List.of("FRAUD", "LEGIT", "INCONCLUSIVE").contains(label))
            throw ApiException.badRequest("O resultado confirmado é FRAUD, LEGIT ou INCONCLUSIVE.");
        var tx = transactions.findById(txId).orElseThrow(() -> ApiException.notFound("Transação"));
        double[] features = Features.vector(inputs.build(tx, "initial", tx.getCreatedAt(), null));
        jdbc.update("""
                insert into known_outcome (tx_id, label, source, features_json, created_by, created_at, review_state, origin_kind)
                values (?, ?, 'ANALYST', ?, ?, ?, 'REVIEWED', 'VERIFIED')
                """, txId, label, write(features), actor, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
        audit.log(actor, "OUTCOME_RECORDED", txId, label);
        return Map.of("txId", txId, "label", label, "source", "ANALYST", "reviewState", "REVIEWED", "originKind", "VERIFIED",
                "usedAsTrainingLabel", true);
    }

    /** Denúncia ainda não revista. Não entra no treino. A decisão do motor também não vira rótulo. */
    public Map<String, Object> recordReport(String txId, String label, String actor) {
        if (!List.of("FRAUD", "LEGIT", "INCONCLUSIVE").contains(label))
            throw ApiException.badRequest("O relato é FRAUD, LEGIT ou INCONCLUSIVE.");
        if (!transactions.existsById(txId)) throw ApiException.notFound("Transação");
        jdbc.update("""
                insert into known_outcome (tx_id, label, source, features_json, created_by, created_at, review_state, origin_kind)
                values (?, ?, 'REPORT', '[]', ?, ?, 'PENDING', 'REPORT')
                """, txId, label, actor, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
        audit.log(actor, "OUTCOME_REPORTED", txId, label + " pendente; não treina");
        return Map.of("txId", txId, "label", label, "reviewState", "PENDING", "originKind", "REPORT", "usedAsTrainingLabel", false);
    }

    public Map<String, Object> monitor(long organizationId) {
        if (remote.enabled()) return remote.monitor(organizationId);
        Integer predictions = jdbc.queryForObject("select count(*) from ml_shadow where error is null and organization_id = ?", Integer.class, organizationId);
        Integer failures = jdbc.queryForObject("select count(*) from ml_shadow where error is not null and organization_id = ?", Integer.class, organizationId);
        Double latency = jdbc.queryForObject("select avg(latency_ms) from ml_shadow where error is null and organization_id = ?", Double.class, organizationId);
        Integer reviewed = jdbc.queryForObject("""
                select count(*) from known_outcome k
                join transactions t on t.id = k.tx_id
                where k.review_state = 'REVIEWED' and k.origin_kind in ('VERIFIED', 'ANALYST') and k.label in ('FRAUD', 'LEGIT')
                  and coalesce(t.organization_id, 1) = ?
                """, Integer.class, organizationId);
        List<Map<String, Object>> byPrediction = jdbc.query("""
                select coalesce(stage, 'initial') stage, prediction, count(*) total
                from ml_shadow where error is null and organization_id = ?
                group by coalesce(stage, 'initial'), prediction order by 1, 2
                """, (rs, n) -> Map.of("stage", rs.getString(1), "prediction", rs.getString(2), "total", rs.getInt(3)), organizationId);
        Integer disagreements = jdbc.queryForObject("""
                select count(*) from ml_shadow where error is null and organization_id = ? and (
                    (prediction = 'FRAUD' and engine_decision in ('APPROVE', 'APPROVE_WITH_ALERT'))
                    or (prediction = 'LEGIT' and engine_decision in ('BLOCK', 'INTERVENE'))
                )
                """, Integer.class, organizationId);
        int labels = reviewed == null ? 0 : reviewed;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("predictions", predictions == null ? 0 : predictions);
        out.put("failures", failures == null ? 0 : failures);
        out.put("meanLatencyMs", latency == null ? null : Math.round(latency * 1000.0) / 1000.0);
        out.put("byPrediction", byPrediction);
        out.put("disagreementsWithEngine", disagreements == null ? 0 : disagreements);
        out.put("reviewedLabels", labels);
        out.put("qualityAvailable", false);
        out.put("qualityNote", labels < 30
                ? "Não há rótulos revisados suficientes para precisão ou recall. As contagens acima são operacionais."
                : "Há rótulos revisados, mas a precisão só entra quando cada previsão tiver o mesmo caso confirmado. Isso ainda não foi calculado.");
        out.put("drift", "não calculada: não há baseline de produção");
        out.put("automaticRetrain", false);
        return out;
    }

    public Map<String, Object> draftFromSuggestion(long id, String actor) {
        String json = jdbc.query("select weights_json, status from weight_suggestion where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, id);
        if (json == null) throw ApiException.notFound("Sugestão");
        Map<String, Integer> weights = mapper.convertValue(read(json), new TypeReference<>() {});
        if (weights.isEmpty()) throw ApiException.conflict("Esta sugestão não recomenda mudança.");
        long policy = policies.createFromSuggestion("Sugestão do laboratório", weights, actor);
        jdbc.update("update weight_suggestion set status = 'DRAFTED' where id = ?", id);
        audit.log(actor, "SUGGESTION_DRAFT", String.valueOf(policy), "sugestão " + id);
        return policies.get(policy);
    }

    private void drainQueue() {
        try {
            for (;;) {
                ExperimentFila.failExpired(jdbc, "ml_experiment", maxRun);
                Long id = ExperimentFila.claimNext(jdbc, "ml_experiment");
                if (id == null) break;
                Job job = jdbc.query("""
                                select id, example_count, seed from ml_experiment where id = ? and status = 'RUNNING'
                                """, rs -> {
                            if (!rs.next()) return null;
                            return new Job(rs.getLong("id"), rs.getInt("example_count"), rs.getLong("seed"));
                        }, id);
                if (job == null) continue;
                runOne(job);
            }
        } finally {
            workerRunning.set(false);
            if (!remote.enabled() && ExperimentFila.hasInFlight(jdbc, "ml_experiment")) {
                kickWorker();
            }
        }
    }

    private record Job(long id, int count, long seed) {}

    private void runOne(Job job) {
        Future<?> run = training.submit(() -> {
            try {
                Confirmed confirmed = confirmed();
                Map<String, Object> report = Experiment.run(job.count(), job.seed(), directory(), confirmed.x, confirmed.y);
                jdbc.update("update ml_experiment set status = 'DONE', finished_at = ?, report_json = ? where id = ?",
                        Timestamp.valueOf(LocalDateTime.now().withNano(0)), mapper.writeValueAsString(report), job.id());
                storeModels(job.id(), report);
                storeSuggestion(job.id(), report);
            } catch (Exception e) {
                log.warn("Experimento {} falhou", job.id(), e);
                jdbc.update("update ml_experiment set status = 'FAILED', finished_at = ?, error = ? where id = ? and status = 'RUNNING'",
                        Timestamp.valueOf(LocalDateTime.now().withNano(0)), trim(e.getMessage()), job.id());
            }
        });
        try {
            run.get(maxRun.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            run.cancel(true);
            jdbc.update("update ml_experiment set status = 'FAILED', finished_at = ?, error = ? where id = ? and status = 'RUNNING'",
                    Timestamp.valueOf(LocalDateTime.now().withNano(0)), ExperimentFila.ERR_TIMEOUT, job.id());
        } catch (Exception e) {
            jdbc.update("update ml_experiment set status = 'FAILED', finished_at = ?, error = ? where id = ? and status = 'RUNNING'",
                    Timestamp.valueOf(LocalDateTime.now().withNano(0)), trim(e.getMessage()), job.id());
        }
    }

    @SuppressWarnings("unchecked")
    private void storeModels(long experimentId, Map<String, Object> report) {
        storeModel(experimentId, (Map<String, Object>) report.get("logistic"), report);
        storeModel(experimentId, (Map<String, Object>) report.get("forest"), report);
    }

    @SuppressWarnings("unchecked")
    private void storeModel(long experimentId, Map<String, Object> model, Map<String, Object> report) {
        Map<String, Object> metrics = (Map<String, Object>) model.get("metrics");
        jdbc.update("""
                insert into ml_model (experiment_id, algorithm, status, path, metrics_json, data_origin, example_count,
                    fraud_count, legit_count, library, library_version, validation, created_at, feature_set, organization_id)
                values (?, ?, 'TRAINED', ?, ?, 'sintetico', ?, ?, ?, 'smile-core', ?, ?, ?, ?, ?)
                """, experimentId, model.get("algorithm"), model.get("path"), write(metrics),
                metrics.get("examples"), metrics.get("frauds"), metrics.get("legits"), report.get("libraryVersion"),
                model.get("validation"), Timestamp.valueOf(LocalDateTime.now().withNano(0)),
                model.get("featureSet") == null ? "operation" : model.get("featureSet"), organizationOf(experimentId));
    }

    @SuppressWarnings("unchecked")
    private void storeSuggestion(long experimentId, Map<String, Object> report) throws Exception {
        Map<String, Object> optimization = (Map<String, Object>) report.get("optimization");
        if (optimization == null || !Boolean.TRUE.equals(optimization.get("improvedOnBothSets"))) return;
        jdbc.update("insert into weight_suggestion (experiment_id, weights_json, metrics_json, status, note, created_at) values (?, ?, ?, 'SUGGESTED', ?, ?)",
                experimentId, mapper.writeValueAsString(optimization.get("suggestedWeights")), mapper.writeValueAsString(optimization),
                optimization.get("note"), Timestamp.valueOf(LocalDateTime.now().withNano(0)));
    }

    private void importReportIfEmpty() {
        Integer models = jdbc.queryForObject("select count(*) from ml_model", Integer.class);
        if (models != null && models > 0) return;
        Path report = directory().resolve("reports").resolve("training-report.json");
        if (!Files.isRegularFile(report)) return;
        try {
            Map<String, Object> body = mapper.readValue(report.toFile(), new TypeReference<>() {});
            long id = insertExperiment(((Number) body.get("requestedExamples")).intValue(), ((Number) body.get("seed")).longValue(), "sistema");
            jdbc.update("update ml_experiment set status = 'DONE', finished_at = ?, report_json = ? where id = ?",
                    Timestamp.valueOf(LocalDateTime.now().withNano(0)), mapper.writeValueAsString(body), id);
            storeModels(id, body);
            storeSuggestion(id, body);
            log.info("Relatório de treino importado de {}", report.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Não importei o relatório de treino: {}", e.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private void importContextIfMissing() {
        Integer existing = jdbc.queryForObject("select count(*) from ml_model where feature_set = 'context'", Integer.class);
        if (existing != null && existing > 0) return;
        Path report = directory().resolve("reports").resolve("context-study.json");
        if (!Files.isRegularFile(report)) return;
        try {
            Map<String, Object> body = mapper.readValue(report.toFile(), new TypeReference<>() {});
            Map<String, Object> paths = (Map<String, Object>) body.get("paths");
            Map<String, Object> reserved = (Map<String, Object>) body.get("reservedEvaluation");
            if (paths == null || reserved == null) return;
            long id = insertExperiment(((Number) body.get("developmentExamples")).intValue(),
                    ((Number) body.get("developmentSeed")).longValue(), "sistema");
            jdbc.update("update ml_experiment set status = 'DONE', finished_at = ?, report_json = ? where id = ?",
                    Timestamp.valueOf(LocalDateTime.now().withNano(0)), mapper.writeValueAsString(body), id);
            storeContext(id, "logistic-regression-context", paths.get("logistic"), (Map<String, Object>) reserved.get("contextLogistic"), body);
            storeContext(id, "random-forest-context", paths.get("forest"), (Map<String, Object>) reserved.get("contextForest"), body);
            log.info("Estudo contextual importado de {}", report.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Não importei o estudo contextual: {}", e.toString());
        }
    }

    private void storeContext(long experimentId, String algorithm, Object path, Map<String, Object> metrics, Map<String, Object> report) {
        jdbc.update("""
                insert into ml_model (experiment_id, algorithm, status, path, metrics_json, data_origin, example_count,
                    fraud_count, legit_count, library, library_version, validation, created_at, feature_set, organization_id)
                values (?, ?, 'TRAINED', ?, ?, 'sintetico', ?, ?, ?, 'smile-core', ?, 'RECORDED', ?, 'context', ?)
                """, experimentId, algorithm, path, write(metrics),
                metrics.get("examples"), metrics.get("frauds"), metrics.get("legits"),
                String.valueOf(report.get("library")).replace("smile-core ", ""),
                Timestamp.valueOf(LocalDateTime.now().withNano(0)), organizationOf(experimentId));
    }

    private long insertExperiment(int count, long seed, String actor) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into ml_experiment (kind, status, seed, example_count, requested_by, created_at, organization_id) values ('TRAIN', 'QUEUED', ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, seed);
            ps.setInt(2, count);
            ps.setString(3, actor);
            ps.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
            ps.setLong(5, organizations.idOfEmail(actor));
            return ps;
        }, keys);
        return GeneratedIds.of(keys);
    }

    private long organizationOf(long experimentId) {
        Long id = jdbc.queryForObject("select coalesce(organization_id, 1) from ml_experiment where id = ?", Long.class, experimentId);
        return id == null ? 1L : id;
    }

    private void requireModel(long id, String actor) {
        Long organizationId = jdbc.query("select organization_id from ml_model where id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, id);
        if (organizationId == null || organizationId != organizations.idOfEmail(actor)) throw ApiException.notFound("Modelo");
    }

    private List<Map<String, Object>> models(long organizationId) {
        return jdbc.query("""
                select id, algorithm, status, path, metrics_json, data_origin, example_count, fraud_count, legit_count,
                       library, library_version, validation, created_at, approved_by, decision_authorized, feature_set
                from ml_model where coalesce(organization_id, 1) = ? order by id desc
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("algorithm", rs.getString("algorithm"));
            row.put("status", rs.getString("status"));
            row.put("path", rs.getString("path"));
            row.put("metrics", read(rs.getString("metrics_json")));
            row.put("origin", rs.getString("data_origin"));
            row.put("examples", rs.getInt("example_count"));
            row.put("frauds", rs.getInt("fraud_count"));
            row.put("legits", rs.getInt("legit_count"));
            row.put("library", rs.getString("library") + " " + rs.getString("library_version"));
            row.put("validation", rs.getString("validation"));
            row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
            row.put("approvedBy", rs.getString("approved_by"));
            row.put("decisionAuthorized", rs.getBoolean("decision_authorized"));
            row.put("featureSet", rs.getString("feature_set"));
            row.put("usage", "APPROVED".equals(rs.getString("status")) ? "shadow" : "stored");
            return row;
        }, organizationId);
    }

    private List<Map<String, Object>> experiments(long organizationId) {
        return jdbc.query("select id, status, seed, example_count, requested_by, created_at, finished_at, error from ml_experiment where coalesce(organization_id, 1) = ? order by id desc",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong(1));
                    row.put("status", rs.getString(2));
                    row.put("seed", rs.getLong(3));
                    row.put("examples", rs.getInt(4));
                    row.put("requestedBy", rs.getString(5));
                    row.put("createdAt", rs.getTimestamp(6).toLocalDateTime());
                    Timestamp finished = rs.getTimestamp(7);
                    row.put("finishedAt", finished == null ? null : finished.toLocalDateTime());
                    row.put("error", rs.getString(8));
                    return row;
                }, organizationId);
    }

    private List<Map<String, Object>> suggestions(long organizationId) {
        return jdbc.query("""
                select s.id, s.status, s.note, s.weights_json, s.metrics_json, s.created_at
                from weight_suggestion s
                join ml_experiment e on e.id = s.experiment_id
                where coalesce(e.organization_id, 1) = ?
                order by s.id desc
                """,
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("status", rs.getString("status"));
                    row.put("note", rs.getString("note"));
                    row.put("weights", read(rs.getString("weights_json")));
                    row.put("metrics", read(rs.getString("metrics_json")));
                    row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
                    return row;
                }, organizationId);
    }

    private Map<String, Object> shadowSummary(long organizationId) {
        Integer total = jdbc.queryForObject("select count(*) from ml_shadow where organization_id = ?", Integer.class, organizationId);
        Integer failures = jdbc.queryForObject("select count(*) from ml_shadow where error is not null and organization_id = ?", Integer.class, organizationId);
        Integer agree = jdbc.queryForObject("""
                select count(*) from ml_shadow where error is null and organization_id = ? and (
                    (prediction = 'FRAUD' and engine_decision in ('BLOCK', 'INTERVENE', 'REQUEST_CONTEXT'))
                    or (prediction = 'LEGIT' and engine_decision in ('APPROVE', 'APPROVE_WITH_ALERT'))
                )
                """, Integer.class, organizationId);
        return Map.of("predictions", total == null ? 0 : total, "failures", failures == null ? 0 : failures,
                "sameDirection", agree == null ? 0 : agree);
    }

    private record Confirmed(double[][] x, int[] y) {}

    private Confirmed confirmed() {
        List<double[]> rows = new ArrayList<>();
        List<Integer> labels = new ArrayList<>();
        jdbc.query("""
                select label, features_json from known_outcome
                where label in ('FRAUD', 'LEGIT') and source = 'ANALYST'
                  and review_state = 'REVIEWED' and origin_kind in ('VERIFIED', 'ANALYST')
                order by id
                """, rs -> {
            Object parsed = read(rs.getString(2));
            if (!(parsed instanceof List<?> list) || list.size() != Features.NAMES.length) return;
            double[] row = new double[list.size()];
            for (int i = 0; i < list.size(); i++) row[i] = ((Number) list.get(i)).doubleValue();
            rows.add(row);
            labels.add("FRAUD".equals(rs.getString(1)) ? 1 : 0);
        });
        int[] y = new int[labels.size()];
        for (int i = 0; i < labels.size(); i++) y[i] = labels.get(i);
        return new Confirmed(rows.toArray(double[][]::new), y);
    }

    private Path directory() {
        for (Path candidate : List.of(Path.of("machine-learning"), Path.of("..", "machine-learning"), Path.of("..", "..", "machine-learning"))) {
            if (Files.isDirectory(candidate)) return candidate;
        }
        return Path.of("machine-learning");
    }

    private Object read(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, Object.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String trim(String message) {
        if (message == null || message.isBlank()) return "falha no experimento";
        return message.length() > 480 ? message.substring(0, 480) : message;
    }
}
