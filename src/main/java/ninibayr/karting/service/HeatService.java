package ninibayr.karting.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import ninibayr.karting.dto.HeatData;
import ninibayr.karting.dto.HeatList;
import ninibayr.karting.entity.HeatEntity;
import ninibayr.karting.exception.SiteRateLimitedException;
import ninibayr.karting.repository.HeatRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class HeatService {

    /** Не обновляем за один тик больше, чем нужно: сайт не любит пачки запросов. */
    private static final int MAX_SYNCS_PER_RUN = 2;

    private final HeatRepository heatRepository;
    private final HeatSyncService heatSyncService;
    private final HeatReadService heatReadService;
    private final HeatListParser heatListParser;
    private final HeatPrefetchService heatPrefetchService;
    private final LiveTimingService liveTimingService;
    private final SiteRequestGate siteRequestGate;
    private final MeterRegistry meterRegistry;

    public HeatService(HeatRepository heatRepository, HeatSyncService heatSyncService,
                       HeatReadService heatReadService, HeatListParser heatListParser,
                       HeatPrefetchService heatPrefetchService,
                       LiveTimingService liveTimingService,
                       SiteRequestGate siteRequestGate,
                       MeterRegistry meterRegistry) {
        this.heatRepository = heatRepository;
        this.heatSyncService = heatSyncService;
        this.heatReadService = heatReadService;
        this.heatListParser = heatListParser;
        this.heatPrefetchService = heatPrefetchService;
        this.liveTimingService = liveTimingService;
        this.siteRequestGate = siteRequestGate;
        this.meterRegistry = meterRegistry;
        // Регистрируем заранее, чтобы в Prometheus были нули, а не «нет данных».
        meterRegistry.counter("karting.heat.cache.requests", "hit", "true");
        meterRegistry.counter("karting.heat.cache.requests", "hit", "false");
    }

    /**
     * Быстрое чтение с фоллбэком на синхронизацию.
     *
     * <p>Кэш читается в короткой транзакции внутри {@link HeatReadService}.
     * Если заезда нет, синхронизация выполняется уже без открытой транзакции,
     * поэтому сетевой запрос к сайту не держит соединение с БД.</p>
     */
    public HeatData getHeatData(String heatSiteId) {
        Optional<HeatReadService.CachedHeat> cached = heatReadService.findCached(heatSiteId);
        if (cached.isPresent()) {
            meterRegistry.counter("karting.heat.cache.requests", "hit", "true").increment();
            HeatReadService.CachedHeat heat = cached.get();
            // Данные старого парсера: стинтов ещё нет. Отдаём кэш сразу,
            // а в фоне перечитываем заезд — приложение обновляется каждые 5 с.
            if (heat.needsResync()) {
                log.info("Заезд {} без стинтов — ставим на пересинхронизацию", heatSiteId);
                heatPrefetchService.resyncOneAsync(heatSiteId);
            }
            return heat.data();
        }

        meterRegistry.counter("karting.heat.cache.requests", "hit", "false").increment();
        log.info("Заезд {} не найден в БД, запускаем синхронизацию...", heatSiteId);
        try {
            return heatSyncService.syncHeat(heatSiteId);
        } catch (SiteRateLimitedException e) {
            log.warn("Синхронизация заезда {} отложена: {}", heatSiteId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Синхронизация заезда {} не удалась: {}", heatSiteId, e.getMessage());
            throw new RuntimeException("Не удалось загрузить заезд " + heatSiteId + ". Попробуйте обновить список или повторить позже.", e);
        }
    }

    /**
     * Перечитывает с сайта заезды, сохранённые без стинтов (старый парсер).
     * Возвращает число успешно обновлённых.
     */
    public int resyncMissingStints(int limit) {
        int effective = Math.max(1, Math.min(limit, 200));
        List<HeatEntity> heats = heatRepository.findMissingStints(PageRequest.of(0, effective));
        int updated = 0;
        for (HeatEntity heat : heats) {
            try {
                heatSyncService.syncHeat(heat.getSiteId());
                updated++;
            } catch (SiteRateLimitedException e) {
                log.warn("Пересинхронизация остановлена: {}", e.getMessage());
                break;
            } catch (Exception e) {
                log.warn("Пересинхронизация заезда {} не удалась: {}", heat.getSiteId(), e.getMessage());
            }
        }
        log.info("Пересинхронизация стинтов: обновлено {} из {}", updated, heats.size());
        return updated;
    }

    /**
     * Фоновое обновление заездов. Вызывается планировщиком, который сам решает,
     * когда пора: днём каждые 30 секунд, ночью — раз в 10 минут.
     *
     * <p>Днём обновляем сегодняшние заезды и всё, что менялось за последние 6 часов.
     * Ночью — только те, что могли ещё идти (стартовали не позже 2 часов назад),
     * поэтому в обычную ночь сайт вообще не опрашивается.</p>
     *
     * @param night ночной режим (редкий опрос)
     */
    public void updateBackgroundCache(boolean night) {
        if (siteRequestGate.isBackgroundPaused()) {
            log.debug("Фоновое обновление пропущено: сайт на паузе ещё {} с",
                    siteRequestGate.remainingBackgroundPauseSeconds());
            return;
        }
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        // Не синхронизируем повторно заезд чаще, чем раз в 5 минут — иначе
        // бесконечный цикл «обновили → lastUpdated стал свежим → снова обновили».
        LocalDateTime minAge = now.minusMinutes(5);

        List<HeatEntity> candidates = night
                ? heatRepository.findNightCandidates(today, now.minusHours(2).toLocalTime())
                : heatRepository.findDayCandidates(today, now.minusHours(6));

        List<HeatEntity> toUpdate = candidates.stream()
                .filter(h -> h.getLastUpdated() == null || !h.getLastUpdated().isAfter(minAge))
                .limit(MAX_SYNCS_PER_RUN)
                .toList();

        if (toUpdate.isEmpty()) {
            log.debug("Фоновое обновление ({}): нечего обновлять", night ? "ночь" : "день");
            return;
        }

        log.info("Фоновое обновление ({}): к обновлению {} заездов", night ? "ночь" : "день", toUpdate.size());

        for (HeatEntity entity : toUpdate) {
            try {
                heatSyncService.syncHeat(entity.getSiteId());
            } catch (SiteRateLimitedException e) {
                log.warn("Фоновое обновление остановлено: {}", e.getMessage());
                break;
            } catch (Exception e) {
                log.error("Ошибка фонового обновления для {}: {}", entity.getSiteId(), e.getMessage());
            }
        }
    }

    public HeatList getHeatList() {
        try {
            HeatList parsed = heatListParser.parseCurrentTrack();
            // Нельзя менять список, который лежит в кэше парсера: иначе
            // скрытый live-заезд останется скрытым и после завершения сессии.
            HeatList result = new HeatList(parsed.getTrackName(), new ArrayList<>(parsed.getHeats()));
            String liveHeatId = liveTimingService.getLiveHeatId();
            if (liveHeatId != null) {
                // Сайт уже показывает текущий заезд в общем списке, но он ещё
                // не завершён. Показываем его только после окончания сессии.
                List<HeatList.HeatItem> visible = result.getHeats().stream()
                        .filter(item -> !liveHeatId.equals(item.getId()))
                        .toList();
                if (visible.size() != result.getHeats().size()) {
                    log.debug("Скрываем текущий live-заезд {} из общего списка", liveHeatId);
                    result.setHeats(visible);
                }
            }
            // Прогреваем кэш последних заездов в фоне, чтобы открытие было мгновенным.
            heatPrefetchService.prefetchRecentAsync();
            return result;
        } catch (SiteRateLimitedException e) {
            log.warn("Список заездов временно недоступен: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Не удалось получить список заездов", e);
            throw new RuntimeException("Не удалось получить список заездов. Проверьте подключение к сети.", e);
        }
    }
}