package ninibayr.karting.service;

import ninibayr.karting.config.PollingProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SitePollingScheduleTest {

    private static PollingProperties properties(LocalTime start, LocalTime end) {
        PollingProperties properties = new PollingProperties();
        properties.setNightStart(start);
        properties.setNightEnd(end);
        properties.setDayInterval(Duration.ofSeconds(30));
        properties.setNightInterval(Duration.ofMinutes(10));
        properties.setDayPrefetchLimit(30);
        properties.setNightPrefetchLimit(5);
        return properties;
    }

    private static SitePollingSchedule schedule() {
        return new SitePollingSchedule(properties(LocalTime.of(23, 0), LocalTime.of(8, 0)));
    }

    @Test
    void nightWindowCrossesMidnight() {
        SitePollingSchedule schedule = schedule();

        assertFalse(schedule.isNight(LocalTime.of(22, 59)));
        assertTrue(schedule.isNight(LocalTime.of(23, 0)));
        assertTrue(schedule.isNight(LocalTime.of(0, 0)));
        assertTrue(schedule.isNight(LocalTime.of(7, 59)));
        assertFalse(schedule.isNight(LocalTime.of(8, 0)));
        assertFalse(schedule.isNight(LocalTime.of(12, 0)));
    }

    @Test
    void usesDifferentIntervalsAndPrefetchLimitsAtNight() {
        SitePollingSchedule schedule = schedule();

        assertEquals(Duration.ofSeconds(30), schedule.interval(LocalTime.of(12, 0)));
        assertEquals(Duration.ofMinutes(10), schedule.interval(LocalTime.of(23, 30)));

        assertEquals(30, schedule.prefetchLimit(LocalTime.of(12, 0)));
        assertEquals(5, schedule.prefetchLimit(LocalTime.of(23, 30)));
    }

    @Test
    void supportsWindowInsideOneDay() {
        SitePollingSchedule schedule = new SitePollingSchedule(
                properties(LocalTime.of(1, 0), LocalTime.of(5, 0)));

        assertTrue(schedule.isNight(LocalTime.of(3, 0)));
        assertFalse(schedule.isNight(LocalTime.of(6, 0)));
        assertFalse(schedule.isNight(LocalTime.of(0, 30)));
    }

    @Test
    void emptyWindowMeansAlwaysDay() {
        SitePollingSchedule schedule = new SitePollingSchedule(
                properties(LocalTime.of(23, 0), LocalTime.of(23, 0)));

        assertFalse(schedule.isNight(LocalTime.of(23, 0)));
        assertFalse(schedule.isNight(LocalTime.of(3, 0)));
    }
}
