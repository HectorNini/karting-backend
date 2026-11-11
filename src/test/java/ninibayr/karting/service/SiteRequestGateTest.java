package ninibayr.karting.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ninibayr.karting.exception.SiteRateLimitedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SiteRequestGateTest {

    private final SiteRequestGate gate = new SiteRequestGate(
            0, 60_000, 600_000, new SimpleMeterRegistry());

    @Test
    @DisplayName("429 включает cooldown и блокирует следующий запрос")
    void rateLimited_shouldStartCooldown() {
        SiteRateLimitedException first = gate.rateLimited(429, "2");

        assertThat(first.getRetryAfterSeconds()).isEqualTo(2);
        assertThat(gate.isCoolingDown()).isTrue();
        assertThatThrownBy(gate::beforeRequest)
                .isInstanceOf(SiteRateLimitedException.class);
    }

    @Test
    @DisplayName("429/403/5xx считаются rate limit, остальные HTTP-ошибки — нет")
    void isRateLimitStatus_shouldClassifyStatuses() {
        assertThat(gate.isRateLimitStatus(429)).isTrue();
        assertThat(gate.isRateLimitStatus(403)).isTrue();
        assertThat(gate.isRateLimitStatus(500)).isTrue();
        assertThat(gate.isRateLimitStatus(404)).isFalse();
        assertThat(gate.isRateLimitStatus(400)).isFalse();
    }
}
