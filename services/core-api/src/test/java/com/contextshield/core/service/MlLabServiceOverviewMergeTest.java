package com.contextshield.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** Garante merge do overview remoto (AWS) com catálogo local da Core API. */
@ExtendWith(MockitoExtension.class)
class MlLabServiceOverviewMergeTest {

    @Mock
    JdbcTemplate jdbc;
    @Mock
    AuditService audit;
    @Mock
    RiskPolicyService policies;
    @Mock
    EngineInputBuilder inputs;
    @Mock
    com.contextshield.core.repo.TransactionRepository transactions;
    @Mock
    OrganizationService organizations;
    @Mock
    MlClient remote;
    @Mock
    LabStudyService studies;

    MlLabService lab;

    @BeforeEach
    void setUp() {
        lab = new MlLabService(jdbc, new ObjectMapper(), audit, policies, inputs, transactions, organizations, remote, studies);
    }

    @Test
    void overviewMergesRemotePayloadWithLocalStudiesAndLabels() {
        when(remote.enabled()).thenReturn(true);
        Map<String, Object> remotePayload = new LinkedHashMap<>();
        remotePayload.put("models", List.of(Map.of("id", 1)));
        remotePayload.put("experiments", List.of());
        remotePayload.put("suggestions", List.of());
        remotePayload.put("shadow", Map.of("predictions", 2, "failures", 0, "disagreementsWithEngine", 1));
        remotePayload.put("decidesPurchase", false);
        remotePayload.put("limitation", "Remoto sintético.");
        when(remote.overview(7L)).thenReturn(remotePayload);

        Map<String, Object> labels = Map.of("reviewedFraudLegit", 4, "pendingReports", 1);
        List<Map<String, Object>> studyRows = List.of(Map.of("id", 99L, "status", "QUEUED"));
        when(studies.labelsOverview(7L)).thenReturn(labels);
        when(studies.studies(7L)).thenReturn(studyRows);
        when(studies.ruleSuggestions(7L)).thenReturn(List.of());

        when(jdbc.queryForObject(contains("known_outcome"), eq(Integer.class), eq(7L))).thenReturn(5);
        when(jdbc.queryForObject(contains("ml_shadow"), eq(Integer.class), eq(7L))).thenReturn(2, 0, 1);

        Map<String, Object> out = lab.overview(7L);

        assertSame(labels, out.get("labels"));
        assertEquals(studyRows, out.get("studies"));
        assertEquals(5, out.get("confirmed"));
        assertEquals(false, out.get("decidesPurchase"));
        @SuppressWarnings("unchecked")
        Map<String, Object> shadow = (Map<String, Object>) out.get("shadow");
        assertEquals(1, shadow.get("sameDirection"));
    }
}