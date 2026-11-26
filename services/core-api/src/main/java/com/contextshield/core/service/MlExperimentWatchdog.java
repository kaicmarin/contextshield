package com.contextshield.core.service;

import com.contextshield.ml.support.ExperimentFila;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConditionalOnProperty(name = "contextshield.ml.url", havingValue = "", matchIfMissing = true)
class MlExperimentWatchdog {
    private static final Logger log = LoggerFactory.getLogger(MlExperimentWatchdog.class);

    private final JdbcTemplate jdbc;
    private final MlLabService lab;
    private final Duration maxRun;

    MlExperimentWatchdog(JdbcTemplate jdbc, MlLabService lab,
                         @Value("${contextshield.experiment-max-minutes:20}") int maxMinutes) {
        this.jdbc = jdbc;
        this.lab = lab;
        this.maxRun = Duration.ofMinutes(Math.max(5, maxMinutes));
    }

    @Scheduled(fixedDelayString = "${contextshield.experiment-watchdog-ms:45000}")
    void sweep() {
        int expired = ExperimentFila.failExpired(jdbc, "ml_experiment", maxRun);
        if (expired > 0) {
            log.warn("Experimento(s) local(is) expirado(s): {}", expired);
            lab.kickWorker();
        }
    }
}