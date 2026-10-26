package ninibayr.karting.service;

import ninibayr.karting.config.PollingProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalTime;

/**
 * Определяет, ночь сейчас или день, и какой интервал опроса использовать.
 *
 * <p>Ночное окно может пересекать полночь: например, 23:00–08:00.</p>
 */
@Component
public class SitePollingSchedule {

    private final PollingProperties properties;

    public SitePollingSchedule(PollingProperties properties) {
        this.properties = properties;
    }

    /** Попадает ли время в ночное окно. */
    public boolean isNight(LocalTime time) {
        LocalTime start = properties.getNightStart();
        LocalTime end = properties.getNightEnd();
        if (start.equals(end)) {
            return false;
        }
        if (start.isBefore(end)) {
            return !time.isBefore(start) && time.isBefore(end);
        }
        // Окно через полночь: [start, 24:00) или [00:00, end).
        return !time.isBefore(start) || time.isBefore(end);
    }

    /** Интервал фонового опроса для указанного времени. */
    public Duration interval(LocalTime time) {
        return isNight(time) ? properties.getNightInterval() : properties.getDayInterval();
    }

    /** Сколько последних заездов прогревать в указанное время. */
    public int prefetchLimit(LocalTime time) {
        return isNight(time) ? properties.getNightPrefetchLimit() : properties.getDayPrefetchLimit();
    }

    /** Минимальная пауза между переподключениями live-WS ночью. */
    public Duration nightReconnectDelay() {
        return properties.getNightReconnectDelay();
    }
}
