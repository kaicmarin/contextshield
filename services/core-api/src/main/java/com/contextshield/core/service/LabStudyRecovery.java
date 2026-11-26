package com.contextshield.core.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;

@Component
class LabStudyRecovery implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    LabStudyRecovery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.update("""
                update ml_study set status = 'FAILED', error = ?, finished_at = ?
                where status in ('QUEUED', 'RUNNING')
                """, "O processo encerrou durante o estudo.", Timestamp.valueOf(LocalDateTime.now().withNano(0)));
    }
}