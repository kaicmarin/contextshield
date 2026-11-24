package com.contextshield.core.service;

import com.contextshield.core.web.ApiException;
import com.contextshield.ml.Experiment;
import com.contextshield.ml.Features;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final ExecutorService pool = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ml-experiment");
        thread.setDaemon(true);
        return thread;
    });

    public MlLabService(JdbcTemplate jdbc, ObjectMapper mapper, AuditService audit, RiskPolicyService policies,
                        EngineInputBuilder inputs, com.contextshield.core.repo.TransactionRepository transactions) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.audit = audit;
        this.policies = policies;
        this.inputs = inputs;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.update("update ml_experiment set status = 'FAILED', error = 'A aplicação encerrou durante o experimento.', finished_at = ? where status in ('QUEUED', 'RUNNING')",
                Timestamp.valueOf(LocalDateTime.now().withNano(0)));
        importReportIfEmpty();
    }

    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("models", models());
        out.put("experiments", experiments());
        out.put("suggestions", suggestions());
        out.put("shadow", shadowSummary());
        out.put("confirmed", jdbc.queryForObject("select count(*) from known_outcome where label in ('FRAUD', 'LEGIT')", Integer.class));
        out.put("limitation", "Métricas de arquivo ou de experimento vêm de operações sintéticas, salvo quando a linha diz a origem. Elas não medem fraude bancária real.");
        return out;
    }

    public Map<String, Object> queue(int count, long seed, String actor) {
        if (count < MIN_EXAMPLES || count > MAX_EXAMPLES)
            throw ApiException.badRequest("A quantidade fica entre " + MIN_EXAMPLES + " e " + MAX_EXAMPLES + ".");
        if (!busy.compareAndSet(false, true)) throw ApiException.conflict("Já existe um experimento em execução.");
        long id = insertExperiment(count, seed, actor);
        audit.log(actor, "ML_EXPERIMENT", String.valueOf(id), count + " exemplos, semente " + seed);
        pool.submit(() -> execute(id, count, seed));
        return experiment(id);
    }

    public Map<String, Object> experiment(long id) {
        List<Map<String, Object>> rows = jdbc.query("""
                select id, kind, status, seed, example_count, requested_by, created_at, finished_at, error, report_json
                from ml_experiment where id = ?
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
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

    public Map<String, Object> approveModel(long id, String actor) {
        String validation = jdbc.query("select validation from ml_model where id = ?", rs -> rs.next() ? rs.getString(1) : null, id);
        if (validation == null) throw ApiException.notFound("Modelo");
        if (!"PASS".equals(validation)) throw ApiException.conflict("A validação automática não passou. O modelo não pode ser aprovado.");
        jdbc.update("update ml_model set status = 'VALIDATED' where status = 'APPROVED'");
        jdbc.update("update ml_model set status = 'APPROVED', approved_by = ?, approved_at = ? where id = ?",
                actor, Timestamp.valueOf(LocalDateTime.now().withNano(0)), id);
        jdbc.update("update ml_model set decision_authorized = false where id = ?", id);
        audit.log(actor, "ML_APPROVED", String.valueOf(id), "Modo sombra. Não decide a compra.");
        return Map.of("id", id, "status", "APPROVED", "usage", "shadow", "decisionAuthorized", false);
    }

    public Map<String, Object> refuseDecisionUse(long id, String actor) {
        Integer count = jdbc.queryForObject("select count(*) from ml_model where id = ?", Integer.class, id);
        if (count == null || count == 0) throw ApiException.notFound("Modelo");
        jdbc.update("update ml_model set decision_authorized = false where id = ?", id);
        audit.log(actor, "ML_DECISION_REFUSED", String.valueOf(id), "O modelo continua sem poder aprovar ou bloquear.");
        return Map.of("id", id, "decisionAuthorized", false,
                "message", "A aprovação experimental não autoriza o modelo a decidir. A compra continua com o motor de regras.");
    }

    public Map<String, Object> rejectModel(long id, String actor) {
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
        jdbc.update("insert into known_outcome (tx_id, label, source, features_json, created_by, created_at) values (?, ?, 'ANALYST', ?, ?, ?)",
                txId, label, write(features), actor, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
        audit.log(actor, "OUTCOME_RECORDED", txId, label);
        return Map.of("txId", txId, "label", label, "source", "ANALYST");
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

    private void execute(long id, int count, long seed) {
        jdbc.update("update ml_experiment set status = 'RUNNING' where id = ?", id);
        try {
            Confirmed confirmed = confirmed();
            Map<String, Object> report = Experiment.run(count, seed, directory(), confirmed.x, confirmed.y);
            jdbc.update("update ml_experiment set status = 'DONE', finished_at = ?, report_json = ? where id = ?",
                    Timestamp.valueOf(LocalDateTime.now().withNano(0)), mapper.writeValueAsString(report), id);
            storeModels(id, report);
            storeSuggestion(id, report);
        } catch (Exception e) {
            log.warn("Experimento {} falhou", id, e);
            jdbc.update("update ml_experiment set status = 'FAILED', finished_at = ?, error = ? where id = ?",
                    Timestamp.valueOf(LocalDateTime.now().withNano(0)), trim(e.getMessage()), id);
        } finally {
            busy.set(false);
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
                    fraud_count, legit_count, library, library_version, validation, created_at)
                values (?, ?, 'TRAINED', ?, ?, 'sintetico', ?, ?, ?, 'smile-core', ?, ?, ?)
                """, experimentId, model.get("algorithm"), model.get("path"), write(metrics),
                metrics.get("examples"), metrics.get("frauds"), metrics.get("legits"), report.get("libraryVersion"),
                model.get("validation"), Timestamp.valueOf(LocalDateTime.now().withNano(0)));
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

    private long insertExperiment(int count, long seed, String actor) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into ml_experiment (kind, status, seed, example_count, requested_by, created_at) values ('TRAIN', 'QUEUED', ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, seed);
            ps.setInt(2, count);
            ps.setString(3, actor);
            ps.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
            return ps;
        }, keys);
        return GeneratedIds.of(keys);
    }

    private List<Map<String, Object>> models() {
        return jdbc.query("""
                select id, algorithm, status, path, metrics_json, data_origin, example_count, fraud_count, legit_count,
                       library, library_version, validation, created_at, approved_by, decision_authorized
                from ml_model order by id desc
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
            row.put("usage", "APPROVED".equals(rs.getString("status")) ? "shadow" : "stored");
            return row;
        });
    }

    private List<Map<String, Object>> experiments() {
        return jdbc.query("select id, status, seed, example_count, requested_by, created_at, finished_at, error from ml_experiment order by id desc",
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
                });
    }

    private List<Map<String, Object>> suggestions() {
        return jdbc.query("select id, status, note, weights_json, metrics_json, created_at from weight_suggestion order by id desc",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("status", rs.getString("status"));
                    row.put("note", rs.getString("note"));
                    row.put("weights", read(rs.getString("weights_json")));
                    row.put("metrics", read(rs.getString("metrics_json")));
                    row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
                    return row;
                });
    }

    private Map<String, Object> shadowSummary() {
        Integer total = jdbc.queryForObject("select count(*) from ml_shadow", Integer.class);
        Integer failures = jdbc.queryForObject("select count(*) from ml_shadow where error is not null", Integer.class);
        Integer agree = jdbc.queryForObject("""
                select count(*) from ml_shadow where error is null and (
                    (prediction = 'FRAUD' and engine_decision in ('BLOCK', 'INTERVENE', 'REQUEST_CONTEXT'))
                    or (prediction = 'LEGIT' and engine_decision in ('APPROVE', 'APPROVE_WITH_ALERT'))
                )
                """, Integer.class);
        return Map.of("predictions", total == null ? 0 : total, "failures", failures == null ? 0 : failures,
                "sameDirection", agree == null ? 0 : agree);
    }

    private record Confirmed(double[][] x, int[] y) {}

    private Confirmed confirmed() {
        List<double[]> rows = new ArrayList<>();
        List<Integer> labels = new ArrayList<>();
        jdbc.query("select label, features_json from known_outcome where label in ('FRAUD', 'LEGIT') and source = 'ANALYST' order by id", rs -> {
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
