package com.contextshield.mlservice;

import com.contextshield.ml.Experiment;
import com.contextshield.ml.Features;
import com.contextshield.ml.SavedModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Catálogo, sombra e experimentos ficam neste processo.
 * O treino grava numa pasta de trabalho e não substitui os arquivos .ser já publicados.
 */
@Service
public class CatalogService implements ApplicationRunner {
    static final long RESERVED_SEED = 9901L;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ModelCatalog files;
    private final Path modelsDir;
    private final Path workDir;
    private final int maxExamples;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final ExecutorService pool = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ml-experiment");
        thread.setDaemon(true);
        return thread;
    });

    public CatalogService(JdbcTemplate jdbc, ObjectMapper mapper, ModelCatalog files,
                          @Value("${contextshield.models-dir}") String modelsDir,
                          @Value("${contextshield.work-dir}") String workDir,
                          @Value("${contextshield.max-examples}") int maxExamples) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.files = files;
        this.modelsDir = Path.of(modelsDir).toAbsolutePath().normalize();
        this.workDir = Path.of(workDir).toAbsolutePath().normalize();
        this.maxExamples = maxExamples;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.update("""
                update ml_experiment set status = 'FAILED', error = 'O processo encerrou durante o experimento.', finished_at = ?
                where status in ('QUEUED', 'RUNNING')
                """, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
        registerPublished();
    }

    public Map<String, Object> models(long organizationId) {
        return Map.of("files", listModels(organizationId), "decidesPurchase", false);
    }

    public Map<String, Object> overview(long organizationId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("models", listModels(organizationId));
        out.put("experiments", experiments(organizationId));
        out.put("suggestions", suggestions(organizationId));
        out.put("shadow", monitor(organizationId));
        out.put("decidesPurchase", false);
        out.put("limitation", "Métricas de experimento vêm de operações sintéticas, salvo quando a linha diz a origem. Elas não medem fraude bancária real.");
        return out;
    }

    public Map<String, Object> reject(long organizationId, long id) {
        int changed = jdbc.update("""
                update ml_model set status = 'REJECTED', decision_authorized = false
                where id = ? and organization_id = ? and status <> 'APPROVED'
                """, id, organizationId);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Desaprove o modelo em uso antes, ou ele não existe.");
        return Map.of("id", id, "status", "REJECTED", "decisionAuthorized", false);
    }

    public Map<String, Object> predict(long organizationId, Map<String, Object> body) {
        String featureSet = body.get("featureSet") == null ? "operation" : String.valueOf(body.get("featureSet"));
        boolean contextual = Boolean.TRUE.equals(body.get("contextual")) || "context".equals(featureSet);
        if (!(body.get("checkout") instanceof Map<?, ?> checkout)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Compra ausente.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) checkout;
        Map<String, Object> model = resolve(organizationId, featureSet, body.get("modelFile"));
        if (model == null) {
            record(organizationId, text(body.get("txId")), null, null, null, null, text(body.get("engineDecision")),
                    0, "Não há modelo aprovado para esta organização.", text(body.get("stage")));
            Map<String, Object> quiet = base(false);
            quiet.put("error", true);
            return quiet;
        }
        long started = System.nanoTime();
        try {
            SavedModel saved = load(Path.of(String.valueOf(model.get("path"))));
            SavedModel.Prediction prediction = saved.predict(contextual ? com.contextshield.ml.ContextFeatures.vector(row) : Features.vector(row));
            double ms = (System.nanoTime() - started) / 1_000_000.0;
            record(organizationId, text(body.get("txId")), (Long) model.get("id"), String.valueOf(model.get("algorithm")),
                    prediction.label(), prediction.fraudProbability(), text(body.get("engineDecision")), ms, null, text(body.get("stage")));
            Map<String, Object> out = base(true);
            out.put("algorithm", prediction.algorithm());
            out.put("prediction", prediction.label());
            out.put("probability", prediction.fraudProbability());
            out.put("latencyMs", Math.round(ms * 1000.0) / 1000.0);
            out.put("modelId", model.get("id"));
            out.put("featureSet", model.get("featureSet"));
            return out;
        } catch (RuntimeException e) {
            record(organizationId, text(body.get("txId")), (Long) model.get("id"), String.valueOf(model.get("algorithm")),
                    null, null, text(body.get("engineDecision")), 0, trim(e.getMessage()), text(body.get("stage")));
            throw e;
        }
    }

    public Map<String, Object> enqueue(long organizationId, String actor, Map<String, Object> body) {
        int examples = body.get("examples") instanceof Number n ? n.intValue() : 0;
        long seed = body.get("seed") instanceof Number n ? n.longValue() : 0L;
        if (examples < 200 || examples > maxExamples) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A quantidade fica entre 200 e " + maxExamples + ".");
        }
        if (seed == RESERVED_SEED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A semente reservada não entra em treino nem em ajuste.");
        }
        if (workDir.startsWith(modelsDir)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A pasta de treino não pode ser a pasta dos modelos publicados.");
        }
        if (!busy.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um experimento em execução.");
        }
        long id;
        try {
            id = insertExperiment(organizationId, examples, seed, actor);
        } catch (RuntimeException e) {
            busy.set(false);
            throw e;
        }
        double[][] confirmedX = confirmedX(body.get("confirmed"));
        int[] confirmedY = confirmedY(body.get("confirmed"));
        pool.submit(() -> execute(id, organizationId, examples, seed, confirmedX, confirmedY));
        return experiment(organizationId, id);
    }

    public Map<String, Object> experiment(long organizationId, long id) {
        List<Map<String, Object>> rows = jdbc.query("""
                select id, status, seed, example_count, requested_by, created_at, finished_at, error, report_json
                from ml_experiment where id = ? and organization_id = ?
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
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
        }, id, organizationId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Experimento não encontrado.");
        return rows.get(0);
    }

    public Map<String, Object> monitor(long organizationId) {
        Integer predictions = jdbc.queryForObject("select count(*) from ml_shadow where error is null and organization_id = ?", Integer.class, organizationId);
        Integer failures = jdbc.queryForObject("select count(*) from ml_shadow where error is not null and organization_id = ?", Integer.class, organizationId);
        Double latency = jdbc.queryForObject("select avg(latency_ms) from ml_shadow where error is null and latency_ms > 0 and organization_id = ?", Double.class, organizationId);
        List<Map<String, Object>> byPrediction = jdbc.query("""
                select coalesce(stage, 'initial') stage, prediction, count(*) total
                from ml_shadow where error is null and organization_id = ?
                group by coalesce(stage, 'initial'), prediction order by 1, 2
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", rs.getString(1));
            row.put("prediction", rs.getString(2));
            row.put("total", rs.getInt(3));
            return row;
        }, organizationId);
        Integer disagreements = jdbc.queryForObject("""
                select count(*) from ml_shadow where error is null and organization_id = ? and (
                    (prediction = 'FRAUD' and engine_decision in ('APPROVE', 'APPROVE_WITH_ALERT'))
                    or (prediction = 'LEGIT' and engine_decision in ('BLOCK', 'INTERVENE'))
                )
                """, Integer.class, organizationId);
        Map<String, Object> out = new LinkedHashMap<>();
        Integer covered = jdbc.queryForObject("select count(distinct tx_id) from ml_shadow where error is null and organization_id = ?", Integer.class, organizationId);
        Integer uncovered = jdbc.queryForObject("""
                select count(distinct tx_id) from ml_shadow s
                where s.organization_id = ? and s.error is not null
                  and not exists (
                    select 1 from ml_shadow o
                    where o.organization_id = s.organization_id and o.tx_id = s.tx_id and o.error is null
                  )
                """, Integer.class, organizationId);
        out.put("predictions", predictions == null ? 0 : predictions);
        out.put("failures", failures == null ? 0 : failures);
        out.put("coveredPurchases", covered == null ? 0 : covered);
        out.put("uncoveredPurchases", uncovered == null ? 0 : uncovered);
        out.put("meanLatencyMs", latency == null ? null : Math.round(latency * 1000.0) / 1000.0);
        out.put("byPrediction", byPrediction);
        out.put("disagreementsWithEngine", disagreements == null ? 0 : disagreements);
        out.put("qualityAvailable", false);
        out.put("qualityNote", "Precisão e recall ficam de fora. Cada previsão ainda não tem um caso confirmado correspondente.");
        out.put("drift", "não calculada: não há baseline de produção");
        out.put("affectsDecision", false);
        out.put("automaticRetrain", false);
        return out;
    }

    public List<Map<String, Object>> shadow(long organizationId, String txId) {
        return jdbc.query("""
                select model_id, algorithm, prediction, probability, engine_decision, latency_ms, error, stage, at
                from ml_shadow where organization_id = ? and tx_id = ? order by id
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("modelId", rs.getObject("model_id"));
            row.put("algorithm", rs.getString("algorithm"));
            row.put("prediction", rs.getString("prediction"));
            row.put("probability", rs.getObject("probability"));
            row.put("engineDecision", rs.getString("engine_decision"));
            row.put("latencyMs", rs.getObject("latency_ms"));
            row.put("error", rs.getString("error"));
            row.put("stage", rs.getString("stage"));
            row.put("affectsDecision", false);
            row.put("at", rs.getTimestamp("at").toLocalDateTime());
            return row;
        }, organizationId, txId);
    }

    public Map<String, Object> approve(long organizationId, long id, String actor) {
        Map<String, Object> model = one(organizationId, id);
        String validation = String.valueOf(model.get("validation"));
        String featureSet = String.valueOf(model.get("featureSet"));
        boolean contextRecorded = "context".equals(featureSet) && "RECORDED".equals(validation);
        if (!"PASS".equals(validation) && !contextRecorded && !"RECORDED".equals(validation)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A validação não permite aprovar este modelo para sombra.");
        }
        jdbc.update("""
                update ml_model set status = 'VALIDATED'
                where status = 'APPROVED' and feature_set = ? and organization_id = ? and id <> ?
                """, featureSet, organizationId, id);
        jdbc.update("""
                update ml_model set status = 'APPROVED', approved_by = ?, decision_authorized = false where id = ? and organization_id = ?
                """, actor, id, organizationId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", "APPROVED");
        out.put("usage", "shadow");
        out.put("decisionAuthorized", false);
        out.put("featureSet", featureSet);
        return out;
    }

    public Map<String, Object> compare(long organizationId, long left, long right) {
        return Map.of("left", one(organizationId, left), "right", one(organizationId, right), "decidesPurchase", false);
    }

    private void execute(long id, long organizationId, int examples, long seed, double[][] x, int[] y) {
        jdbc.update("update ml_experiment set status = 'RUNNING' where id = ?", id);
        try {
            Path dir = workDir.resolve("job-" + id);
            Files.createDirectories(dir);
            Map<String, Object> report = Experiment.run(examples, seed, dir, x, y);
            jdbc.update("update ml_experiment set status = 'DONE', finished_at = ?, report_json = ?, work_dir = ? where id = ?",
                    Timestamp.valueOf(LocalDateTime.now().withNano(0)), mapper.writeValueAsString(report), dir.toString(), id);
            store(id, organizationId, report.get("logistic"));
            store(id, organizationId, report.get("forest"));
            storeSuggestion(id, organizationId, report.get("optimization"));
        } catch (Exception e) {
            jdbc.update("update ml_experiment set status = 'FAILED', finished_at = ?, error = ? where id = ?",
                    Timestamp.valueOf(LocalDateTime.now().withNano(0)), trim(e.getMessage()), id);
        } finally {
            busy.set(false);
        }
    }

    @SuppressWarnings("unchecked")
    private void store(long experimentId, long organizationId, Object raw) {
        if (!(raw instanceof Map<?, ?> model)) return;
        Map<String, Object> metrics = model.get("metrics") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String path = String.valueOf(model.get("path"));
        jdbc.update("""
                insert into ml_model (organization_id, file_name, algorithm, feature_set, status, path, metrics_json,
                    validation, decision_authorized, library_version, created_at)
                values (?, ?, ?, 'operation', 'TRAINED', ?, ?, ?, false, '3.1.1', ?)
                """, organizationId, Path.of(path).getFileName().toString(), model.get("algorithm"), path,
                write(metrics), model.get("validation"), Timestamp.valueOf(LocalDateTime.now().withNano(0)));
    }

    private void storeSuggestion(long experimentId, long organizationId, Object raw) throws Exception {
        if (!(raw instanceof Map<?, ?> optimization) || !Boolean.TRUE.equals(optimization.get("improvedOnBothSets"))) return;
        jdbc.update("""
                insert into ml_suggestion (experiment_id, organization_id, weights_json, note, status, created_at)
                values (?, ?, ?, ?, 'SUGGESTED', ?)
                """, experimentId, organizationId, mapper.writeValueAsString(optimization.get("suggestedWeights")),
                optimization.get("note"), Timestamp.valueOf(LocalDateTime.now().withNano(0)));
    }

    private void registerPublished() {
        try {
            for (String name : files.files()) {
                Integer found = jdbc.queryForObject("select count(*) from ml_model where file_name = ? and status = 'APPROVED'", Integer.class, name);
                if (found != null && found > 0) continue;
                Path path = modelsDir.resolve(name).normalize();
                if (!path.startsWith(modelsDir) || !Files.isRegularFile(path)) continue;
                String featureSet = name.contains("context") ? "context" : "operation";
                String algorithm = name.startsWith("random-forest") ? "random-forest" : "logistic-regression";
                jdbc.update("""
                        insert into ml_model (organization_id, file_name, algorithm, feature_set, status, path, validation,
                            decision_authorized, library_version, created_at, approved_by)
                        values (1, ?, ?, ?, 'APPROVED', ?, 'RECORDED', false, '3.1.1', ?, 'publicado')
                        """, name, algorithm, featureSet, path.toString(), Timestamp.valueOf(LocalDateTime.now().withNano(0)));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Não foi possível registrar os modelos publicados.", e);
        }
    }

    private Map<String, Object> resolve(long organizationId, String featureSet, Object fileName) {
        if (fileName != null && !String.valueOf(fileName).isBlank()) {
            String name = ModelCatalog.fileName(String.valueOf(fileName));
            List<Map<String, Object>> rows = jdbc.query("""
                    select id, algorithm, feature_set, path from ml_model
                    where file_name = ? and status = 'APPROVED' and organization_id = ?
                    order by id desc
                    """, (rs, n) -> modelRow(rs), name, organizationId);
            if (rows.isEmpty() && organizationId != 1L) {
                rows = jdbc.query("""
                        select id, algorithm, feature_set, path from ml_model
                        where file_name = ? and status = 'APPROVED' and organization_id = 1 and approved_by = 'publicado'
                        order by id desc
                        """, (rs, n) -> modelRow(rs), name);
            }
            return rows.isEmpty() ? null : rows.get(0);
        }
        List<Map<String, Object>> rows = jdbc.query("""
                select id, algorithm, feature_set, path from ml_model
                where status = 'APPROVED' and feature_set = ? and organization_id = ?
                order by id desc
                """, (rs, n) -> modelRow(rs), featureSet, organizationId);
        if (rows.isEmpty() && organizationId != 1L) {
            rows = jdbc.query("""
                    select id, algorithm, feature_set, path from ml_model
                    where status = 'APPROVED' and feature_set = ? and organization_id = 1 and approved_by = 'publicado'
                    order by id desc
                    """, (rs, n) -> modelRow(rs), featureSet);
        }
        return rows.isEmpty() ? null : rows.get(0);
    }

    private SavedModel load(Path path) {
        Path file = path.toAbsolutePath().normalize();
        if ((!file.startsWith(modelsDir) && !file.startsWith(workDir)) || !Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Modelo fora das pastas permitidas.");
        }
        try {
            return SavedModel.load(file);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível ler o modelo.");
        }
    }

    private List<Map<String, Object>> experiments(long organizationId) {
        return jdbc.query("""
                select id, status, seed, example_count, requested_by, created_at, finished_at, error
                from ml_experiment where organization_id = ? order by id desc
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("status", rs.getString("status"));
            row.put("seed", rs.getLong("seed"));
            row.put("examples", rs.getInt("example_count"));
            row.put("requestedBy", rs.getString("requested_by"));
            row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
            Timestamp finished = rs.getTimestamp("finished_at");
            row.put("finishedAt", finished == null ? null : finished.toLocalDateTime());
            row.put("error", rs.getString("error"));
            return row;
        }, organizationId);
    }

    private List<Map<String, Object>> suggestions(long organizationId) {
        return jdbc.query("""
                select id, status, note, weights_json, created_at from ml_suggestion
                where organization_id = ? order by id desc
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("status", rs.getString("status"));
            row.put("note", rs.getString("note"));
            row.put("weights", read(rs.getString("weights_json")));
            return row;
        }, organizationId);
    }

    private List<Map<String, Object>> listModels(long organizationId) {
        return jdbc.query("""
                select id, file_name, algorithm, feature_set, status, validation, decision_authorized, created_at
                from ml_model where organization_id = ? or (organization_id = 1 and approved_by = 'publicado')
                order by id desc
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("file", rs.getString("file_name"));
            row.put("algorithm", rs.getString("algorithm"));
            row.put("featureSet", rs.getString("feature_set"));
            row.put("status", rs.getString("status"));
            row.put("validation", rs.getString("validation"));
            row.put("decisionAuthorized", false);
            row.put("usage", "APPROVED".equals(rs.getString("status")) ? "shadow" : "stored");
            return row;
        }, organizationId);
    }

    private Map<String, Object> one(long organizationId, long id) {
        List<Map<String, Object>> rows = jdbc.query("""
                select id, file_name, algorithm, feature_set, status, validation, decision_authorized, metrics_json
                from ml_model where id = ? and organization_id = ?
                """, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("file", rs.getString("file_name"));
            row.put("algorithm", rs.getString("algorithm"));
            row.put("featureSet", rs.getString("feature_set"));
            row.put("status", rs.getString("status"));
            row.put("validation", rs.getString("validation"));
            row.put("decisionAuthorized", false);
            row.put("metrics", read(rs.getString("metrics_json")));
            return row;
        }, id, organizationId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Modelo não encontrado.");
        return rows.get(0);
    }

    private long insertExperiment(long organizationId, int examples, long seed, String actor) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into ml_experiment (organization_id, status, seed, example_count, requested_by, created_at)
                    values (?, 'QUEUED', ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setLong(1, organizationId);
            ps.setLong(2, seed);
            ps.setInt(3, examples);
            ps.setString(4, actor);
            ps.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("Experimento sem identificador.");
        return key.longValue();
    }

    private void record(long organizationId, String txId, Long modelId, String algorithm, String prediction,
                        Double probability, String decision, double latency, String error, String stage) {
        jdbc.update("""
                insert into ml_shadow (organization_id, tx_id, model_id, algorithm, prediction, probability,
                    engine_decision, latency_ms, error, stage, at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, organizationId, txId, modelId, algorithm, prediction, probability, decision, latency, error,
                stage == null || stage.isBlank() ? "initial" : stage, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
    }

    private static Map<String, Object> modelRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("algorithm", rs.getString("algorithm"));
        row.put("featureSet", rs.getString("feature_set"));
        row.put("path", rs.getString("path"));
        return row;
    }

    private static Map<String, Object> base(boolean active) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "shadow");
        out.put("active", active);
        out.put("affectsDecision", false);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static double[][] confirmedX(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) return new double[0][];
        List<double[]> rows = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map) || !(map.get("features") instanceof List<?> features)) continue;
            if (features.size() != Features.NAMES.length) continue;
            double[] row = new double[features.size()];
            for (int i = 0; i < features.size(); i++) row[i] = ((Number) features.get(i)).doubleValue();
            rows.add(row);
        }
        return rows.toArray(double[][]::new);
    }

    private static int[] confirmedY(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) return new int[0];
        List<Integer> labels = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map) || !(map.get("features") instanceof List<?> features)) continue;
            if (features.size() != Features.NAMES.length) continue;
            labels.add("FRAUD".equals(map.get("label")) ? 1 : 0);
        }
        int[] y = new int[labels.size()];
        for (int i = 0; i < labels.size(); i++) y[i] = labels.get(i);
        return y;
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

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trim(String message) {
        if (message == null || message.isBlank()) return "falha no experimento";
        return message.length() > 480 ? message.substring(0, 480) : message;
    }
}
