package com.contextshield.core.service;

import com.contextshield.ml.Features;
import com.contextshield.ml.SavedModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Inferência em modo sombra. A decisão da compra continua sendo a do Risk Engine.
 * Se o modelo faltar ou falhar, a compra segue sem ele.
 */
@Service
public class MachineLearningService {

    private static final Logger log = LoggerFactory.getLogger(MachineLearningService.class);

    private final JdbcTemplate jdbc;
    private final MlClient remote;
    private volatile SavedModel model;
    private volatile long modelId = -1;
    private volatile SavedModel contextModel;
    private volatile long contextModelId = -1;

    public MachineLearningService(JdbcTemplate jdbc, MlClient remote) {
        this.jdbc = jdbc;
        this.remote = remote;
    }

    public Map<String, Object> shadow(String txId, Map<String, Object> checkout, String engineDecision) {
        return shadow(txId, checkout, engineDecision, "initial", 1L);
    }

    public Map<String, Object> shadow(String txId, Map<String, Object> checkout, String engineDecision, String stage) {
        return shadow(txId, checkout, engineDecision, stage, 1L);
    }

    public Map<String, Object> shadow(String txId, Map<String, Object> checkout, String engineDecision, String stage, long organizationId) {
        Map<String, Object> first = predict(txId, checkout, engineDecision, stage, "operation", false, organizationId);
        if ("recalculated".equals(stage) && checkout.get("answers") instanceof Map<?, ?> answers && !answers.isEmpty()) {
            predict(txId, checkout, engineDecision, stage, "context", true, organizationId);
        }
        return first;
    }

