package ninibayr.karting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.LocalTime;

/**
 * Расписание фонового опроса сайта.
 *
 * <p>Время — локальное для JVM. В Docker часовой пояс задаётся через
 * {@code KARTING_TZ} (по умолчанию {@code Europe/Moscow}).</p>
 */
@ConfigurationProperties(prefix = "karting.polling")
public class PollingProperties {

    /** Интервал фонового обновления днём. */
    private Duration dayInterval = Duration.ofSeconds(30);

    /** Интервал фонового обновления ночью. */
    private Duration nightInterval = Duration.ofMinutes(10);

    /** Начало ночного окна (включительно). */
    private LocalTime nightStart = LocalTime.of(23, 0);

    /** Конец ночного окна (не включая). */
    private LocalTime nightEnd = LocalTime.of(8, 0);

    /** Сколько последних заездов прогревать днём. */
    private int dayPrefetchLimit = 30;

    /** Сколько последних заездов прогревать ночью. */
    private int nightPrefetchLimit = 5;

    /** Минимальная пауза между переподключениями live-WS ночью. */
    private Duration nightReconnectDelay = Duration.ofMinutes(10);

    public Duration getDayInterval() {
        return dayInterval;
    }

    public void setDayInterval(Duration dayInterval) {
        this.dayInterval = dayInterval;
    }

    public Duration getNightInterval() {
        return nightInterval;
    }

    public void setNightInterval(Duration nightInterval) {
        this.nightInterval = nightInterval;
    }

    public LocalTime getNightStart() {
        return nightStart;
    }

    public void setNightStart(LocalTime nightStart) {
        this.nightStart = nightStart;
    }

    public LocalTime getNightEnd() {
        return nightEnd;
    }

    public void setNightEnd(LocalTime nightEnd) {
        this.nightEnd = nightEnd;
    }

    public int getDayPrefetchLimit() {
        return dayPrefetchLimit;
    }

    public void setDayPrefetchLimit(int dayPrefetchLimit) {
        this.dayPrefetchLimit = dayPrefetchLimit;
    }

    public int getNightPrefetchLimit() {
        return nightPrefetchLimit;
    }

    public void setNightPrefetchLimit(int nightPrefetchLimit) {
        this.nightPrefetchLimit = nightPrefetchLimit;
    }

    public Duration getNightReconnectDelay() {
        return nightReconnectDelay;
    }

    public void setNightReconnectDelay(Duration nightReconnectDelay) {
        this.nightReconnectDelay = nightReconnectDelay;
    }
}
