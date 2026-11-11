package ninibayr.karting.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import ninibayr.karting.dto.HeatList;
import ninibayr.karting.entity.HeatEntity;
import ninibayr.karting.exception.SiteRateLimitedException;
import ninibayr.karting.repository.HeatRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Фоновый «прогрев» кэша: заранее подтягивает с сайта заезды из списка,
 * чтобы открытие заезда в приложении не ждало синхронного парсинга.
 *
 * Работает в одном потоке и с паузами, чтобы не нагружать внешний сайт.
 */
@Service
@Slf4j
public class HeatPrefetchService {

    private final HeatRepository heatRepository;
    private final HeatSyncService heatSyncService;
    private final HeatListParser heatListParser;
    private final SitePollingSchedule schedule;
    private final SiteRequestGate siteRequestGate;

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "heat-prefetch");
        t.setDaemon(true);
        return t;
    });

    /** Уже поставленные в очередь / обработанные id, чтобы не дублировать работу. */
    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();

    /** Заезды, которые сейчас принудительно перечитываются (без проверки кэша). */
    private final Set<String> resyncing = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** После rate limit не запускаем новый прогрев до этого времени. */
    private volatile long retryAfterAt;

    public HeatPrefetchService(HeatRepository heatRepository,
                               HeatSyncService heatSyncService,
                               HeatListParser heatListParser,
                               SitePollingSchedule schedule,
                               SiteRequestGate siteRequestGate,
                               MeterRegistry meterRegistry) {
        this.heatRepository = heatRepository;
        this.heatSyncService = heatSyncService;
        this.heatListParser = heatListParser;
        this.schedule = schedule;
        this.siteRequestGate = siteRequestGate;
        Gauge.builder("karting.prefetch.queue", executor,
                        ex -> ex.getQueue().size())
                .description("Сколько заездов ждёт в очереди фонового прогрева")
                .register(meterRegistry);
        Gauge.builder("karting.prefetch.scheduled", scheduled, Set::size)
                .description("Сколько заездов уже поставлено на прогрев")
                .register(meterRegistry);
    }

    /**
     * Прогрев при старте приложения: к моменту первого запроса часть
     * заездов уже будет в БД.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void prefetchOnStartup() {
        prefetchRecentAsync();
    }

    /**
     * Запускает прогрев в фоне. Безопасно вызывать многократно:
     * повторный вызов не создаёт вторую очередь.
     */
    public void prefetchRecentAsync() {
        if (System.currentTimeMillis() < retryAfterAt || siteRequestGate.isBackgroundPaused()) {
            return;
        }
        if (!scheduled.isEmpty() || !running.compareAndSet(false, true)) {
            return; // прогрев уже запущен
        }
        executor.submit(() -> {
            try {
                prefetchRecent();
            } finally {
                running.set(false);
            }
        });
    }

    private void prefetchRecent() {
        try {
            if (siteRequestGate.isBackgroundPaused()) {
                log.debug("Фоновый прогрев отложен: сайт на паузе ещё {} с",
                        siteRequestGate.remainingBackgroundPauseSeconds());
                return;
            }
            LocalTime now = LocalTime.now();
            int limit = schedule.prefetchLimit(now);
            HeatList list = heatListParser.parseCurrentTrack();
            List<HeatList.HeatItem> items = list.getHeats().stream()
                    .limit(limit)
                    .toList();
            log.info("Фоновый прогрев: {} заездов ({} режим)", items.size(),
                    schedule.isNight(now) ? "ночной" : "дневной");

            for (HeatList.HeatItem item : items) {
                String id = item.getId();
                if (!scheduled.add(id)) {
                    continue;
                }
                if (siteRequestGate.isCoolingDown() || siteRequestGate.isBackgroundPaused()) {
                    backOff("сайт на cooldown/паузе");
                    return;
                }
                try {
                    // Уже есть в БД — не трогаем, данные подтянутся фоновой синхронизацией.
                    if (heatRepository.findBySiteId(id).isPresent()) {
                        continue;
                    }
                    heatSyncService.syncHeat(id);
                } catch (SiteRateLimitedException e) {
                    backOff(e.getMessage());
                    return;
                } catch (Exception e) {
                    log.warn("Прогрев заезда {} не удался: {}", id, e.getMessage());
                }
            }
            log.info("Фоновый прогрев завершён");
        } catch (SiteRateLimitedException e) {
            backOff(e.getMessage());
        } catch (Exception e) {
            log.warn("Фоновый прогрев не удался: {}", e.getMessage());
            scheduled.clear(); // разрешаем повторить позже
        }
    }

    private void backOff(String reason) {
        long seconds = Math.max(siteRequestGate.remainingCooldownSeconds(),
                siteRequestGate.remainingBackgroundPauseSeconds());
        seconds = Math.max(1, seconds);
        retryAfterAt = System.currentTimeMillis() + seconds * 1_000 + 1_000;
        scheduled.clear();
        log.warn("Фоновый прогрев остановлен: {}. Повтор через {} с", reason, seconds);
    }

    /** Есть ли заезд в локальном кэше БД. */
    public boolean isCached(String heatSiteId) {
        return heatRepository.findBySiteId(heatSiteId).isPresent();
    }

    /** Ставит в очередь конкретный заезд (например, при открытии из списка). */
    public void prefetchOneAsync(String heatSiteId) {
        if (isCached(heatSiteId) || !scheduled.add(heatSiteId)) {
            return;
        }
        if (siteRequestGate.isCoolingDown() || siteRequestGate.isBackgroundPaused()) {
            scheduled.remove(heatSiteId);
            log.debug("Прогрев заезда {} отложен: сайт на cooldown/паузе", heatSiteId);
            return;
        }
        executor.submit(() -> {
            try {
                heatSyncService.syncHeat(heatSiteId);
            } catch (SiteRateLimitedException e) {
                log.warn("Прогрев заезда {} отложен: {}", heatSiteId, e.getMessage());
            } catch (Exception e) {
                log.warn("Прогрев заезда {} не удался: {}", heatSiteId, e.getMessage());
            } finally {
                scheduled.remove(heatSiteId);
            }
        });
    }

    /**
     * Принудительно перечитывает заезд с сайта и обновляет его в БД — даже если
     * он уже в кэше. Нужно после завершения live-гонки и для дозаполнения
     * стинтов в старых заездах.
     */
    public void resyncOneAsync(String heatSiteId) {
        if (heatSiteId == null || !resyncing.add(heatSiteId)) {
            return;
        }
        if (siteRequestGate.isCoolingDown() || siteRequestGate.isBackgroundPaused()) {
            resyncing.remove(heatSiteId);
            log.debug("Пересинхронизация заезда {} отложена: сайт на cooldown/паузе", heatSiteId);
            return;
        }
        executor.submit(() -> {
            try {
                heatSyncService.syncHeat(heatSiteId);
            } catch (SiteRateLimitedException e) {
                log.warn("Пересинхронизация заезда {} отложена: {}", heatSiteId, e.getMessage());
            } catch (Exception e) {
                log.warn("Пересинхронизация заезда {} не удалась: {}", heatSiteId, e.getMessage());
            } finally {
                resyncing.remove(heatSiteId);
            }
        });
    }

    /** Подтянутые заезды, чтобы не запрашивать БД повторно. */
    public Set<String> cachedIds() {
        return heatRepository.findAll().stream()
                .map(HeatEntity::getSiteId)
                .collect(java.util.stream.Collectors.toSet());
    }
}
