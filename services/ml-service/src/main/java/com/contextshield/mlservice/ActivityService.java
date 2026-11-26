package com.contextshield.mlservice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ActivityService {

    static final int MAX_PAGE = 50;
    static final String CALIBRATION_NOTE = "A probabilidade exibida é a saída do classificador. Não representa risco calibrado de fraude real.";
    static final String ALIGN_NOTE = "Concordância com o motor indica alinhamento de direção, não acerto da previsão.";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ActivityService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Map<String, Object> summary(long organizationId, int days) {
        int span = Math.max(1, Math.min(days, 90));
        LocalDateTime since = LocalDateTime.now().minusDays(span).withNano(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cards", cards(organizationId, since));
        out.put("charts", charts(organizationId, since));
        return out;
    }

    public Map<String, Object> feed(long organizationId, int page, int size, String cursor, Long modelId, String algorithm,
                                    String prediction, String align, String stage, String status, String q) {
        int take = Math.max(1, Math.min(size <= 0 ? 25 : size, MAX_PAGE));
        List<Map<String, Object>> events = shadowEvents(organizationId, modelId, algorithm, prediction, align, stage, status, q);
        events.addAll(experimentEvents(organizationId, q));
        events.addAll(approvalEvents(organizationId, modelId));
        events.sort((a, b) -> ((LocalDateTime) b.get("at")).compareTo((LocalDateTime) a.get("at")));
        int from = page * take;
        int to = Math.min(events.size(), from + take);
        List<Map<String, Object>> slice = from >= events.size() ? List.of() : events.subList(from, to);
        return Map.of("items", slice, "hasMore", to < events.size(), "shadowMode", true);
    }

    public Map<String, Object> modelStats(long organizationId, long modelId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("predictionTotal", jdbc.queryForObject(
                "select count(*) from ml_shadow where organization_id = ? and model_id = ? and error is null", Integer.class, organizationId, modelId));
        stats.put("recentActivity", shadowEvents(organizationId, modelId, null, null, null, null, null, null).stream().limit(12).toList());
        return stats;
    }

    private Map<String, Object> cards(long organizationId, LocalDateTime since) {
        Map<String, Object> c = new LinkedHashMap<>();
        Integer total = jdbc.queryForObject("select count(*) from ml_shadow where organization_id = ? and error is null", Integer.class, organizationId);
        Integer last24h = jdbc.queryForObject(
                "select count(*) from ml_shadow where organization_id = ? and error is null and at >= ?",
                Integer.class, organizationId, Timestamp.valueOf(LocalDateTime.now().minusHours(24)));
        Integer failures = jdbc.queryForObject("select count(*) from ml_shadow where organization_id = ? and error is not null", Integer.class, organizationId);
        Integer active = jdbc.queryForObject("""
                select count(distinct model_id) from ml_shadow s join ml_model m on m.id = s.model_id
                where s.organization_id = ? and m.status = 'APPROVED' and s.error is null
                """, Integer.class, organizationId);
        Integer agree = jdbc.queryForObject("""
                select count(*) from ml_shadow where organization_id = ? and error is null and (
                (prediction = 'FRAUD' and engine_decision in ('BLOCK', 'INTERVENE', 'REQUEST_CONTEXT'))
                or (prediction = 'LEGIT' and engine_decision in ('APPROVE', 'APPROVE_WITH_ALERT')))
                """, Integer.class, organizationId);
        int ok = total == null ? 0 : total;
        int a = agree == null ? 0 : agree;
        Double latency = jdbc.queryForObject(
                "select avg(latency_ms) from ml_shadow where organization_id = ? and error is null and latency_ms > 0",
                Double.class, organizationId);
        Integer recentExp = jdbc.queryForObject(
                "select count(*) from ml_experiment where organization_id = ? and created_at >= ?",
                Integer.class, organizationId, Timestamp.valueOf(since));
        c.put("predictions", ok);
        c.put("predictions24h", last24h == null ? 0 : last24h);
        c.put("activeShadowModels", active == null ? 0 : active);
        c.put("agreements", a);
        c.put("disagreements", Math.max(0, ok - a));
        c.put("failures", failures == null ? 0 : failures);
        c.put("meanLatencyMs", latency == null ? null : Math.round(latency * 1000.0) / 1000.0);
        c.put("recentExperiments", recentExp == null ? 0 : recentExp);
        return c;
    }

    private Map<String, Object> charts(long organizationId, LocalDateTime since) {
        Map<String, Object> charts = new LinkedHashMap<>();
        charts.put("predictionsByDay", jdbc.query("""
                        select cast(at as date) d, count(*) total,
                               sum(case when prediction = 'FRAUD' then 1 else 0 end) fraud,
                               sum(case when prediction = 'LEGIT' then 1 else 0 end) legit
                        from ml_shadow where organization_id = ? and error is null and at >= ?
                        group by cast(at as date) order by d
                        """, (rs, n) -> Map.of("d", rs.getDate("d").toLocalDate().toString(), "total", rs.getInt("total"),
                        "fraud", rs.getInt("fraud"), "legit", rs.getInt("legit")),
                organizationId, Timestamp.valueOf(since)));
        charts.put("alignmentByDay", jdbc.query("""
                        select cast(at as date) d,
                               sum(case when (prediction = 'FRAUD' and engine_decision in ('BLOCK','INTERVENE','REQUEST_CONTEXT'))
                                     or (prediction = 'LEGIT' and engine_decision in ('APPROVE','APPROVE_WITH_ALERT')) then 1 else 0 end) agree,
                               sum(case when not ((prediction = 'FRAUD' and engine_decision in ('BLOCK','INTERVENE','REQUEST_CONTEXT'))
                                     or (prediction = 'LEGIT' and engine_decision in ('APPROVE','APPROVE_WITH_ALERT'))) then 1 else 0 end) disagree
                        from ml_shadow where organization_id = ? and error is null and at >= ?
                        group by cast(at as date) order by d
                        """, (rs, n) -> Map.of("d", rs.getDate("d").toLocalDate().toString(), "agree", rs.getInt("agree"), "disagree", rs.getInt("disagree")),
                organizationId, Timestamp.valueOf(since)));
        charts.put("latencyByModel", jdbc.query("""
                        select model_id, algorithm, round(cast(avg(latency_ms) as numeric), 2) avg_ms, count(*) total
                        from ml_shadow where organization_id = ? and error is null and latency_ms > 0 and at >= ?
                        group by model_id, algorithm order by total desc limit 8
                        """, (rs, n) -> Map.of("modelId", rs.getObject("model_id"), "algorithm", rs.getString("algorithm"),
                        "avgMs", rs.getDouble("avg_ms"), "total", rs.getInt("total")),
                organizationId, Timestamp.valueOf(since)));
        charts.put("predictionMix", jdbc.query("""
                        select coalesce(prediction, 'UNKNOWN') label, count(*) total
                        from ml_shadow where organization_id = ? and error is null and at >= ?
                        group by prediction order by total desc
                        """, (rs, n) -> Map.of("label", rs.getString("label"), "total", rs.getInt("total")),
                organizationId, Timestamp.valueOf(since)));
        return charts;
    }

    private List<Map<String, Object>> shadowEvents(long organizationId, Long modelId, String algorithm, String prediction,
                                                   String align, String stage, String status, String q) {
        StringBuilder sql = new StringBuilder("""
                select s.id, s.at, s.tx_id, s.model_id, s.algorithm, s.prediction, s.probability, s.engine_decision,
                       s.latency_ms, s.error, s.stage, m.validation, m.feature_set, m.status model_status
                from ml_shadow s left join ml_model m on m.id = s.model_id where s.organization_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(organizationId);
        if (modelId != null) { sql.append(" and s.model_id = ?"); args.add(modelId); }
        if (algorithm != null && !algorithm.isBlank()) { sql.append(" and lower(s.algorithm) = ?"); args.add(algorithm.toLowerCase(Locale.ROOT)); }
        if (prediction != null && !prediction.isBlank()) { sql.append(" and s.prediction = ?"); args.add(prediction); }
        if (stage != null && !stage.isBlank()) { sql.append(" and s.stage = ?"); args.add(stage); }
        if (status != null && "FAILED".equalsIgnoreCase(status)) sql.append(" and s.error is not null");
        else if (status != null && "OK".equalsIgnoreCase(status)) sql.append(" and s.error is null");
        if (q != null && !q.isBlank()) { sql.append(" and s.tx_id like ?"); args.add("%" + q.trim() + "%"); }
        if (align != null && "agree".equalsIgnoreCase(align)) {
            sql.append(" and s.error is null and ((s.prediction = 'FRAUD' and s.engine_decision in ('BLOCK','INTERVENE','REQUEST_CONTEXT')) or (s.prediction = 'LEGIT' and s.engine_decision in ('APPROVE','APPROVE_WITH_ALERT')))");
        } else if (align != null && "disagree".equalsIgnoreCase(align)) {
            sql.append(" and s.error is null and not ((s.prediction = 'FRAUD' and s.engine_decision in ('BLOCK','INTERVENE','REQUEST_CONTEXT')) or (s.prediction = 'LEGIT' and s.engine_decision in ('APPROVE','APPROVE_WITH_ALERT')))");
        }
        sql.append(" order by s.at desc, s.id desc limit 400");
        return jdbc.query(sql.toString(), (rs, n) -> {
            boolean failure = rs.getString("error") != null;
            String pred = rs.getString("prediction");
            String engine = rs.getString("engine_decision");
            Map<String, Object> row = new LinkedHashMap<>();
            long id = rs.getLong("id");
            row.put("key", "shadow:" + id);
            row.put("type", failure ? "SHADOW_FAILURE" : "PREDICTION");
            row.put("at", rs.getTimestamp("at").toLocalDateTime());
            row.put("title", failure ? "Falha de inferência (sombra)" : "Previsão em modo sombra");
            row.put("modelId", rs.getObject("model_id"));
            row.put("algorithm", rs.getString("algorithm"));
            row.put("txId", rs.getString("tx_id"));
            row.put("stage", rs.getString("stage"));
            row.put("prediction", pred);
            row.put("probability", rs.getObject("probability"));
            row.put("probabilityNote", failure ? null : CALIBRATION_NOTE);
            row.put("engineDecision", engine);
            row.put("alignedWithEngine", failure ? null : aligned(pred, engine));
            row.put("alignNote", ALIGN_NOTE);
            row.put("latencyMs", rs.getObject("latency_ms"));
            row.put("status", failure ? "FAILED" : "OK");
            row.put("error", rs.getString("error"));
            return row;
        }, args.toArray());
    }

    private List<Map<String, Object>> experimentEvents(long organizationId, String q) {
        String sql = "select id, status, seed, example_count, requested_by, created_at, finished_at, error, report_json from ml_experiment where organization_id = ? order by id desc limit 60";
        return jdbc.query(sql, rs -> {
            List<Map<String, Object>> out = new ArrayList<>();
            while (rs.next()) {
                long id = rs.getLong("id");
                LocalDateTime created = rs.getTimestamp("created_at").toLocalDateTime();
                out.add(expEvent(id, "EXPERIMENT_QUEUED", created, rs, "Experimento enfileirado"));
                if (rs.getTimestamp("finished_at") != null) {
                    LocalDateTime fin = rs.getTimestamp("finished_at").toLocalDateTime();
                    String st = rs.getString("status");
                    out.add(expEvent(id, "FAILED".equals(st) ? "EXPERIMENT_FAILED" : "EXPERIMENT_DONE", fin, rs,
                            "FAILED".equals(st) ? "Experimento falhou" : "Modelo treinado (experimento)"));
                }
            }
            return out;
        }, organizationId);
    }

    private Map<String, Object> expEvent(long id, String type, LocalDateTime at, java.sql.ResultSet rs, String title) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", type.toLowerCase(Locale.ROOT) + ":exp:" + id + ":" + at.truncatedTo(ChronoUnit.SECONDS));
        row.put("type", type);
        row.put("at", at);
        row.put("title", title);
        row.put("experimentId", id);
        row.put("status", rs.getString("status"));
        row.put("examples", rs.getObject("example_count"));
        row.put("dataOrigin", "sintetico");
        row.put("error", rs.getString("error"));
        row.put("report", read(rs.getString("report_json")));
        return row;
    }

    private List<Map<String, Object>> approvalEvents(long organizationId, Long modelId) {
        var mapper = (org.springframework.jdbc.core.RowMapper<Map<String, Object>>) (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            long id = rs.getLong("id");
            row.put("key", "model-approved:" + id);
            row.put("type", "MODEL_APPROVED");
            row.put("at", rs.getTimestamp("created_at").toLocalDateTime());
            row.put("title", "Modelo aprovado para modo sombra");
            row.put("modelId", id);
            row.put("algorithm", rs.getString("algorithm"));
            row.put("approvedBy", rs.getString("approved_by"));
            row.put("shadowMode", true);
            row.put("affectsDecision", false);
            return row;
        };
        if (modelId == null) {
            return jdbc.query("""
                    select id, algorithm, feature_set, status, approved_by, created_at from ml_model
                    where organization_id = ? and status = 'APPROVED' order by created_at desc limit 30
                    """, mapper, organizationId);
        }
        return jdbc.query("""
                select id, algorithm, feature_set, status, approved_by, created_at from ml_model
                where organization_id = ? and id = ? and status = 'APPROVED'
                """, mapper, organizationId, modelId);
    }

    private static boolean aligned(String prediction, String engine) {
        if (prediction == null || engine == null) return false;
        if ("FRAUD".equals(prediction)) return List.of("BLOCK", "INTERVENE", "REQUEST_CONTEXT").contains(engine);
        if ("LEGIT".equals(prediction)) return List.of("APPROVE", "APPROVE_WITH_ALERT").contains(engine);
        return false;
    }

    private Object read(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}