package ninibayr.karting.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ninibayr.karting.config.PollingProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class HeatSyncSchedulerTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    private static SitePollingSchedule schedule() {
        PollingProperties properties = new PollingProperties();
        properties.setNightStart(LocalTime.of(23, 0));
        properties.setNightEnd(LocalTime.of(8, 0));
        properties.setDayInterval(Duration.ofSeconds(30));
        properties.setNightInterval(Duration.ofMinutes(10));
        return new SitePollingSchedule(properties);
    }

    @Test
    void runsEveryThirtySecondsDuringDay() {
        HeatService heatService = mock(HeatService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MutableClock clock = new MutableClock(Instant.parse("2026-10-26T09:00:00Z"), ZONE); // 12:00 МСК
        HeatSyncScheduler scheduler = new HeatSyncScheduler(heatService, schedule(), clock, registry);

        scheduler.runIfDue();
        verify(heatService).syncNewHeats();

        clock.plus(Duration.ofSeconds(29));
        scheduler.runIfDue();
        verifyNoMoreInteractions(heatService);

        clock.plus(Duration.ofSeconds(1));
        scheduler.runIfDue();
        verify(heatService, times(2)).syncNewHeats();

        assertEquals(2.0, registry.get("karting.polling.runs")
                .tag("mode", "day").counter().count());
        assertEquals(30.0, registry.get("karting.polling.interval.seconds").gauge().value());
        assertEquals(0.0, registry.get("karting.polling.night").gauge().value());
    }

    @Test
    void runsEveryTenMinutesDuringNight() {
        HeatService heatService = mock(HeatService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MutableClock clock = new MutableClock(Instant.parse("2026-10-26T20:30:00Z"), ZONE); // 23:30 МСК
        HeatSyncScheduler scheduler = new HeatSyncScheduler(heatService, schedule(), clock, registry);

        scheduler.runIfDue();
        verify(heatService).syncNewHeats();

        clock.plus(Duration.ofMinutes(9));
        scheduler.runIfDue();
        verifyNoMoreInteractions(heatService);

        clock.plus(Duration.ofMinutes(1));
        scheduler.runIfDue();
        verify(heatService, times(2)).syncNewHeats();

        assertEquals(2.0, registry.get("karting.polling.runs")
                .tag("mode", "night").counter().count());
        assertEquals(600.0, registry.get("karting.polling.interval.seconds").gauge().value());
        assertEquals(1.0, registry.get("karting.polling.night").gauge().value());
    }

    /** Часы, которые можно двигать вручную. */
    private static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void plus(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
