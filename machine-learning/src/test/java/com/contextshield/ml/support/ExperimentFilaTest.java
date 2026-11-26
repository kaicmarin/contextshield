package com.contextshield.ml.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ExperimentFilaTest {

    @Test
    void claimRequiresQueuedRow() {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(
                new org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder()
                        .setType(org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType.H2)
                        .addScript("classpath:experiment-fila-h2.sql")
                        .build());
        ExperimentFila.recoverOnStartup(jdbc, "ml_experiment");
        jdbc.update("insert into ml_experiment (organization_id, status, seed, example_count, requested_by, created_at) values (1,'QUEUED',1,220,'t',current_timestamp)");
        Long id = ExperimentFila.claimNext(jdbc, "ml_experiment");
        assertNotNull(id);
        String status = jdbc.queryForObject("select status from ml_experiment where id = ?", String.class, id);
        assertEquals("RUNNING", status);
    }
}