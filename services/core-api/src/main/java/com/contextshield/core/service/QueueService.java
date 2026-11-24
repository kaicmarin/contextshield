package com.contextshield.core.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Fila do analista: filtros, ordenação e página calculados no banco. */
@Service
public class QueueService {

    public static final Map<String, List<String>> GROUPS = Map.of(
            "ATENCAO", List.of("INTERVENTION", "CONTEXT_REQUIRED", "ANALYZING", "BLOCKED"),
            "AGUARDANDO", List.of("CONTEXT_REQUIRED", "INTERVENTION"));

    private static final Map<String, String> SORTS = Map.of(
            "id", "t.id",
            "customerName", "c.name",
            "description", "t.description",
            "amount", "t.amount",
            "score", "a.score",
            "ruleId", "a.rule_id",
            "status", "t.status",
            "createdAt", "t.created_at");

    private static final String FROM = """
            from transactions t
            join customer c on c.id = t.customer_id
            left join assessment a on a.tx_id = t.id
              and a.id = (select max(b.id) from assessment b where b.tx_id = t.id)
            """;

    private final JdbcTemplate jdbc;

    public QueueService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> page(String status, String q, Integer days, String band, int page, int size, String sort, String dir) {
        LocalDateTime from = MetricsService.windowStart(days);
        String order = SORTS.getOrDefault(sort, "t.created_at");
        String direction = "asc".equalsIgnoreCase(dir) ? "asc" : "desc";

        List<Object> args = new ArrayList<>();
        String where = where(from, blank(band), blank(q), blank(status), args);
        Long total = jdbc.queryForObject("select count(*) " + FROM + where, Long.class, args.toArray());
        long count = total == null ? 0 : total;

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * (long) size);
        List<Map<String, Object>> items = jdbc.query("""
                select t.id, t.customer_id, c.name, t.description, t.amount, t.status,
                       a.score, a.band, a.decision, a.rule_id, a.fragility,
                       t.declared_origin, t.scenario, t.created_at, t.updated_at
                """ + FROM + where + " order by " + order + " " + direction + " nulls last, t.id desc limit ? offset ?",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString(1));
                    row.put("customerId", rs.getString(2));
                    row.put("customerName", rs.getString(3));
                    row.put("description", rs.getString(4));
                    row.put("amount", rs.getBigDecimal(5));
                    row.put("status", rs.getString(6));
                    int score = rs.getInt(7);
                    row.put("score", rs.wasNull() ? null : score);
                    row.put("band", rs.getString(8));
                    row.put("decision", rs.getString(9));
                    row.put("ruleId", rs.getString(10));
                    row.put("fragility", rs.getString(11));
                    row.put("declaredOrigin", rs.getString(12));
                    row.put("scenario", rs.getString(13));
                    row.put("createdAt", rs.getTimestamp(14).toLocalDateTime());
                    row.put("updatedAt", rs.getTimestamp(15).toLocalDateTime());
                    return row;
                }, pageArgs.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", page);
        out.put("size", size);
        out.put("total", count);
        out.put("pages", size == 0 ? 0 : (int) Math.ceil(count / (double) size));
        out.put("counts", counts(from, blank(band), blank(q)));
        out.put("items", items);
        return out;
    }

    private Map<String, Long> counts(LocalDateTime from, String band, String q) {
        List<Object> args = new ArrayList<>();
        String where = where(from, band, q, null, args);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        jdbc.query("select t.status, count(*) " + FROM + where + " group by t.status", args.toArray(), rs -> {
            byStatus.put(rs.getString(1), rs.getLong(2));
        });
        Map<String, Long> out = new LinkedHashMap<>();
        long all = byStatus.values().stream().mapToLong(Long::longValue).sum();
        out.put("ALL", all);
        out.put("ATENCAO", sum(byStatus, GROUPS.get("ATENCAO")));
        out.put("AGUARDANDO", sum(byStatus, GROUPS.get("AGUARDANDO")));
        byStatus.forEach(out::put);
        return out;
    }

    private static long sum(Map<String, Long> byStatus, List<String> names) {
        return names.stream().mapToLong(n -> byStatus.getOrDefault(n, 0L)).sum();
    }

    private static String where(LocalDateTime from, String band, String q, String status, List<Object> args) {
        StringBuilder sql = new StringBuilder(" where 1=1 ");
        if (from != null) {
            sql.append(" and t.created_at >= ? ");
            args.add(Timestamp.valueOf(from));
        }
        if (band != null) {
            sql.append(" and a.band = ? ");
            args.add(band);
        }
        if (q != null) {
            String like = "%" + q.toLowerCase(Locale.ROOT) + "%";
            sql.append(" and (lower(t.id) like ? or lower(c.name) like ? or lower(coalesce(t.description, '')) like ? or lower(coalesce(a.rule_id, '')) like ?) ");
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (status != null) {
            List<String> group = GROUPS.get(status);
            if (group != null) {
                sql.append(" and t.status in (").append(String.join(", ", group.stream().map(s -> "?").toList())).append(") ");
                args.addAll(group);
            } else if (Set.of("RECEIVED", "ANALYZING", "APPROVED", "APPROVED_WITH_ALERT", "CONTEXT_REQUIRED",
                    "INTERVENTION", "BLOCKED", "CANCELLED", "INCIDENT_RECORDED").contains(status)) {
                sql.append(" and t.status = ? ");
                args.add(status);
            }
        }
        return sql.toString();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
