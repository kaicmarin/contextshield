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

    public MachineLearningService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> shadow(String txId, Map<String, Object> checkout, String engineDecision) {
        Map<String, Object> quiet = new java.util.LinkedHashMap<>();
        quiet.put("mode", "shadow");
        quiet.put("active", false);
        quiet.put("affectsDecision", false);
        try {
            SavedModel current = approved();
            if (current == null) return quiet;
            long started = System.nanoTime();
            SavedModel.Prediction prediction = current.predict(Features.vector(checkout));
            double ms = (System.nanoTime() - started) / 1_000_000.0;
            jdbc.update("""
                    insert into ml_shadow (tx_id, model_id, algorithm, prediction, probability, engine_decision, latency_ms, at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, txId, modelId, prediction.algorithm(), prediction.label(), prediction.fraudProbability(),
                    engineDecision, ms, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("mode", "shadow");
            out.put("active", true);
            out.put("affectsDecision", false);
            out.put("modelId", modelId);
            out.put("algorithm", prediction.algorithm());
            out.put("prediction", prediction.label());
            out.put("probability", prediction.fraudProbability());
            out.put("latencyMs", Math.round(ms * 1000.0) / 1000.0);
            return out;
        } catch (Exception e) {
            log.warn("ML em modo sombra falhou; a decisão do motor permanece: {}", e.toString());
            try {
                jdbc.update("insert into ml_shadow (tx_id, model_id, engine_decision, at, error) values (?, ?, ?, ?, ?)",
                        txId, modelId > 0 ? modelId : null, engineDecision, Timestamp.valueOf(LocalDateTime.now().withNano(0)),
                        trim(e.getMessage()));
            } catch (Exception ignored) {
                log.warn("Não foi possível registrar a falha do modo sombra.");
            }
            quiet.put("error", true);
            return quiet;
        }
    }

    public List<Map<String, Object>> rows(String txId) {
        return jdbc.query("""
                select model_id, algorithm, prediction, probability, engine_decision, latency_ms, error, at
                from ml_shadow where tx_id = ? order by id
                """, (rs, n) -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("modelId", rs.getObject("model_id"));
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

    private SavedModel approved() throws Exception {
        List<String> paths = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        jdbc.query("select id, path from ml_model where status = 'APPROVED' order by id desc", rs -> {
            ids.add(rs.getLong("id"));
            paths.add(rs.getString("path"));
        });
        if (ids.isEmpty()) return null;
        long id = ids.get(0);
        String path = paths.get(0);
        if (model != null && modelId == id) return model;
        synchronized (this) {
            if (model != null && modelId == id) return model;
            model = SavedModel.load(Path.of(path));
            modelId = id;
            return model;
        }
    }

    private static String trim(String message) {
        if (message == null || message.isBlank()) return "falha na inferência";
        return message.length() > 280 ? message.substring(0, 280) : message;
    }
}
