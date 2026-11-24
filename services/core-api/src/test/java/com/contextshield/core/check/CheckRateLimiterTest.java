package com.contextshield.core.check;

import com.contextshield.core.service.CheckRateLimiter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckRateLimiterTest {

    @Test
    void respeitaTetoDaJanela() {
        CheckRateLimiter lim = new CheckRateLimiter();
        long t = 1_000_000L;
        for (int i = 0; i < 3; i++) assertThat(lim.tryAcquire("1.1.1.1", 3, t)).isTrue();
        assertThat(lim.tryAcquire("1.1.1.1", 3, t)).isFalse();
        assertThat(lim.tryAcquire("2.2.2.2", 3, t)).isTrue();
        assertThat(lim.tryAcquire("1.1.1.1", 3, t + 60_000L)).isTrue();
    }
}
