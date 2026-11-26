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
    /** Máximo por fonte SQL em uma página (evita carregar o histórico inteiro). */
    static final int SOURCE_FETCH_LIMIT = 400;
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
        // O catálogo principal fica no ml-service; o banco local é apenas fallback.
        Object modelCatalog = listModelsBrief(organizationId);
        if (remote.enabled()) {
            try {
                Object remoteModels = remote.overview(organizationId).get("models");
                if (remoteModels instanceof List<?> modelList && !modelList.isEmpty()) {
                    modelCatalog = modelList;
                }
            } catch (Exception ignored) {
                // Mantém o catálogo local caso o serviço de ML esteja indisponível.
            }
        }
        out.put("models", modelCatalog);
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
        ActivityCursor cursor = ActivityCursor.parse(query.cursor()).orElse(null);
        if (cursor == null && query.safePage() > 0 && remote.enabled() && (query.cursor() == null || query.cursor().isBlank())) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Use nextCursor para páginas após a primeira (paginação unificada).");
        }
        List<Map<String, Object>> events = new ArrayList<>();
        if (!remote.enabled() || includeCoreEvents(query.eventType())) {
            events.addAll(coreEvents(organizationId, query, cursor, SOURCE_FETCH_LIMIT));
        }
        if (remote.enabled() && includeRemoteEvents(query.eventType())) {
            try {
                for (Map<String, Object> e : remote.activityEvents(organizationId, query, SOURCE_FETCH_LIMIT)) {
                    normalizeEventTimestamps(e);
                    if (cursor == null || cursor.isOlderThan(e)) events.add(e);
                }
            } catch (Exception ignored) {
                /* mantém eventos da Core quando o ML Service falhar */
            }
        }
        events.sort(ACTIVITY_ORDER);
        events = dedupe(events);
        if (cursor != null) {
            events = events.stream().filter(cursor::isOlderThan).toList();
        }
        int from = cursor == null && query.safePage() > 0 && !remote.enabled() ? query.safePage() * take : 0;
        if (from > 0) {
            if (from >= events.size()) events = List.of();
            else events = events.subList(from, events.size());
        }
        boolean hasMore = events.size() > take;
        List<Map<String, Object>> page = events.size() <= take ? events : events.subList(0, take);
        String nextCursor = hasMore && !page.isEmpty() ? ActivityCursor.encode(page.get(page.size() - 1)) : "";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", page);
        out.put("totalApprox", page.size() + (hasMore ? 1 : 0));
        out.put("page", query.safePage());
        out.put("size", take);
        out.put("hasMore", hasMore);
        out.put("nextCursor", nextCursor);
        out.put("shadowMode", true);
        out.put("calibrationNote", CALIBRATION_NOTE);
        out.put("alignNote", ALIGN_NOTE);
        return out;
    }

    private static final Comparator<Map<String, Object>> ACTIVITY_ORDER = Comparator
            .comparing(MlActivityService::eventAt).reversed()
            .thenComparing(e -> String.valueOf(e.get("key")), Comparator.reverseOrder());

    public Map<String, Object> modelActivity(long organizationId, long modelId) {
        Map<String, Object> coreModel = findCoreModel(organizationId, modelId);
        Map<String, Object> remotePayload = Map.of();
        if (remote.enabled()) {
            try {
                remotePayload = remote.modelActivity(organizationId, modelId);
            } catch (Exception ignored) {
                remotePayload = Map.of();
            }
        }
        Object remoteModel = remotePayload.get("model");
        if (coreModel == null && !(remoteModel instanceof Map<?, ?>)) {
            throw ApiException.notFound("Modelo");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(remotePayload);
        if (coreModel != null) {
            out.put("model", mergeModelMetadata(coreModel, remoteModel instanceof Map<?, ?> rm ? (Map<String, Object>) rm : null));
            out.put("catalogSource", remoteModel instanceof Map<?, ?> ? "BOTH" : "CORE");
        } else {
            out.put("model", remoteModel);
            out.put("catalogSource", "ML_SERVICE");
        }
        if (!out.containsKey("predictionTotal")) {
            out.putAll(localModelStats(organizationId, modelId));
        }
        out.put("experiments", coreExperimentsForModel(organizationId, modelId));
        out.put("calibrationNote", CALIBRATION_NOTE);
        out.put("alignNote", ALIGN_NOTE);
        out.put("shadowMode", true);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeModelMetadata(Map<String, Object> core, Map<String, Object> remote) {
        if (remote == null) return core;
        Map<String, Object> merged = new LinkedHashMap<>(core);
        merged.put("metrics", pick(remote.get("metrics"), core.get("metrics")));
        merged.put("validation", pick(core.get("validation"), remote.get("validation")));
        merged.put("status", pick(core.get("status"), remote.get("status")));
        return merged;
    }

    private static Object pick(Object preferred, Object fallback) {
        return preferred != null ? preferred : fallback;
    }

    private List<Map<String, Object>> coreExperimentsForModel(long organizationId, long modelId) {
        return jdbc.query("""
                        select e.id, e.status, e.example_count, e.seed, e.created_at, e.finished_at, e.error
                        from ml_experiment e
                        join ml_model m on m.experiment_id = e.id
                        where m.id = ? and coalesce(e.organization_id, 1) = ?
                        order by e.id desc limit 12
                        """, (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("status", rs.getString("status"));
                    row.put("examples", rs.getObject("example_count"));
                    row.put("seed", rs.getObject("seed"));
                    row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
                    Timestamp fin = rs.getTimestamp("finished_at");
                    row.put("finishedAt", fin == null ? null : fin.toLocalDateTime());
                    row.put("error", rs.getString("error"));
                    return row;
                }, modelId, organizationId);
    }

    private Map<String, Object> findCoreModel(long organizationId, long modelId) {
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
                    Timestamp ap = rs.getTimestamp("approved_at");
                    m.put("approvedAt", ap == null ? null : ap.toLocalDateTime());
                    m.put("approvedBy", rs.getString("approved_by"));
                    m.put("decisionAuthorized", rs.getBoolean("decision_authorized"));
                    m.put("usage", "APPROVED".equals(rs.getString("status")) ? "shadow" : "stored");
                    return m;
                }, modelId, organizationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean includeRemoteEvents(String eventType) {
        if (eventType == null || eventType.isBlank()) return true;
        String t = eventType.toUpperCase(Locale.ROOT);
        return t.startsWith("PREDICTION") || t.startsWith("SHADOW") || t.startsWith("MODEL") || t.startsWith("EXPERIMENT");
    }

    private boolean includeCoreEvents(String eventType) {
        if (eventType == null || eventType.isBlank()) return true;
        String t = eventType.toUpperCase(Locale.ROOT);
        if (t.startsWith("STUDY") || t.startsWith("AUDIT")) return true;
        if (t.startsWith("PREDICTION") || t.startsWith("SHADOW") || t.startsWith("MODEL") || t.startsWith("EXPERIMENT")) {
            return !remote.enabled();
        }
        return !remote.enabled();
    }

    private List<Map<String, Object>> coreEvents(long organizationId, ActivityQuery q, ActivityCursor cursor, int fetchLimit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(filterOlder(studyEvents(organizationId, q), cursor, fetchLimit));
        rows.addAll(filterOlder(auditEvents(organizationId, q), cursor, fetchLimit));
        if (!remote.enabled()) {
            rows.addAll(shadowEvents(organizationId, q, cursor, fetchLimit));
            rows.addAll(filterOlder(experimentEvents(organizationId, q), cursor, fetchLimit));
            rows.addAll(filterOlder(modelApprovalEvents(organizationId, q), cursor, fetchLimit));
        }
        return rows;
    }

    private static List<Map<String, Object>> filterOlder(List<Map<String, Object>> events, ActivityCursor cursor, int cap) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : events) {
            if (cursor != null && !cursor.isOlderThan(e)) continue;
            out.add(e);
            if (out.size() >= cap) break;
        }
        return out;
    }

    private List<Map<String, Object>> shadowEvents(long organizationId, ActivityQuery q, ActivityCursor cursor, int fetchLimit) {
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
        appendShadowKeyset(sql, args, cursor);
        sql.append(" order by s.at desc, s.id desc limit ?");
        args.add(fetchLimit);
        return jdbc.query(sql.toString(), (rs, n) -> shadowRow(rs), args.toArray());
    }

    private static void appendShadowKeyset(StringBuilder sql, List<Object> args, ActivityCursor cursor) {
        if (cursor == null) return;
        Long shadowId = cursor.shadowId();
        if (shadowId != null && cursor.at != null) {
            sql.append(" and (s.at < ? or (s.at = ? and s.id < ?))");
            args.add(Timestamp.valueOf(cursor.at));
            args.add(Timestamp.valueOf(cursor.at));
            args.add(shadowId);
        } else if (shadowId != null) {
            sql.append(" and s.id < ?");
            args.add(shadowId);
        } else if (cursor.at != null) {
            sql.append(" and s.at < ?");
            args.add(Timestamp.valueOf(cursor.at));
        }
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
        if (q.eventType() != null && !q.eventType().isBlank()) {
            String t = q.eventType().toUpperCase(Locale.ROOT);
            if ("PREDICTION".equals(t)) sql.append(" and s.error is null");
            else if ("SHADOW_FAILURE".equals(t)) sql.append(" and s.error is not null");
        }
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
        if (q.from() != null) {
            sql.append(" and coalesce(finished_at, created_at) >= ?");
            args.add(Timestamp.valueOf(q.from()));
        }
        if (q.to() != null) {
            sql.append(" and coalesce(finished_at, created_at) <= ?");
            args.add(Timestamp.valueOf(q.to()));
        }
        sql.append(" order by coalesce(finished_at, created_at) desc, id desc limit 60");
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
                        """, rs -> {
                    if (!rs.next()) return null;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("at", rs.getTimestamp("at").toLocalDateTime());
                    row.put("txId", rs.getString("tx_id"));
                    row.put("prediction", rs.getString("prediction"));
                    row.put("engineDecision", rs.getString("engine_decision"));
                    row.put("latencyMs", rs.getObject("latency_ms"));
                    return row;
                }, organizationId, modelId);
        int t = total == null ? 0 : total;
        int a = agree == null ? 0 : agree;
        stats.put("predictionTotal", t);
        stats.put("agreementRate", t == 0 ? null : Math.round(a * 1000.0 / t) / 10.0);
        stats.put("meanLatencyMs", latency == null ? null : Math.round(latency * 1000.0) / 1000.0);
        stats.put("lastPrediction", last);
        stats.put("recentActivity", shadowEvents(organizationId,
                new ActivityQuery(0, 12, null, modelId, null, null, null, null, null, null, null, null, null, false),
                null, 12));
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

    private static List<Map<String, Object>> dedupe(List<Map<String, Object>> events) {
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (Map<String, Object> e : events) {
            String key = String.valueOf(e.get("key"));
            if (seen.add(key)) out.add(e);
        }
        return out;
    }

    private static void normalizeEventTimestamps(Map<String, Object> e) {
        Object at = e.get("at");
        if (at instanceof String s && !s.isBlank()) {
            e.put("at", parseEventAt(s));
        }
    }

    static LocalDateTime eventAt(Map<String, Object> e) {
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