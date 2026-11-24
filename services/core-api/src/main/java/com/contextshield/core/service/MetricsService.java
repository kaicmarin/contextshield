package com.contextshield.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/** Números do painel do analista, calculados direto no banco. */
@Service
public class MetricsService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MetricsService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    public static final int MAX_DAYS = 3660;

    public static LocalDateTime windowStart(Integer days) {
        if (days == null || days <= 0) return null;
        LocalDateTime to = LocalDateTime.now(ZONE).withNano(0);
        return to.toLocalDate().minusDays(days - 1L).atStartOfDay();
    }

    /** Histórico completo. */
    public Map<String, Object> overview() {
        return overview(null, null);
    }

    /**
     * Indicadores do mesmo intervalo.
     * {@code days} é o número de datas civis em America/Sao_Paulo, terminando hoje e começando à meia-noite
     * do primeiro dia. Nulo ou zero significa todo o histórico. {@code band} filtra a última avaliação da compra
     * (LOW, MEDIUM, HIGH, CRITICAL). Relatos não têm banda: entram só pelo intervalo de criação.
     */
    public Map<String, Object> overview(Integer days, String band) {
        LocalDateTime to = LocalDateTime.now(ZONE).withNano(0);
        LocalDateTime from = null;
        if (days != null && days > 0) from = to.toLocalDate().minusDays(days - 1L).atStartOfDay();
        String risk = band == null || band.isBlank() ? null : band;

        List<Object> txArgs = new ArrayList<>();
        String txWhere = txWhere(from, risk, txArgs);

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        jdbc.query("select t.status, count(*) from transactions t " + txWhere + " group by t.status",
                txArgs.toArray(), rs -> { byStatus.put(rs.getString(1), rs.getLong(2)); });
        out.put("transactionsByStatus", byStatus);
        out.put("transactions", byStatus.values().stream().mapToLong(Long::longValue).sum());

        Map<String, Long> byDecision = new LinkedHashMap<>();
        Map<String, BigDecimal> byAmount = new LinkedHashMap<>();
        jdbc.query("""
                select a.decision, count(*), coalesce(sum(t.amount), 0)
                from transactions t
                join assessment a on a.tx_id = t.id
                  and a.id = (select max(b.id) from assessment b where b.tx_id = t.id)
                """ + txWhere + " group by a.decision", txArgs.toArray(), rs -> {
            byDecision.put(rs.getString(1), rs.getLong(2));
            byAmount.put(rs.getString(1), rs.getBigDecimal(3));
        });
        out.put("decisions", byDecision);
        out.put("decisionAmounts", byAmount);

        out.put("protectedAmount", jdbc.queryForObject(
                "select coalesce(sum(t.amount), 0) from transactions t " + txWhere + " and t.status in ('BLOCKED', 'CANCELLED')",
                BigDecimal.class, txArgs.toArray()));
        out.put("awaitingCustomer", jdbc.queryForObject(
                "select count(*) from transactions t " + txWhere + " and t.status in ('CONTEXT_REQUIRED', 'INTERVENTION')",
                Long.class, txArgs.toArray()));

        double[] score = jdbc.query("""
                select coalesce(avg(a.score), 0),
                       coalesce(sum(case when a.rule_id like 'S-%' then 1 else 0 end), 0)
                from transactions t
                join assessment a on a.tx_id = t.id
                  and a.id = (select max(b.id) from assessment b where b.tx_id = t.id)
                """ + txWhere, txArgs.toArray(), (ResultSetExtractor<double[]>) rs -> {
            rs.next();
            return new double[]{rs.getDouble(1), rs.getDouble(2)};
        });
        out.put("averageScore", score[0]);
        out.put("safetyAdjusted", Math.round(score[1]));

        List<Object> incArgs = new ArrayList<>();
        String incWhere = from == null ? "" : " where created_at >= ? ";
        if (from != null) incArgs.add(Timestamp.valueOf(from));
        Map<String, Long> incidents = new LinkedHashMap<>();
        jdbc.query("select status, count(*) from incident " + incWhere + " group by status", incArgs.toArray(), rs -> {
            incidents.put(rs.getString(1), rs.getLong(2));
        });
        out.put("incidentsByStatus", incidents);
        out.put("entities", jdbc.queryForObject("""
                select count(distinct m.entity_id) from intel_mention m
                join incident i on i.id = m.incident_id
                """ + (from == null ? "" : " where i.created_at >= ? "), Long.class, incArgs.toArray()));

        List<Object> latArgs = new ArrayList<>();
        StringBuilder latWhere = new StringBuilder(" where 1=1 ");
        if (from != null) {
            latWhere.append(" and created_at >= ? ");
            latArgs.add(Timestamp.valueOf(from));
        }
        if (risk != null) {
            latWhere.append(" and band = ? ");
            latArgs.add(risk);
        }
        List<Double> engineMs = new ArrayList<>();
        List<Double> roundTrip = new ArrayList<>();
        jdbc.query("select duration_ms, payload from assessment " + latWhere + " order by id desc limit 500",
                latArgs.toArray(), rs -> {
                    engineMs.add(rs.getDouble(1));
                    try {
                        JsonNode n = mapper.readTree(rs.getString(2));
                        if (n != null && n.has("roundTripMs")) roundTrip.add(n.get("roundTripMs").asDouble());
                    } catch (Exception ignored) {
                    }
                });
        Collections.sort(engineMs);
        Collections.sort(roundTrip);
        out.put("latency", Map.of(
                "samples", engineMs.size(),
                "engineP50", percentile(engineMs, 50), "engineP95", percentile(engineMs, 95),
                "roundTripP50", percentile(roundTrip, 50), "roundTripP95", percentile(roundTrip, 95)));

        out.put("daily", jdbc.queryForList("""
                select cast(t.created_at as date) as d, count(*) as total,
                       sum(case when t.status in ('BLOCKED','CANCELLED','INTERVENTION','CONTEXT_REQUIRED') then 1 else 0 end) as protected
                from transactions t """ + txWhere + " group by cast(t.created_at as date) order by d", txArgs.toArray()));

        Map<String, Long> origins = new LinkedHashMap<>();
        jdbc.query("select t.declared_origin, count(*) from transactions t " + txWhere
                        + " and t.declared_origin is not null group by t.declared_origin",
                txArgs.toArray(), (RowCallbackHandler) rs -> {
                    origins.put(rs.getString(1), rs.getLong(2));
                });
        out.put("origins", origins);

        out.put("recentHeld", jdbc.query("""
                select t.id, t.amount, t.status, t.created_at from transactions t """ + txWhere + """
                 and t.status in ('BLOCKED','CANCELLED','INTERVENTION','CONTEXT_REQUIRED')
                 order by t.created_at desc limit 5""",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString(1));
                    row.put("amount", rs.getBigDecimal(2));
                    row.put("status", rs.getString(3));
                    row.put("createdAt", rs.getTimestamp(4).toLocalDateTime());
                    return row;
                }, txArgs.toArray()));

        Long previousProtected = null;
        if (from != null && days != null && days > 0) {
            List<Object> prevArgs = new ArrayList<>();
            prevArgs.add(Timestamp.valueOf(from.minusDays(days)));
            prevArgs.add(Timestamp.valueOf(from));
            StringBuilder prev = new StringBuilder(" where t.created_at >= ? and t.created_at < ? ");
            if (risk != null) {
                prev.append("""
                         and exists (
                           select 1 from assessment a
                           where a.tx_id = t.id
                             and a.id = (select max(b.id) from assessment b where b.tx_id = t.id)
                             and a.band = ?)
                        """);
                prevArgs.add(risk);
            }
            previousProtected = jdbc.queryForObject(
                    "select coalesce(sum(case when t.status in ('BLOCKED','CANCELLED','INTERVENTION','CONTEXT_REQUIRED') then 1 else 0 end), 0) from transactions t " + prev,
                    Long.class, prevArgs.toArray());
        }
        out.put("previousProtected", previousProtected);

        Map<String, Object> window = new LinkedHashMap<>();
        window.put("days", days == null ? 0 : days);
        window.put("band", risk);
        window.put("from", from);
        window.put("to", to);
        window.put("zone", ZONE.getId());
        window.put("bandAppliesToIncidents", false);
        out.put("window", window);
        return out;
    }

    private static String txWhere(LocalDateTime from, String band, List<Object> args) {
        StringBuilder sql = new StringBuilder(" where 1=1 ");
        if (from != null) {
            sql.append(" and t.created_at >= ? ");
            args.add(Timestamp.valueOf(from));
        }
        if (band != null) {
            sql.append("""
                     and exists (
                       select 1 from assessment a
                       where a.tx_id = t.id
                         and a.id = (select max(b.id) from assessment b where b.tx_id = t.id)
                         and a.band = ?)
                    """);
            args.add(band);
        }
        return sql.toString();
    }
}