    private Map<String, Object> predict(String txId, Map<String, Object> checkout, String engineDecision, String stage,
                                        String featureSet, boolean contextual, long organizationId) {
        Map<String, Object> quiet = new java.util.LinkedHashMap<>();
        quiet.put("mode", "shadow");
        quiet.put("stage", stage);
        quiet.put("featureSet", featureSet);
        quiet.put("active", false);
        quiet.put("affectsDecision", false);
        long usedId = -1;
        try {
            if (remote.enabled()) return predictRemote(txId, checkout, engineDecision, stage, featureSet, contextual, organizationId);
            SavedModel current = approved(featureSet, organizationId);
            if (current == null) return quiet;
            usedId = "context".equals(featureSet) ? contextModelId : modelId;
            long started = System.nanoTime();
            SavedModel.Prediction prediction = current.predict(contextual
                    ? com.contextshield.ml.ContextFeatures.vector(checkout)
                    : Features.vector(checkout));
            double ms = (System.nanoTime() - started) / 1_000_000.0;
            jdbc.update("""
                    insert into ml_shadow (tx_id, model_id, algorithm, prediction, probability, engine_decision, latency_ms, at, stage, organization_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, txId, usedId, prediction.algorithm(), prediction.label(), prediction.fraudProbability(),
                    engineDecision, ms, Timestamp.valueOf(LocalDateTime.now().withNano(0)), stage, organizationId);
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("mode", "shadow");
            out.put("stage", stage);
            out.put("featureSet", featureSet);
            out.put("active", true);
            out.put("affectsDecision", false);
            out.put("modelId", usedId);
            out.put("algorithm", prediction.algorithm());
            out.put("prediction", prediction.label());
            out.put("probability", prediction.fraudProbability());
            out.put("latencyMs", Math.round(ms * 1000.0) / 1000.0);
            out.put("explanation", explain(stage, prediction.label(), engineDecision));
            return out;
        } catch (Exception e) {
            log.warn("ML em modo sombra falhou; a decisão do motor permanece: {}", e.toString());
            try {
                jdbc.update("insert into ml_shadow (tx_id, model_id, engine_decision, at, error, stage, organization_id) values (?, ?, ?, ?, ?, ?, ?)",
                        txId, usedId > 0 ? usedId : null, engineDecision, Timestamp.valueOf(LocalDateTime.now().withNano(0)),
                        trim(e.getMessage()), stage, organizationId);
            } catch (Exception ignored) {
                log.warn("Não foi possível registrar a falha do modo sombra.");
            }
            quiet.put("error", true);
            return quiet;
        }
    }

    private Map<String, Object> predictRemote(String txId, Map<String, Object> checkout, String engineDecision, String stage,
                                              String featureSet, boolean contextual, long organizationId) {
        Map<String, Object> result = remote.predict(featureSet, checkout, contextual, organizationId, txId, stage, engineDecision);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("mode", "shadow");
        out.put("stage", stage);
        out.put("featureSet", featureSet);
        out.put("affectsDecision", false);
        boolean active = Boolean.TRUE.equals(result.get("active")) && result.get("prediction") != null;
        out.put("active", active);
        if (!active) {
            out.put("error", Boolean.TRUE.equals(result.get("error")));
            return out;
        }
        out.put("modelId", result.get("modelId"));
        out.put("algorithm", result.get("algorithm"));
        out.put("prediction", result.get("prediction"));
        out.put("probability", result.get("probability"));
        out.put("latencyMs", result.get("latencyMs"));
        out.put("explanation", explain(stage, String.valueOf(result.get("prediction")), engineDecision));
        return out;
    }

    public List<Map<String, Object>> rows(String txId) {
        if (remote.enabled()) {
            Long org = jdbc.query("select organization_id from transactions where id = ?",
                    rs -> rs.next() ? (Long) rs.getObject(1) : null, txId);
            return remote.shadow(org == null ? 1L : org, txId);
        }
        return jdbc.query("""
                select s.model_id, s.algorithm, s.prediction, s.probability, s.engine_decision, s.latency_ms, s.error, s.at, s.stage,
                       m.library_version, m.feature_set
                from ml_shadow s
                left join ml_model m on m.id = s.model_id
                where s.tx_id = ? order by s.id
                """, (rs, n) -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            String stage = rs.getString("stage");
            String prediction = rs.getString("prediction");
            String decision = rs.getString("engine_decision");
            row.put("modelId", rs.getObject("model_id"));
            row.put("stage", stage);
            row.put("algorithm", rs.getString("algorithm"));
            row.put("featureSet", rs.getString("feature_set"));
            row.put("libraryVersion", rs.getString("library_version"));
            row.put("prediction", prediction);
            row.put("probability", rs.getObject("probability"));
            row.put("engineDecision", decision);
            row.put("latencyMs", rs.getObject("latency_ms"));
            row.put("error", rs.getString("error"));
            row.put("affectsDecision", false);
            row.put("explanation", rs.getString("error") == null ? explain(stage, prediction, decision) : "A falha ficou registrada. A decisão continua sendo a do motor.");
            row.put("at", rs.getTimestamp("at").toLocalDateTime());
            return row;
        }, txId);
    }

    private static String explain(String stage, String prediction, String decision) {
        String when = "recalculated".equals(stage) ? "Depois das respostas declaradas" : "No checkout, antes das respostas";
        return when + ", o modelo estimou " + prediction + ". O motor decidiu " + decision + ". A estimativa fica ao lado dessa decisão.";
    }

    private SavedModel approved(String featureSet, long organizationId) throws Exception {
        List<String> paths = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        jdbc.query("""
                select id, path from ml_model
                where status = 'APPROVED' and coalesce(feature_set, 'operation') = ? and coalesce(organization_id, 1) = ?
                order by id desc
                """,
                rs -> {
                    ids.add(rs.getLong("id"));
                    paths.add(rs.getString("path"));
                }, featureSet, organizationId);
        if (ids.isEmpty()) return null;
        long id = ids.get(0);
        String path = paths.get(0);
        boolean contextual = "context".equals(featureSet);
        SavedModel cached = contextual ? contextModel : model;
        long cachedId = contextual ? contextModelId : modelId;
        if (cached != null && cachedId == id) return cached;
        synchronized (this) {
            cached = contextual ? contextModel : model;
            cachedId = contextual ? contextModelId : modelId;
            if (cached != null && cachedId == id) return cached;
            SavedModel loaded = SavedModel.load(Path.of(path));
            if (contextual) {
                contextModel = loaded;
                contextModelId = id;
            } else {
                model = loaded;
                modelId = id;
            }
            return loaded;
        }
    }

    private static String trim(String message) {
        if (message == null || message.isBlank()) return "falha na inferência";
        return message.length() > 280 ? message.substring(0, 280) : message;
    }
}
