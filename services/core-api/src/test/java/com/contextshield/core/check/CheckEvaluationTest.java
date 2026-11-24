package com.contextshield.core.check;

import com.contextshield.core.repo.MerchantRepository;
import com.contextshield.core.repo.SellerRepository;
import com.contextshield.core.service.CheckService;
import com.contextshield.core.service.IntelligenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Métricas só no holdout (check-eval.json). Não falha a suíte por casar menos que 100%.
 * Falha se o arquivo estiver vazio ou se o verificador não devolver um nível válido.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckEvaluationTest {

    private static final Logger log = LoggerFactory.getLogger(CheckEvaluationTest.class);
    private static final List<String> LEVELS = List.of("RED", "YELLOW", "GRAY", "GREEN");

    @Mock IntelligenceService intel;
    @Mock MerchantRepository merchants;
    @Mock SellerRepository sellers;

    @Test
    void relatorioDoHoldout() throws Exception {
        when(merchants.findAll()).thenReturn(List.of());
        when(sellers.findAll()).thenReturn(List.of());
        when(intel.hintFor(anyString())).thenReturn(new IntelligenceService.IntelHint(0, null, null, null));
        when(intel.matchAddressText(anyString())).thenReturn(Optional.empty());
        CheckService check = new CheckService(intel, merchants, sellers);

        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/check-eval.json")) {
            assertThat(in).as("check-eval.json no classpath").isNotNull();
            root = new ObjectMapper().readTree(in);
        }
        JsonNode cases = root.path("cases");
        assertThat(cases.size()).isGreaterThanOrEqualTo(10);

        int alertTp = 0, alertFp = 0, alertFn = 0, alertTn = 0;
        Map<String, int[]> per = new LinkedHashMap<>();
        for (String l : LEVELS) per.put(l, new int[]{0, 0, 0});

        StringBuilder misses = new StringBuilder();
        long started = System.nanoTime();
        for (JsonNode c : cases) {
            String id = c.path("id").asText();
            String expect = c.path("expect").asText();
            String got = check.analyze(c.path("text").asText()).level();
            assertThat(LEVELS).as(id).contains(got);
            assertThat(LEVELS).as(id).contains(expect);
            boolean expAlert = expect.equals("RED") || expect.equals("YELLOW");
            boolean gotAlert = got.equals("RED") || got.equals("YELLOW");
            if (expAlert && gotAlert) alertTp++;
            else if (!expAlert && gotAlert) alertFp++;
            else if (expAlert && !gotAlert) alertFn++;
            else alertTn++;
            per.get(expect)[0]++;
            if (got.equals(expect)) per.get(expect)[1]++;
            else {
                per.get(got)[2]++;
                misses.append(id).append(" expect=").append(expect).append(" got=").append(got).append("; ");
            }
        }

        double prec = alertTp + alertFp == 0 ? 0 : alertTp / (double) (alertTp + alertFp);
        double rec = alertTp + alertFn == 0 ? 0 : alertTp / (double) (alertTp + alertFn);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        log.info("holdout n={} alert precisao={} recall={} FP={} FN={} TP={} TN={} tempoMs={} misses={}",
                cases.size(), round(prec), round(rec), alertFp, alertFn, alertTp, alertTn, elapsedMs, misses);
        for (String l : LEVELS) {
            int[] a = per.get(l);
            double p = a[1] + a[2] == 0 ? 0 : a[1] / (double) (a[1] + a[2]);
            double r = a[0] == 0 ? 0 : a[1] / (double) a[0];
            log.info("holdout classe {} suporte={} acertos={} FP={} precisao={} recall={}",
                    l, a[0], a[1], a[2], round(p), round(r));
        }
        assertThat(cases.size()).isEqualTo(alertTp + alertFp + alertFn + alertTn);
    }

    private static String round(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
