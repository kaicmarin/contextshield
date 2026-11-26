package com.contextshield.core.service;

import com.contextshield.core.web.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Leitura do histórico de ML (sombra). Não altera decisões nem modelos. */
@Service
public class MlActivityService {

    static final int MAX_PAGE = 50;
    static final int DEFAULT_PAGE = 25;
    static final String CALIBRATION_NOTE = "A probabilidade exibida é a saída do classificador. Não representa risco calibrado "
            + "de fraude real, salvo validação explícita de calibração.";
    static final String ALIGN_NOTE = "Concordância com o motor indica alinhamento de direção, não acerto da previsão.";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MlClient remote;

    public MlActivityService(JdbcTemplate jdbc, ObjectMapper mapper, MlClient remote) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.remote = remote;
    }

    public record ActivityQuery(
            int page,
            int size,
            String cursor,
            Long modelId,
            String algorithm,
            String eventType,
            String prediction,
            String align,
            String stage,
            String status,
            String q,
            LocalDateTime from,
            LocalDateTime to,
            boolean liveTail
    ) {
        public int safeSize() {
            return Math.max(1, Math.min(size <= 0 ? DEFAULT_PAGE : size, MAX_PAGE));
        }

        public int safePage() {
            return Math.max(0, page);
        }
    }

    public Map<String, Object> summary(long organizationId, int days) {
        int span = Math.max(1, Math.min(days, 90));
        LocalDateTime since = LocalDateTime.now().minusDays(span).withNano(0);
        Map<String, Object> out = new LinkedHashMap<>();
        if (remote.enabled()) {
            try {
                out.putAll(remote.activitySummary(organizationId, span));
            } catch (Exception e) {
                out.putAll(localShadowSummary(organizationId, since));
            }
        } else {
            out.putAll(localShadowSummary(organizationId, since));
        }
        out.put("recentStudies", jdbc.queryForObject(
                "select count(*) from ml_study where organization_id = ? and created_at >= ?", Integer.class, organizationId, Timestamp.valueOf(since)));
        out.put("models", listModelsBrief(organizationId));
        out.put("shadowMode", true);
        out.put("affectsDecision", false);
        out.put("calibrationNote", CALIBRATION_NOTE);
        out.put("alignNote", ALIGN_NOTE);
        if (!out.containsKey("charts")) {
            out.put("charts", localCharts(organizationId, since));
        }
        return out;
    }

    public Map<String, Object> activity(long organizationId, ActivityQuery query) {
        int take = query.safeSize();
        List<Map<String, Object>> events = new ArrayList<>();
        if (!remote.enabled() || includeCoreEvents(query.eventType())) {
            events.addAll(coreEvents(organizationId, query));
        }
        if (remote.enabled() && includeRemoteEvents(query.eventType())) {
            try {
                for (Map<String, Object> e : remote.activityFeed(organizationId, query)) {
                    normalizeEventTimestamps(e);
                    events.add(e);
                }
            } catch (Exception ignored) {
                /* mantém eventos da Core quando o ML Service falhar */
            }
        }
        events.sort(Comparator.comparing(MlActivityService::eventAt).reversed()
                .thenComparing(e -> String.valueOf(e.get("key"))));
        events = dedupe(events);
        int from = query.cursor != null && !query.cursor.isBlank() ? indexAfterCursor(events, query.cursor) : query.safePage() * take;
        int to = Math.min(events.size(), from + take);
        List<Map<String, Object>> page = from >= events.size() ? List.of() : events.subList(from, to);
        String nextCursor = page.isEmpty() ? null : String.valueOf(page.get(page.size() - 1).get("key"));
        return Map.of(
                "items", page,
                "totalApprox", events.size(),
                "page", query.safePage(),
                "size", take,
                "hasMore", to < events.size(),
                "nextCursor", nextCursor == null ? "" : nextCursor,
                "shadowMode", true,
                "calibrationNote", CALIBRATION_NOTE,
                "alignNote", ALIGN_NOTE);
    }

    public Map<String, Object> modelActivity(long organizationId, long modelId) {
        requireModel(organizationId, modelId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", modelRow(organizationId, modelId));
        if (remote.enabled()) {
            out.putAll(remote.modelActivity(organizationId, modelId));
        } else {
            out.putAll(localModelStats(organizationId, modelId));
        }
        out.put("experiments", jdbc.query("""
                        select e.id, e.status, e.example_count, e.seed, e.created_at, e.finished_at, e.error
                        from ml_experiment e
                        join ml_model m on m.experiment_id = e.id
                        where m.id = ? and coalesce(e.organization_id, 1) = ?
                        order by e.id desc limit 12
                        """, (rs, n) -> Map.of(
                        "id", rs.getLong("id"),
                        "status", rs.getString("status"),
                        "examples", rs.getObject("example_count"),
                        "seed", rs.getObject("seed"),
                        "createdAt", rs.getTimestamp("created_at").toLocalDateTime(),
                        "finishedAt", rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toLocalDateTime(),
                        "error", rs.getString("error")), modelId, organizationId));
        out.put("calibrationNote", CALIBRATION_NOTE);
        out.put("alignNote", ALIGN_NOTE);
        out.put("shadowMode", true);
        return out;
    }

    private boolean includeCoreEvents(String eventType) {
        if (eventType == null || eventType.isBlank()) return true;
        String t = eventType.toUpperCase(Locale.ROOT);
        if (t.startsWith("STUDY") || t.startsWith("AUDIT")) return true;
        return !remote.enabled();
    }

    private boolean includeRemoteEvents(String eventType) {
        if (eventType == null || eventType.isBlank()) return true;
        String t = eventType.toUpperCase(Locale.ROOT);
        return t.startsWith("PREDICTION") || t.startsWith("SHADOW") || t.startsWith("MODEL") || t.startsWith("EXPERIMENT");
    }

    private List<Map<String, Object>> coreEvents(long organizationId, ActivityQuery q) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(studyEvents(organizationId, q));
        rows.addAll(auditEvents(organizationId, q));
        if (!remote.enabled()) {
            rows.addAll(shadowEvents(organizationId, q));
            rows.addAll(experimentEvents(organizationId, q));
            rows.addAll(modelApprovalEvents(organizationId, q));
        }
        return rows;
    }

    private List<Map<String, Object>> shadowEvents(long organizationId, ActivityQuery q) {
        StringBuilder sql = new StringBuilder("""
                select s.id, s.at, s.tx_id, s.model_id, s.algorithm, s.prediction, s.probability, s.engine_decision,
                       s.latency_ms, s.error, s.stage, m.validation, m.feature_set, m.status model_status
                from ml_shadow s
                left join ml_model m on m.id = s.model_id
                where s.organization_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(organizationId);
        appendShadowFilters(sql, args, q);
        sql.append(" order by s.at desc, s.id desc limit 500");
        return jdbc.query(sql.toString(), (rs, n) -> shadowRow(rs), args.toArray());
    }

    private Map<String, Object> shadowRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        boolean failure = rs.getString("error") != null;
        String prediction = rs.getString("prediction");
        String engine = rs.getString("engine_decision");
        Map<String, Object> row = new LinkedHashMap<>();
        long id = rs.getLong("id");
        row.put("key", "shadow:" + id);
        row.put("type", failure ? "SHADOW_FAILURE" : "PREDICTION");
        row.put("at", rs.getTimestamp("at").toLocalDateTime());
        row.put("title", failure ? "Falha de inferência (sombra)" : "Previsão em modo sombra");
        row.put("modelId", rs.getObject("model_id"));
        row.put("algorithm", rs.getString("algorithm"));
        row.put("featureSet", rs.getString("feature_set"));
        row.put("modelStatus", rs.getString("model_status"));
        row.put("validation", rs.getString("validation"));
        row.put("txId", rs.getString("tx_id"));
        row.put("stage", rs.getString("stage"));
        row.put("prediction", prediction);
        row.put("probability", rs.getObject("probability"));
        row.put("probabilityNote", failure ? null : CALIBRATION_NOTE);
        row.put("engineDecision", engine);
        row.put("alignedWithEngine", failure ? null : aligned(prediction, engine));
        row.put("alignNote", ALIGN_NOTE);
        row.put("latencyMs", rs.getObject("latency_ms"));
        row.put("status", failure ? "FAILED" : "OK");
        row.put("error", rs.getString("error"));
        return row;
    }

    private void appendShadowFilters(StringBuilder sql, List<Object> args, ActivityQuery q) {
        if (q.modelId() != null) {
            sql.append(" and s.model_id = ?");
            args.add(q.modelId());
        }
        if (q.algorithm() != null && !q.algorithm().isBlank()) {
            sql.append(" and lower(s.algorithm) = ?");
            args.add(q.algorithm().toLowerCase(Locale.ROOT));
        }
        if (q.prediction() != null && !q.prediction().isBlank()) {
            sql.append(" and s.prediction = ?");
            args.add(q.prediction().toUpperCase(Locale.ROOT));
        }
        if (q.stage() != null && !q.stage().isBlank()) {
            sql.append(" and coalesce(s.stage, 'initial') = ?");
            args.add(q.stage());
        }
        if (q.status() != null && !q.status().isBlank()) {
            if ("FAILED".equalsIgnoreCase(q.status())) sql.append(" and s.error is not null");
            else if ("OK".equalsIgnoreCase(q.status())) sql.append(" and s.error is null");
        }
        if (q.from() != null) {
            sql.append(" and s.at >= ?");
            args.add(Timestamp.valueOf(q.from()));
        }
        if (q.to() != null) {
            sql.append(" and s.at <= ?");
            args.add(Timestamp.valueOf(q.to()));
        }
        if (q.q() != null && !q.q().isBlank()) {
            sql.append(" and s.tx_id like ?");
            args.add("%" + q.q().trim() + "%");
        }
        if (q.align() != null && !q.align().isBlank()) {
            String align = q.align().toLowerCase(Locale.ROOT);
            if ("agree".equals(align)) {
                sql.append(" and s.error is null and (");
                sql.append(alignPredicate(true));
                sql.append(")");
            } else if ("disagree".equals(align)) {
                sql.append(" and s.error is null and not (");
                sql.append(alignPredicate(true));
                sql.append(")");
            }
        }
    }

    private static String alignPredicate(boolean agree) {
        return """
                (s.prediction = 'FRAUD' and s.engine_decision in ('BLOCK', 'INTERVENE', 'REQUEST_CONTEXT'))
                or (s.prediction = 'LEGIT' and s.engine_decision in ('APPROVE', 'APPROVE_WITH_ALERT'))
                """;
    }

    private static boolean aligned(String prediction, String engine) {
        if (prediction == null || engine == null) return false;
        if ("FRAUD".equals(prediction)) return List.of("BLOCK", "INTERVENE", "REQUEST_CONTEXT").contains(engine);
        if ("LEGIT".equals(prediction)) return List.of("APPROVE", "APPROVE_WITH_ALERT").contains(engine);
        return false;
    }

    private List<Map<String, Object>> experimentEvents(long organizationId, ActivityQuery q) {
        if (q.eventType() != null && !q.eventType().isBlank()
                && !q.eventType().toUpperCase(Locale.ROOT).startsWith("EXPERIMENT")) return List.of();
        StringBuilder sql = new StringBuilder("""
                select id, status, kind, seed, example_count, requested_by, created_at, finished_at, error, report_json
                from ml_experiment where coalesce(organization_id, 1) = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(organizationId);
        if (q.q() != null && !q.q().isBlank()) {
            sql.append(" and cast(id as varchar) like ?");
            args.add("%" + q.q().trim() + "%");
        }
        if (q.from() != null) {
            sql.append(" and created_at >= ?");
            args.add(Timestamp.valueOf(q.from()));
        }
        if (q.to() != null) {
            sql.append(" and created_at <= ?");
            args.add(Timestamp.valueOf(q.to()));
        }
        sql.append(" order by id desc limit 80");
        return jdbc.query(sql.toString(), rs -> {
            List<Map<String, Object>> out = new ArrayList<>();
            while (rs.next()) {
                long id = rs.getLong("id");
                LocalDateTime created = rs.getTimestamp("created_at").toLocalDateTime();
                out.add(experimentRow("EXPERIMENT_QUEUED", id, created, rs, "Experimento enfileirado"));
                Timestamp fin = rs.getTimestamp("finished_at");
                if (fin != null) {
                    String st = rs.getString("status");
                    String type = "FAILED".equals(st) ? "EXPERIMENT_FAILED" : "EXPERIMENT_DONE";
                    String title = "FAILED".equals(st) ? "Experimento falhou" : "Modelo treinado (experimento)";
                    out.add(experimentRow(type, id, fin.toLocalDateTime(), rs, title));
                }
            }
            return out;
        }, args.toArray());
    }

    private Map<String, Object> experimentRow(String type, long id, LocalDateTime at, java.sql.ResultSet rs, String title) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", type.toLowerCase(Locale.ROOT) + ":exp:" + id + ":" + at.truncatedTo(ChronoUnit.SECONDS));
        row.put("type", type);
        row.put("at", at);
        row.put("title", title);
        row.put("experimentId", id);
        row.put("status", rs.getString("status"));
        row.put("examples", rs.getObject("example_count"));
        row.put("seed", rs.getObject("seed"));
        row.put("requestedBy", rs.getString("requested_by"));
        row.put("dataOrigin", "sintetico");
        row.put("error", rs.getString("error"));
        row.put("report", readJson(rs.getString("report_json")));
        row.put("studyId", null);
        row.put("reportHref", "/api/ops/ml/experiments/" + id);
        return row;
    }

    private List<Map<String, Object>> modelApprovalEvents(long organizationId, ActivityQuery q) {
        if (q.eventType() != null && !q.eventType().isBlank()
                && !"MODEL_APPROVED".equalsIgnoreCase(q.eventType())) return List.of();
        String sql = """
                select id, algorithm, feature_set, status, approved_by, approved_at, validation, data_origin, experiment_id
                from ml_model where coalesce(organization_id, 1) = ? and approved_at is not null
                order by approved_at desc limit 40
                """;
        if (q.modelId() != null) {
            sql = """
                    select id, algorithm, feature_set, status, approved_by, approved_at, validation, data_origin, experiment_id
                    from ml_model where id = ? and coalesce(organization_id, 1) = ? and approved_at is not null
                    """;
            return jdbc.query(sql, this::approvalRow, q.modelId(), organizationId);
        }
        return jdbc.query(sql, this::approvalRow, organizationId);
    }

    private Map<String, Object> approvalRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        long id = rs.getLong("id");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", "model-approved:" + id);
        row.put("type", "MODEL_APPROVED");
        row.put("at", rs.getTimestamp("approved_at").toLocalDateTime());
        row.put("title", "Modelo aprovado para modo sombra");
        row.put("modelId", id);
        row.put("algorithm", rs.getString("algorithm"));
        row.put("featureSet", rs.getString("feature_set"));
        row.put("status", rs.getString("status"));
        row.put("approvedBy", rs.getString("approved_by"));
        row.put("dataOrigin", rs.getString("data_origin"));
        row.put("validation", rs.getString("validation"));
        row.put("experimentId", rs.getObject("experiment_id"));
        row.put("shadowMode", true);
        row.put("affectsDecision", false);
        return row;
    }

    private List<Map<String, Object>> studyEvents(long organizationId, ActivityQuery q) {
        if (q.eventType() != null && !q.eventType().isBlank()
                && !q.eventType().toUpperCase(Locale.ROOT).startsWith("STUDY")) return List.of();
        StringBuilder sql = new StringBuilder("""
                select id, kind, status, cost_profile, created_by, created_at, finished_at, error, note, report_json
                from ml_study where organization_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(organizationId);
        if (q.q() != null && !q.q().isBlank()) {
            sql.append(" and cast(id as varchar) like ?");
            args.add("%" + q.q().trim() + "%");
        }
        sql.append(" order by coalesce(finished_at, created_at) desc limit 60");
        return jdbc.query(sql.toString(), (rs, n) -> {
            LocalDateTime at = rs.getTimestamp("finished_at") != null
                    ? rs.getTimestamp("finished_at").toLocalDateTime()
                    : rs.getTimestamp("created_at").toLocalDateTime();
            Map<String, Object> row = new LinkedHashMap<>();
            long id = rs.getLong("id");
            String status = rs.getString("status");
            row.put("key", "study:" + id + ":" + status);
            row.put("type", "STUDY_" + status);
            row.put("at", at);
            row.put("title", "Estudo de política · " + rs.getString("kind"));
            row.put("studyId", id);
            row.put("kind", rs.getString("kind"));
            row.put("status", status);
            row.put("costProfile", rs.getString("cost_profile"));
            row.put("createdBy", rs.getString("created_by"));
            row.put("note", rs.getString("note"));
            row.put("error", rs.getString("error"));
            row.put("report", readJson(rs.getString("report_json")));
            row.put("reportHref", "/api/ops/ml/studies/" + id);
            row.put("affectsDecision", false);
            return row;
        }, args.toArray());
    }

    private List<Map<String, Object>> auditEvents(long organizationId, ActivityQuery q) {
        if (q.eventType() != null && !q.eventType().isBlank()
                && !q.eventType().toUpperCase(Locale.ROOT).startsWith("AUDIT")) return List.of();
        return jdbc.query("""
                        select at, actor, action, target, detail
                        from audit_entry
                        where organization_id = ? and action like 'ML_%'
                        order by at desc limit 40
                        """, (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", "audit:" + rs.getTimestamp("at").getTime() + ":" + rs.getString("action") + ":" + rs.getString("target"));
                    row.put("type", "AUDIT_" + rs.getString("action"));
                    row.put("at", rs.getTimestamp("at").toLocalDateTime());
                    row.put("title", auditTitle(rs.getString("action")));
                    row.put("actor", rs.getString("actor"));
                    row.put("action", rs.getString("action"));
                    row.put("target", rs.getString("target"));
                    row.put("detail", rs.getString("detail"));
                    row.put("affectsDecision", false);
                    return row;
                }, organizationId);
    }

    private static String auditTitle(String action) {
        return switch (action) {
            case "ML_APPROVED" -> "Auditoria · modelo aprovado (sombra)";
            case "ML_EXPERIMENT" -> "Auditoria · experimento solicitado";
            case "ML_WEIGHT_STUDY", "ML_RULE_STUDY" -> "Auditoria · estudo enfileirado";
            case "ML_REJECTED" -> "Auditoria · modelo rejeitado";
            default -> "Auditoria · " + action;
        };
    }

    private Map<String, Object> localShadowSummary(long organizationId, LocalDateTime since) {
        Map<String, Object> cards = new LinkedHashMap<>();
        Integer total = jdbc.queryForObject(
                "select count(*) from ml_shadow where organization_id = ? and error is null", Integer.class, organizationId);
        Integer last24h = jdbc.queryForObject(
                "select count(*) from ml_shadow where organization_id = ? and error is null and at >= ?",
                Integer.class, organizationId, Timestamp.valueOf(LocalDateTime.now().minusHours(24)));
        Integer failures = jdbc.queryForObject(
                "select count(*) from ml_shadow where organization_id = ? and error is not null", Integer.class, organizationId);
        Integer active = jdbc.queryForObject("""
                select count(distinct model_id) from ml_shadow s
                join ml_model m on m.id = s.model_id
                where s.organization_id = ? and m.status = 'APPROVED' and s.error is null
                """, Integer.class, organizationId);
        Integer agree = jdbc.queryForObject("""
                select count(*) from ml_shadow where organization_id = ? and error is null and (
                (prediction = 'FRAUD' and engine_decision in ('BLOCK', 'INTERVENE', 'REQUEST_CONTEXT'))
                or (prediction = 'LEGIT' and engine_decision in ('APPROVE', 'APPROVE_WITH_ALERT')))
                """, Integer.class, organizationId);
        Integer totalOk = total == null ? 0 : total;
        Integer disagree = totalOk - (agree == null ? 0 : agree);
        Double latency = jdbc.queryForObject(
                "select avg(latency_ms) from ml_shadow where organization_id = ? and error is null and latency_ms > 0",
                Double.class, organizationId);
        Integer recentExp = jdbc.queryForObject(
                "select count(*) from ml_experiment where coalesce(organization_id, 1) = ? and created_at >= ?",
                Integer.class, organizationId, Timestamp.valueOf(since));
        cards.put("predictions", totalOk);
        cards.put("predictions24h", last24h == null ? 0 : last24h);
        cards.put("activeShadowModels", active == null ? 0 : active);
        cards.put("agreements", agree == null ? 0 : agree);
        cards.put("disagreements", Math.max(0, disagree));
        cards.put("failures", failures == null ? 0 : failures);
        cards.put("meanLatencyMs", latency == null ? null : Math.round(latency * 1000.0) / 1000.0);
        cards.put("recentExperiments", recentExp == null ? 0 : recentExp);
        return Map.of("cards", cards);
    }

    private Map<String, Object> localCharts(long organizationId, LocalDateTime since) {
        Map<String, Object> charts = new LinkedHashMap<>();
        charts.put("predictionsByDay", jdbc.query("""
                        select cast(at as date) d, count(*) total,
                               sum(case when prediction = 'FRAUD' then 1 else 0 end) fraud,
                               sum(case when prediction = 'LEGIT' then 1 else 0 end) legit
                        from ml_shadow where organization_id = ? and error is null and at >= ?
                        group by cast(at as date) order by d
                        """, (rs, n) -> Map.of(
                        "d", rs.getDate("d").toLocalDate().toString(),
                        "total", rs.getInt("total"),
                        "fraud", rs.getInt("fraud"),
                        "legit", rs.getInt("legit")),
                organizationId, Timestamp.valueOf(since)));
        charts.put("alignmentByDay", jdbc.query("""
                        select cast(at as date) d,
                               sum(case when (prediction = 'FRAUD' and engine_decision in ('BLOCK','INTERVENE','REQUEST_CONTEXT'))
                                     or (prediction = 'LEGIT' and engine_decision in ('APPROVE','APPROVE_WITH_ALERT')) then 1 else 0 end) agree,
                               sum(case when not ((prediction = 'FRAUD' and engine_decision in ('BLOCK','INTERVENE','REQUEST_CONTEXT'))
                                     or (prediction = 'LEGIT' and engine_decision in ('APPROVE','APPROVE_WITH_ALERT'))) then 1 else 0 end) disagree
                        from ml_shadow where organization_id = ? and error is null and at >= ?
                        group by cast(at as date) order by d
                        """, (rs, n) -> Map.of(
                        "d", rs.getDate("d").toLocalDate().toString(),
                        "agree", rs.getInt("agree"),
                        "disagree", rs.getInt("disagree")),
                organizationId, Timestamp.valueOf(since)));
        charts.put("latencyByModel", jdbc.query("""
                        select model_id, algorithm, round(cast(avg(latency_ms) as numeric), 2) avg_ms, count(*) total
                        from ml_shadow where organization_id = ? and error is null and latency_ms > 0 and at >= ?
                        group by model_id, algorithm order by total desc limit 8
                        """, (rs, n) -> Map.of(
                        "modelId", rs.getObject("model_id"),
                        "algorithm", rs.getString("algorithm"),
                        "avgMs", rs.getDouble("avg_ms"),
                        "total", rs.getInt("total")),
                organizationId, Timestamp.valueOf(since)));
        charts.put("predictionMix", jdbc.query("""
                        select coalesce(prediction, 'UNKNOWN') label, count(*) total
                        from ml_shadow where organization_id = ? and error is null and at >= ?
                        group by prediction order by total desc
                        """, (rs, n) -> Map.of("label", rs.getString("label"), "total", rs.getInt("total")),
                organizationId, Timestamp.valueOf(since)));
        return charts;
    }

    private Map<String, Object> localModelStats(long organizationId, long modelId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        Integer total = jdbc.queryForObject(
                "select count(*) from ml_shadow where organization_id = ? and model_id = ? and error is null",
                Integer.class, organizationId, modelId);
        Integer agree = jdbc.queryForObject("""
                select count(*) from ml_shadow where organization_id = ? and model_id = ? and error is null and (
                (prediction = 'FRAUD' and engine_decision in ('BLOCK', 'INTERVENE', 'REQUEST_CONTEXT'))
                or (prediction = 'LEGIT' and engine_decision in ('APPROVE', 'APPROVE_WITH_ALERT')))
                """, Integer.class, organizationId, modelId);
        Double latency = jdbc.queryForObject(
                "select avg(latency_ms) from ml_shadow where organization_id = ? and model_id = ? and error is null and latency_ms > 0",
                Double.class, organizationId, modelId);
        var last = jdbc.query("""
                        select at, tx_id, prediction, engine_decision, latency_ms
                        from ml_shadow where organization_id = ? and model_id = ? order by at desc limit 1
                        """, rs -> rs.next() ? Map.of(
                        "at", rs.getTimestamp("at").toLocalDateTime(),
                        "txId", rs.getString("tx_id"),
                        "prediction", rs.getString("prediction"),
                        "engineDecision", rs.getString("engine_decision"),
                        "latencyMs", rs.getObject("latency_ms")) : null,
                organizationId, modelId);
        int t = total == null ? 0 : total;
        int a = agree == null ? 0 : agree;
        stats.put("predictionTotal", t);
        stats.put("agreementRate", t == 0 ? null : Math.round(a * 1000.0 / t) / 10.0);
        stats.put("meanLatencyMs", latency == null ? null : Math.round(latency * 1000.0) / 1000.0);
        stats.put("lastPrediction", last);
        stats.put("recentActivity", shadowEvents(organizationId,
                new ActivityQuery(0, 12, null, modelId, null, null, null, null, null, null, null, null, null, false)));
        return stats;
    }

    private List<Map<String, Object>> listModelsBrief(long organizationId) {
        return jdbc.query("""
                        select id, algorithm, status, validation, feature_set, data_origin, created_at, approved_at
                        from ml_model where coalesce(organization_id, 1) = ? order by id desc limit 24
                        """, (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("algorithm", rs.getString("algorithm"));
                    m.put("status", rs.getString("status"));
                    m.put("validation", rs.getString("validation"));
                    m.put("featureSet", rs.getString("feature_set"));
                    m.put("dataOrigin", rs.getString("data_origin"));
                    m.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
                    m.put("approvedAt", rs.getTimestamp("approved_at") == null ? null : rs.getTimestamp("approved_at").toLocalDateTime());
                    return m;
                }, organizationId);
    }

    private Map<String, Object> modelRow(long organizationId, long modelId) {
        List<Map<String, Object>> rows = jdbc.query("""
                        select id, algorithm, status, validation, feature_set, data_origin, metrics_json, experiment_id,
                               example_count, created_at, approved_at, approved_by, decision_authorized
                        from ml_model where id = ? and coalesce(organization_id, 1) = ?
                        """, (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("algorithm", rs.getString("algorithm"));
                    m.put("status", rs.getString("status"));
                    m.put("validation", rs.getString("validation"));
                    m.put("featureSet", rs.getString("feature_set"));
                    m.put("dataOrigin", rs.getString("data_origin"));
                    m.put("experimentId", rs.getObject("experiment_id"));
                    m.put("examples", rs.getObject("example_count"));
                    m.put("metrics", readJson(rs.getString("metrics_json")));
                    m.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
                    m.put("approvedAt", rs.getTimestamp("approved_at") == null ? null : rs.getTimestamp("approved_at").toLocalDateTime());
                    m.put("approvedBy", rs.getString("approved_by"));
                    m.put("decisionAuthorized", rs.getBoolean("decision_authorized"));
                    m.put("usage", "APPROVED".equals(rs.getString("status")) ? "shadow" : "stored");
                    return m;
                }, modelId, organizationId);
        if (rows.isEmpty()) throw ApiException.notFound("Modelo");
        return rows.get(0);
    }

    private void requireModel(long organizationId, long modelId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from ml_model where id = ? and coalesce(organization_id, 1) = ?", Integer.class, modelId, organizationId);
        if (n == null || n == 0) throw ApiException.notFound("Modelo");
    }

    private static List<Map<String, Object>> dedupe(List<Map<String, Object>> events) {
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (Map<String, Object> e : events) {
            String key = String.valueOf(e.get("key"));
            if (seen.add(key)) out.add(e);
        }
        return out;
    }

    private static int indexAfterCursor(List<Map<String, Object>> events, String cursor) {
        for (int i = 0; i < events.size(); i++) {
            if (cursor.equals(String.valueOf(events.get(i).get("key")))) return i + 1;
        }
        return 0;
    }

    private static void normalizeEventTimestamps(Map<String, Object> e) {
        Object at = e.get("at");
        if (at instanceof String s && !s.isBlank()) {
            e.put("at", parseEventAt(s));
        }
    }

    private static LocalDateTime eventAt(Map<String, Object> e) {
        Object at = e.get("at");
        if (at instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (at instanceof String s && !s.isBlank()) {
            return parseEventAt(s);
        }
        return LocalDateTime.MIN;
    }

    private static LocalDateTime parseEventAt(String s) {
        try {
            String core = s.length() >= 19 ? s.substring(0, 19) : s;
            return LocalDateTime.parse(core);
        } catch (Exception e) {
            return LocalDateTime.MIN;
        }
    }

    private Object readJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}