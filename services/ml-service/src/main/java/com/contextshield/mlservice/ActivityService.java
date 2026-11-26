package com.contextshield.mlservice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ActivityService {

    static final int MAX_FETCH = 400;
    static final String CALIBRATION_NOTE = "A probabilidade exibida é a saída do classificador. Não representa risco calibrado de fraude real.";
    static final String ALIGN_NOTE = "Concordância com o motor indica alinhamento de direção, não acerto da previsão.";

    private static final Comparator<Map<String, Object>> EVENT_ORDER = Comparator
            .comparing((Map<String, Object> e) -> (LocalDateTime) e.get("at")).reversed()
            .thenComparing(e -> String.valueOf(e.get("key")), Comparator.reverseOrder());

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

    /** Lista de eventos para merge na Core API (sem paginação aqui). */
    public List<Map<String, Object>> eventsForMerge(long organizationId, int limit, String cursor,
                                                    Long modelId, String algorithm, String eventType,
                                                    String prediction, String align, String stage, String status,
                                                    String q, LocalDateTime from, LocalDateTime to) {
        int take = Math.max(1, Math.min(limit, MAX_FETCH));
        ShadowCursor sc = ShadowCursor.parse(cursor);
        List<Map<String, Object>> events = new ArrayList<>();
        if (includeShadowEvents(eventType)) {
            events.addAll(shadowEvents(organizationId, modelId, algorithm, prediction, align, stage, status, q, from, to, eventType, sc, take));
        }
        if (includeExperimentEvents(eventType)) {
            events.addAll(experimentEvents(organizationId, q, from, to, sc));
        }
        if (includeApprovalEvents(eventType)) {
            events.addAll(approvalEvents(organizationId, modelId, sc));
        }
        events.sort(EVENT_ORDER);
        events = filterOlder(events, sc, take);
        return events;
    }

    public Map<String, Object> feed(long organizationId, int page, int size, String cursor, Long modelId, String algorithm,
                                    String prediction, String align, String stage, String status, String q) {
        int take = Math.max(1, Math.min(size <= 0 ? 25 : size, 50));
        List<Map<String, Object>> events = eventsForMerge(organizationId, take + 50, cursor, modelId, algorithm, null,
                prediction, align, stage, status, q, null, null);
        int from = page * take;
        int to = Math.min(events.size(), from + take);
        List<Map<String, Object>> slice = from >= events.size() ? List.of() : events.subList(from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", slice);
        out.put("hasMore", to < events.size());
        out.put("shadowMode", true);
        return out;
    }

    public Map<String, Object> modelStats(long organizationId, long modelId) {
        Map<String, Object> model = findModel(organizationId, modelId);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("model", model);
        stats.put("catalogSource", "ML_SERVICE");
        Integer total = jdbc.queryForObject(
                "select count(*) from ml_shadow where organization_id = ? and model_id = ? and error is null", Integer.class, organizationId, modelId);
        Integer agree = jdbc.queryForObject("""
                select count(*) from ml_shadow where organization_id = ? and model_id = ? and error is null and (
                (prediction = 'FRAUD' and engine_decision in ('BLOCK', 'INTERVENE', 'REQUEST_CONTEXT'))
                or (prediction = 'LEGIT' and engine_decision in ('APPROVE', 'APPROVE_WITH_ALERT')))
                """, Integer.class, organizationId, modelId);
        Double latency = jdbc.queryForObject(
                "select avg(latency_ms) from ml_shadow where organization_id = ? and model_id = ? and error is null and latency_ms > 0",
                Double.class, organizationId, modelId);
        int t = total == null ? 0 : total;
        int a = agree == null ? 0 : agree;
        stats.put("predictionTotal", t);
        stats.put("agreementRate", t == 0 ? null : Math.round(a * 1000.0 / t) / 10.0);
        stats.put("meanLatencyMs", latency == null ? null : Math.round(latency * 1000.0) / 1000.0);
        stats.put("recentActivity", shadowEvents(organizationId, modelId, null, null, null, null, null, null, null, null, null, null, 12));
        stats.put("alignNote", ALIGN_NOTE);
        stats.put("calibrationNote", CALIBRATION_NOTE);
        return stats;
    }

    private Map<String, Object> findModel(long organizationId, long modelId) {
        List<Map<String, Object>> rows = jdbc.query("""
                        select id, algorithm, feature_set, status, validation, metrics_json, created_at, approved_by
                        from ml_model where id = ? and organization_id = ?
                        """, (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("algorithm", rs.getString("algorithm"));
                    row.put("featureSet", rs.getString("feature_set"));
                    row.put("status", rs.getString("status"));
                    row.put("validation", rs.getString("validation"));
                    row.put("metrics", read(rs.getString("metrics_json")));
                    row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
                    row.put("approvedBy", rs.getString("approved_by"));
                    row.put("usage", "APPROVED".equals(rs.getString("status")) ? "shadow" : "stored");
                    return row;
                }, modelId, organizationId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Modelo não encontrado.");
        return rows.get(0);
    }

    private static boolean includeShadowEvents(String eventType) {
        if (eventType == null || eventType.isBlank()) return true;
        String t = eventType.toUpperCase(Locale.ROOT);
        return "PREDICTION".equals(t) || "SHADOW_FAILURE".equals(t) || t.startsWith("SHADOW");
    }

    private static boolean includeExperimentEvents(String eventType) {
        if (eventType == null || eventType.isBlank()) return true;
        return eventType.toUpperCase(Locale.ROOT).startsWith("EXPERIMENT");
    }

    private static boolean includeApprovalEvents(String eventType) {
        if (eventType == null || eventType.isBlank()) return true;
        return "MODEL_APPROVED".equalsIgnoreCase(eventType);
    }

    private static List<Map<String, Object>> filterOlder(List<Map<String, Object>> events, ShadowCursor cursor, int cap) {
        if (cursor == null) return events.size() <= cap ? events : events.subList(0, cap);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : events) {
            if (!cursor.isOlderThan(e)) continue;
            out.add(e);
            if (out.size() >= cap) break;
        }
        return out;
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
                                                   String align, String stage, String status, String q,
                                                   LocalDateTime from, LocalDateTime to, String eventType,
                                                   ShadowCursor cursor, int fetchLimit) {
        StringBuilder sql = new StringBuilder("""
                select s.id, s.at, s.tx_id, s.model_id, s.algorithm, s.prediction, s.probability, s.engine_decision,
                       s.latency_ms, s.error, s.stage, m.validation, m.feature_set, m.status model_status
                from ml_shadow s left join ml_model m on m.id = s.model_id where s.organization_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(organizationId);
        if (eventType != null && !eventType.isBlank()) {
            if ("PREDICTION".equalsIgnoreCase(eventType)) sql.append(" and s.error is null");
            else if ("SHADOW_FAILURE".equalsIgnoreCase(eventType)) sql.append(" and s.error is not null");
        }
        if (modelId != null) { sql.append(" and s.model_id = ?"); args.add(modelId); }
        if (algorithm != null && !algorithm.isBlank()) { sql.append(" and lower(s.algorithm) = ?"); args.add(algorithm.toLowerCase(Locale.ROOT)); }
        if (prediction != null && !prediction.isBlank()) { sql.append(" and s.prediction = ?"); args.add(prediction.toUpperCase(Locale.ROOT)); }
        if (stage != null && !stage.isBlank()) { sql.append(" and coalesce(s.stage, 'initial') = ?"); args.add(stage); }
        if (status != null && "FAILED".equalsIgnoreCase(status)) sql.append(" and s.error is not null");
        else if (status != null && "OK".equalsIgnoreCase(status)) sql.append(" and s.error is null");
        if (from != null) { sql.append(" and s.at >= ?"); args.add(Timestamp.valueOf(from)); }
        if (to != null) { sql.append(" and s.at <= ?"); args.add(Timestamp.valueOf(to)); }
        if (q != null && !q.isBlank()) { sql.append(" and (s.tx_id like ? or cast(s.id as varchar) like ?)"); args.add("%" + q.trim() + "%"); args.add("%" + q.trim() + "%"); }
        if (align != null && "agree".equalsIgnoreCase(align)) {
            sql.append(" and s.error is null and ((s.prediction = 'FRAUD' and s.engine_decision in ('BLOCK','INTERVENE','REQUEST_CONTEXT')) or (s.prediction = 'LEGIT' and s.engine_decision in ('APPROVE','APPROVE_WITH_ALERT')))");
        } else if (align != null && "disagree".equalsIgnoreCase(align)) {
            sql.append(" and s.error is null and not ((s.prediction = 'FRAUD' and s.engine_decision in ('BLOCK','INTERVENE','REQUEST_CONTEXT')) or (s.prediction = 'LEGIT' and s.engine_decision in ('APPROVE','APPROVE_WITH_ALERT')))");
        }
        if (cursor != null) cursor.appendKeyset(sql, args);
        sql.append(" order by s.at desc, s.id desc limit ?");
        args.add(fetchLimit);
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

    private List<Map<String, Object>> experimentEvents(long organizationId, String q, LocalDateTime from, LocalDateTime to, ShadowCursor cursor) {
        StringBuilder sql = new StringBuilder("select id, status, seed, example_count, requested_by, created_at, finished_at, error, report_json from ml_experiment where organization_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(organizationId);
        if (q != null && !q.isBlank()) { sql.append(" and cast(id as varchar) like ?"); args.add("%" + q.trim() + "%"); }
        if (from != null) { sql.append(" and created_at >= ?"); args.add(Timestamp.valueOf(from)); }
        if (to != null) { sql.append(" and created_at <= ?"); args.add(Timestamp.valueOf(to)); }
        if (cursor != null && cursor.at != null) { sql.append(" and created_at < ?"); args.add(Timestamp.valueOf(cursor.at)); }
        sql.append(" order by id desc limit 60");
        return jdbc.query(sql.toString(), rs -> {
            List<Map<String, Object>> out = new ArrayList<>();
            while (rs.next()) {
                long id = rs.getLong("id");
                LocalDateTime created = rs.getTimestamp("created_at").toLocalDateTime();
                out.add(expEvent(id, "EXPERIMENT_QUEUED", created, rs, "Experimento enfileirado"));
                Timestamp fin = rs.getTimestamp("finished_at");
                if (fin != null) {
                    String st = rs.getString("status");
                    out.add(expEvent(id, "FAILED".equals(st) ? "EXPERIMENT_FAILED" : "EXPERIMENT_DONE", fin.toLocalDateTime(), rs,
                            "FAILED".equals(st) ? "Experimento falhou" : "Modelo treinado (experimento)"));
                }
            }
            return out;
        }, args.toArray());
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

    private List<Map<String, Object>> approvalEvents(long organizationId, Long modelId, ShadowCursor cursor) {
        var rowMapper = (org.springframework.jdbc.core.RowMapper<Map<String, Object>>) (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            long id = rs.getLong("id");
            row.put("key", "model-approved:" + id);
            row.put("type", "MODEL_APPROVED");
            Timestamp when = rs.getTimestamp("approved_at");
            if (when == null) when = rs.getTimestamp("created_at");
            row.put("at", when.toLocalDateTime());
            row.put("title", "Modelo aprovado para modo sombra");
            row.put("modelId", id);
            row.put("algorithm", rs.getString("algorithm"));
            row.put("approvedBy", rs.getString("approved_by"));
            row.put("shadowMode", true);
            row.put("affectsDecision", false);
            return row;
        };
        String base = "select id, algorithm, approved_by, approved_at, created_at from ml_model where organization_id = ? and status = 'APPROVED'";
        if (modelId == null) {
            String sql = base + (cursor != null && cursor.at != null ? " and coalesce(approved_at, created_at) < ?" : "") + " order by coalesce(approved_at, created_at) desc limit 30";
            if (cursor != null && cursor.at != null) return jdbc.query(sql, rowMapper, organizationId, Timestamp.valueOf(cursor.at));
            return jdbc.query(sql, rowMapper, organizationId);
        }
        return jdbc.query(base + " and id = ?", rowMapper, organizationId, modelId);
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

    static final class ShadowCursor {
        final LocalDateTime at;
        final String key;

        ShadowCursor(LocalDateTime at, String key) {
            this.at = at;
            this.key = key == null ? "" : key;
        }

        static ShadowCursor parse(String raw) {
            if (raw == null || raw.isBlank()) return null;
            int pipe = raw.indexOf('|');
            if (pipe < 1) {
                if (raw.startsWith("shadow:")) return new ShadowCursor(null, raw);
                return null;
            }
            try {
                String atPart = raw.substring(0, pipe).trim();
                String core = atPart.length() >= 19 ? atPart.substring(0, 19) : atPart;
                return new ShadowCursor(LocalDateTime.parse(core), raw.substring(pipe + 1).trim());
            } catch (Exception e) {
                return new ShadowCursor(null, raw.substring(pipe + 1).trim());
            }
        }

        Long shadowId() {
            if (key == null || !key.startsWith("shadow:")) return null;
            try {
                return Long.parseLong(key.substring("shadow:".length()));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        void appendKeyset(StringBuilder sql, List<Object> args) {
            if (at == null) return;
            Long id = shadowId();
            if (id != null) {
                sql.append(" and (s.at < ? or (s.at = ? and s.id < ?))");
                args.add(Timestamp.valueOf(at));
                args.add(Timestamp.valueOf(at));
                args.add(id);
            } else {
                sql.append(" and s.at < ?");
                args.add(Timestamp.valueOf(at));
            }
        }

        boolean isOlderThan(Map<String, Object> event) {
            if (at == null) return true;
            LocalDateTime eat = (LocalDateTime) event.get("at");
            String ekey = String.valueOf(event.get("key"));
            if (eat == null) return true;
            int cmp = eat.compareTo(at);
            if (cmp < 0) return true;
            if (cmp > 0) return false;
            return ekey.compareTo(key) < 0;
        }
    }
}