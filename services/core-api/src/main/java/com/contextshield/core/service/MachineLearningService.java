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
    private volatile SavedModel model;
    private volatile long modelId = -1;
    private volatile SavedModel contextModel;
    private volatile long contextModelId = -1;

    public MachineLearningService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> shadow(String txId, Map<String, Object> checkout, String engineDecision) {
        return shadow(txId, checkout, engineDecision, "initial");
    }

    public Map<String, Object> shadow(String txId, Map<String, Object> checkout, String engineDecision, String stage) {
        Map<String, Object> first = predict(txId, checkout, engineDecision, stage, "operation", false);
        if ("recalculated".equals(stage) && checkout.get("answers") instanceof Map<?, ?> answers && !answers.isEmpty()) {
            predict(txId, checkout, engineDecision, stage, "context", true);
        }
        return first;
    }

    private Map<String, Object> predict(String txId, Map<String, Object> checkout, String engineDecision, String stage,
                                        String featureSet, boolean contextual) {
        Map<String, Object> quiet = new java.util.LinkedHashMap<>();
        quiet.put("mode", "shadow");
        quiet.put("stage", stage);
        quiet.put("featureSet", featureSet);
        quiet.put("active", false);
        quiet.put("affectsDecision", false);
        long usedId = -1;
        try {
            SavedModel current = approved(featureSet);
            if (current == null) return quiet;
            usedId = "context".equals(featureSet) ? contextModelId : modelId;
            long started = System.nanoTime();
            SavedModel.Prediction prediction = current.predict(contextual
                    ? com.contextshield.ml.ContextFeatures.vector(checkout)
                    : Features.vector(checkout));
            double ms = (System.nanoTime() - started) / 1_000_000.0;
            jdbc.update("""
                    insert into ml_shadow (tx_id, model_id, algorithm, prediction, probability, engine_decision, latency_ms, at, stage)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, txId, usedId, prediction.algorithm(), prediction.label(), prediction.fraudProbability(),
                    engineDecision, ms, Timestamp.valueOf(LocalDateTime.now().withNano(0)), stage);
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
            return out;
        } catch (Exception e) {
            log.warn("ML em modo sombra falhou; a decisão do motor permanece: {}", e.toString());
            try {
                jdbc.update("insert into ml_shadow (tx_id, model_id, engine_decision, at, error, stage) values (?, ?, ?, ?, ?, ?)",
                        txId, usedId > 0 ? usedId : null, engineDecision, Timestamp.valueOf(LocalDateTime.now().withNano(0)),
                        trim(e.getMessage()), stage);
            } catch (Exception ignored) {
                log.warn("Não foi possível registrar a falha do modo sombra.");
            }
            quiet.put("error", true);
            return quiet;
        }
    }

    public List<Map<String, Object>> rows(String txId) {
        return jdbc.query("""
                select model_id, algorithm, prediction, probability, engine_decision, latency_ms, error, at, stage
                from ml_shadow where tx_id = ? order by id
                """, (rs, n) -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("modelId", rs.getObject("model_id"));
            row.put("stage", rs.getString("stage"));
            row.put("algorithm", rs.getString("algorithm"));
            row.put("prediction", rs.getString("prediction"));
            row.put("probability", rs.getObject("probability"));
            row.put("engineDecision", rs.getString("engine_decision"));
            row.put("latencyMs", rs.getObject("latency_ms"));
            row.put("error", rs.getString("error"));
            row.put("affectsDecision", false);
            row.put("at", rs.getTimestamp("at").toLocalDateTime());
            return row;
        }, txId);
    }

    private SavedModel approved(String featureSet) throws Exception {
        List<String> paths = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        jdbc.query("select id, path from ml_model where status = 'APPROVED' and coalesce(feature_set, 'operation') = ? order by id desc",
                rs -> {
                    ids.add(rs.getLong("id"));
                    paths.add(rs.getString("path"));
                }, featureSet);
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
