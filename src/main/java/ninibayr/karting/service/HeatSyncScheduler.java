package ninibayr.karting.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Страховочный опрос новых заездов. По умолчанию выключен: основной запрос
 * к сайту делает завершение live-сессии. Если включить, тикает раз в 30 секунд
 * и запускает проверку только тогда, когда подошёл интервал (10 минут днём,
 * 30 минут ночью — настраивается).
 */
@Component
@Slf4j
public class HeatSyncScheduler {

    /** Частота тика; не должна быть больше самого короткого интервала. */
    private static final long TICK_MS = 30_000;

    private final HeatService heatService;
    private final SitePollingSchedule schedule;
    private final Clock clock;
    private final boolean enabled;
    private final Counter dayRuns;
    private final Counter nightRuns;

    private final AtomicLong lastRunAt = new AtomicLong();
    private volatile Boolean lastNight;

    @Autowired
    public HeatSyncScheduler(HeatService heatService, SitePollingSchedule schedule,
                             MeterRegistry meterRegistry,
                             @Value("${karting.polling.new-heats-sync-enabled:false}") boolean enabled) {
        this(heatService, schedule, Clock.systemDefaultZone(), meterRegistry, enabled);
    }

    HeatSyncScheduler(HeatService heatService, SitePollingSchedule schedule,
                      Clock clock, MeterRegistry meterRegistry) {
        this(heatService, schedule, clock, meterRegistry, true);
    }

    HeatSyncScheduler(HeatService heatService, SitePollingSchedule schedule,
                      Clock clock, MeterRegistry meterRegistry, boolean enabled) {
        this.heatService = heatService;
        this.schedule = schedule;
        this.clock = clock;
        this.enabled = enabled;
        this.dayRuns = meterRegistry.counter("karting.polling.runs", "mode", "day");
        this.nightRuns = meterRegistry.counter("karting.polling.runs", "mode", "night");
        Gauge.builder("karting.polling.night", this, s -> s.isNightNow() ? 1 : 0)
                .description("Ночной режим опроса сайта (1/0)")
                .register(meterRegistry);
        Gauge.builder("karting.polling.interval.seconds", this, s -> s.currentIntervalSeconds())
                .description("Текущий интервал фонового опроса сайта, секунды")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = TICK_MS)
    public void tick() {
        runIfDue();
    }

    void runIfDue() {
        if (!enabled) {
            return;
        }
        LocalTime nowTime = LocalTime.now(clock);
        long now = clock.millis();
        long last = lastRunAt.get();
        Duration interval = schedule.interval(nowTime);
        if (last != 0 && now - last < interval.toMillis()) {
            return;
        }
        if (!lastRunAt.compareAndSet(last, now)) {
            return;
        }

        boolean night = schedule.isNight(nowTime);
        if (lastNight == null || lastNight != night) {
            log.info("Опрос сайта: {} режим, интервал {}", night ? "ночной" : "дневной", interval);
            lastNight = night;
        }
        (night ? nightRuns : dayRuns).increment();
        try {
            heatService.syncNewHeats();
        } catch (Exception e) {
            log.error("Проверка новых заездов не удалась: {}", e.getMessage(), e);
        }
    }

    private boolean isNightNow() {
        return schedule.isNight(LocalTime.now(clock));
    }

    private double currentIntervalSeconds() {
        return schedule.interval(LocalTime.now(clock)).toSeconds();
    }
}
