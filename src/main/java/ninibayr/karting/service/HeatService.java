package ninibayr.karting.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import ninibayr.karting.dto.HeatData;
import ninibayr.karting.dto.HeatList;
import ninibayr.karting.entity.HeatEntity;
import ninibayr.karting.exception.SiteRateLimitedException;
import ninibayr.karting.repository.HeatRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class HeatService {

    /**
     * Смотрим только верхушку списка: новые заезды появляются сверху.
     * Старые дыры в БД не догоняем пачкой — они подтянутся при открытии.
     */
    private static final int NEW_HEATS_SCAN_LIMIT = 20;

    /**
     * Фоновая проверка новых заездов. Старые заезды не перечитываем:
     * это тратит квоту сайта и мешает открытию заездов из приложения.
     */
    @Value("${karting.polling.new-heats-sync-enabled:true}")
    private boolean newHeatsSyncEnabled = true;

    /** Сколько новых заездов максимум синхронизировать за один проход. */
    @Value("${karting.polling.new-heats-per-run:3}")
    private int newHeatsPerRun = 3;

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
     * Проверяет, появились ли на сайте новые заезды, и синхронизирует только их.
     *
     * <p>Список заездов уже кэшируется парсером, поэтому проверка не дёргает
     * сайт чаще допустимого. Уже сохранённые заезды не перечитываются: их данные
     * не меняются, а live-заезд обновляется отдельным сервисом после финиша.</p>
     *
     * <p>За один проход синхронизируется не больше {@code new-heats-per-run}
     * заездов, чтобы не упираться в лимит сайта. При 429 цикл сразу
     * останавливается, а общий {@link SiteRequestGate} отправляет фон на паузу.</p>
     *
     * @return сколько новых заездов удалось синхронизировать
     */
    public int syncNewHeats() {
        if (!newHeatsSyncEnabled || newHeatsPerRun <= 0) {
            log.debug("Проверка новых заездов отключена настройкой");
            return 0;
        }
        if (siteRequestGate.isBackgroundPaused()) {
            log.debug("Проверка новых заездов пропущена: сайт на паузе ещё {} с",
                    siteRequestGate.remainingBackgroundPauseSeconds());
            return 0;
        }

        HeatList list;
        try {
            list = heatListParser.parseCurrentTrack();
        } catch (SiteRateLimitedException e) {
            log.warn("Проверка новых заездов отложена: {}", e.getMessage());
            return 0;
        } catch (Exception e) {
            log.warn("Не удалось проверить новые заезды: {}", e.getMessage());
            return 0;
        }

        List<HeatList.HeatItem> recent = list.getHeats().stream()
                .limit(NEW_HEATS_SCAN_LIMIT)
                .toList();
        if (recent.isEmpty()) {
            return 0;
        }

        List<String> ids = recent.stream()
                .map(HeatList.HeatItem::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return 0;
        }

        Set<String> existing = new HashSet<>(heatRepository.findExistingSiteIds(ids));
        String liveHeatId = liveTimingService.getLiveHeatId();
        List<HeatList.HeatItem> missing = recent.stream()
                .filter(item -> !existing.contains(item.getId()))
                .filter(item -> liveHeatId == null || !liveHeatId.equals(item.getId()))
                .toList();

        if (missing.isEmpty()) {
            log.debug("Новых заездов для синхронизации нет");
            return 0;
        }

        List<HeatList.HeatItem> toSync = missing.stream()
                .limit(newHeatsPerRun)
                .toList();
        log.info("Новых заездов: {}. Синхронизируем {}: {}",
                missing.size(), toSync.size(),
                toSync.stream().map(HeatList.HeatItem::getId).toList());

        int synced = 0;
        for (HeatList.HeatItem item : toSync) {
            if (siteRequestGate.isCoolingDown() || siteRequestGate.isBackgroundPaused()) {
                log.debug("Синхронизация новых заездов остановлена: сайт на cooldown/паузе");
                break;
            }
            try {
                heatSyncService.syncHeat(item.getId());
                synced++;
            } catch (SiteRateLimitedException e) {
                log.warn("Синхронизация новых заездов остановлена: {}", e.getMessage());
                break;
            } catch (Exception e) {
                log.warn("Не удалось синхронизировать новый заезд {}: {}",
                        item.getId(), e.getMessage());
            }
        }
        return synced;
    }

    public HeatList getHeatList() {
        try {
            HeatList parsed = heatListParser.parseCurrentTrack();
            // Копируем элементы: нельзя менять список, который лежит в кэше
            // парсера, иначе скрытый live-заезд или флаг synced «залипнут».
            List<HeatList.HeatItem> items = parsed.getHeats().stream()
                    .map(HeatList.HeatItem::copy)
                    .collect(Collectors.toCollection(ArrayList::new));
            HeatList result = new HeatList(parsed.getTrackName(), items);

            String liveHeatId = liveTimingService.getLiveHeatId();
            if (liveHeatId != null) {
                // Сайт уже показывает текущий заезд в общем списке, но он ещё
                // не завершён. Показываем его только после окончания сессии.
                List<HeatList.HeatItem> visible = result.getHeats().stream()
                        .filter(item -> !liveHeatId.equals(item.getId()))
                        .toList();
                if (visible.size() != result.getHeats().size()) {
                    log.debug("Скрываем текущий live-заезд {} из общего списка", liveHeatId);
                    result.setHeats(new ArrayList<>(visible));
                }
            }

            markSynced(result.getHeats());

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

    /** Проставляет флаг «есть ли подробные данные в БД» одним запросом. */
    private void markSynced(List<HeatList.HeatItem> items) {
        if (items.isEmpty()) {
            return;
        }
        List<String> ids = items.stream()
                .map(HeatList.HeatItem::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        try {
            Set<String> existing = new HashSet<>(heatRepository.findExistingSiteIds(ids));
            for (HeatList.HeatItem item : items) {
                item.setSynced(existing.contains(item.getId()));
            }
        } catch (Exception e) {
            // Список должен открываться даже при проблемах с БД: лучше показать
            // заезды без отметки, чем отдать 500 на весь экран.
            log.warn("Не удалось определить синхронизированные заезды: {}", e.getMessage());
        }
    }
}
