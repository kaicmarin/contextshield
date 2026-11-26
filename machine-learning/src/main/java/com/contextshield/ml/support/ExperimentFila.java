package com.contextshield.ml.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Estados de experimento no banco, com recuperação após falha ou reinício.
 * A fila é FIFO: vários QUEUED, no máximo um RUNNING por organização (claim atômico).
 */
public final class ExperimentFila {
    public static final String ERR_PROCESS_END = "O processo encerrou durante o experimento.";
    public static final String ERR_TIMEOUT = "Treino interrompido: tempo máximo de execução excedido.";
    public static final String ERR_ORPHAN = "Recuperado: execução anterior ficou sem conclusão.";

    private ExperimentFila() {}

    public static void recoverOnStartup(JdbcTemplate jdbc, String table) {
        jdbc.update("""
                update %s set status = 'FAILED', error = ?, finished_at = ?
                where status in ('QUEUED', 'RUNNING')
                """.formatted(table), ERR_PROCESS_END, now());
    }

    public static int failExpired(JdbcTemplate jdbc, String table, Duration maxRun) {
        if (maxRun == null || maxRun.isZero() || maxRun.isNegative()) return 0;
        Timestamp threshold = Timestamp.valueOf(LocalDateTime.now().minus(maxRun).withNano(0));
        return jdbc.update("""
                update %s set status = 'FAILED', error = ?, finished_at = ?
                where status = 'RUNNING' and started_at is not null and started_at < ?
                """.formatted(table), ERR_TIMEOUT, now(), threshold)
                + jdbc.update("""
                update %s set status = 'FAILED', error = ?, finished_at = ?
                where status = 'RUNNING' and started_at is null and created_at < ?
                """.formatted(table), ERR_ORPHAN, now(), threshold);
    }

    public static int failOrphanRunning(JdbcTemplate jdbc, String table) {
        return jdbc.update("""
                update %s set status = 'FAILED', error = ?, finished_at = ?
                where status = 'RUNNING'
                """.formatted(table), ERR_ORPHAN, now());
    }

  public static boolean hasQueued(JdbcTemplate jdbc, String table, long organizationId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from %s where organization_id = ? and status = 'QUEUED'".formatted(table),
                Integer.class, organizationId);
        return n != null && n > 0;
    }

    public static boolean hasInFlight(JdbcTemplate jdbc, String table) {
        Integer n = jdbc.queryForObject(
                "select count(*) from %s where status in ('QUEUED', 'RUNNING')".formatted(table), Integer.class);
        return n != null && n > 0;
    }

    /** Devolve o id reivindicado ou null se a fila estiver vazia. */
    public static Long claimNext(JdbcTemplate jdbc, String table) {
        List<Long> ids = jdbc.query("""
                select id from %s where status = 'QUEUED' order by id asc limit 1
                """.formatted(table), (rs, n) -> rs.getLong(1));
        if (ids.isEmpty()) return null;
        long id = ids.get(0);
        int updated = jdbc.update("""
                update %s set status = 'RUNNING', started_at = ? where id = ? and status = 'QUEUED'
                """.formatted(table), now(), id);
        return updated == 1 ? id : null;
    }

    private static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now().withNano(0));
    }
}