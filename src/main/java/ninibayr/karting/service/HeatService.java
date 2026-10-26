package ninibayr.karting.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import ninibayr.karting.dto.HeatData;
import ninibayr.karting.dto.HeatList;
import ninibayr.karting.entity.HeatEntity;
import ninibayr.karting.repository.HeatRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class HeatService {

    private final HeatRepository heatRepository;
    private final HeatSyncService heatSyncService;
    private final HeatMapper heatMapper;
    private final HeatListParser heatListParser;
    private final HeatPrefetchService heatPrefetchService;
    private final MeterRegistry meterRegistry;

    public HeatService(HeatRepository heatRepository, HeatSyncService heatSyncService,
                       HeatMapper heatMapper, HeatListParser heatListParser,
                       HeatPrefetchService heatPrefetchService, MeterRegistry meterRegistry) {
        this.heatRepository = heatRepository;
        this.heatSyncService = heatSyncService;
        this.heatMapper = heatMapper;
        this.heatListParser = heatListParser;
        this.heatPrefetchService = heatPrefetchService;
        this.meterRegistry = meterRegistry;
        // Регистрируем заранее, чтобы в Prometheus были нули, а не «нет данных».
        meterRegistry.counter("karting.heat.cache.requests", "hit", "true");
        meterRegistry.counter("karting.heat.cache.requests", "hit", "false");
    }

    /**
     * Быстрое чтение с фоллбэком на синхронизацию.
     * readOnly = true здесь безопасен, так как мы не делаем запись в этом методе.
     * Если данных нет, мы делегируем запись другому сервису (heatSyncService),
     * и Spring AOP корректно откроет для него новую транзакцию.
     */
    @Transactional(readOnly = true)
    public HeatData getHeatData(String heatSiteId) {
        return heatRepository.findBySiteId(heatSiteId)
                .map(heat -> {
                    meterRegistry.counter("karting.heat.cache.requests", "hit", "true").increment();
                    // Данные старого парсера: стинтов ещё нет. Отдаём кэш сразу,
                    // а в фоне перечитываем заезд — приложение обновляется каждые 5 с.
                    if (heat.getStints().isEmpty() && !heat.getLaps().isEmpty()) {
                        log.info("Заезд {} без стинтов — ставим на пересинхронизацию", heatSiteId);
                        heatPrefetchService.resyncOneAsync(heatSiteId);
                    }
                    return heatMapper.toDto(heat);
                })
                .orElseGet(() -> {
                    meterRegistry.counter("karting.heat.cache.requests", "hit", "false").increment();
                    log.info("Заезд {} не найден в БД, запускаем синхронизацию...", heatSiteId);
                    try {
                        return heatSyncService.syncHeat(heatSiteId);
                    } catch (Exception e) {
                        log.error("Синхронизация заезда {} не удалась: {}", heatSiteId, e.getMessage());
                        throw new RuntimeException("Не удалось загрузить заезд " + heatSiteId + ". Попробуйте обновить список или повторить позже.", e);
                    }
                });
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
    @Transactional
    public void updateBackgroundCache(boolean night) {
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
                .toList();

        if (toUpdate.isEmpty()) {
            log.debug("Фоновое обновление ({}): нечего обновлять", night ? "ночь" : "день");
            return;
        }

        log.info("Фоновое обновление ({}): к обновлению {} заездов", night ? "ночь" : "день", toUpdate.size());

        for (HeatEntity entity : toUpdate) {
            try {
                heatSyncService.syncHeat(entity.getSiteId());
                Thread.sleep(1500); // простая задержка чтобы не долбить сайт
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Ошибка фонового обновления для {}: {}", entity.getSiteId(), e.getMessage());
            }
        }
    }

    public HeatList getHeatList() {
        try {
            HeatList list = heatListParser.parseCurrentTrack();
            // Прогреваем кэш последних заездов в фоне, чтобы открытие было мгновенным.
            heatPrefetchService.prefetchRecentAsync();
            return list;
        } catch (Exception e) {
            log.error("Не удалось получить список заездов", e);
            throw new RuntimeException("Не удалось получить список заездов. Проверьте подключение к сети.", e);
        }
    }
}