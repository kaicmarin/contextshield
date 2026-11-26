package com.contextshield.mlservice;

import com.contextshield.ml.support.ExperimentFila;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
class MlExperimentWatchdog {
    private static final Logger log = LoggerFactory.getLogger(MlExperimentWatchdog.class);
    private static final String TABLE = "ml_experiment";

    private final JdbcTemplate jdbc;
    private final CatalogService catalog;
    private final Duration maxRun;

    MlExperimentWatchdog(JdbcTemplate jdbc, CatalogService catalog,
                         @Value("${contextshield.experiment-max-minutes:20}") int maxMinutes) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.maxRun = Duration.ofMinutes(Math.max(5, maxMinutes));
    }

    @Scheduled(fixedDelayString = "${contextshield.experiment-watchdog-ms:45000}")
    void sweep() {
        int expired = ExperimentFila.failExpired(jdbc, TABLE, maxRun);
        if (expired > 0) {
            log.warn("Experimento(s) expirado(s) marcado(s) FAILED: {}", expired);
            catalog.kickWorker();
        }
    }
}