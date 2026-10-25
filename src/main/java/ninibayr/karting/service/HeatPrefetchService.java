package ninibayr.karting.service;

import lombok.extern.slf4j.Slf4j;
import ninibayr.karting.dto.HeatList;
import ninibayr.karting.entity.HeatEntity;
import ninibayr.karting.repository.HeatRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Фоновый «прогрев» кэша: заранее подтягивает с сайта заезды из списка,
 * чтобы открытие заезда в приложении не ждало синхронного парсинга.
 *
 * Работает в одном потоке и с паузами, чтобы не нагружать внешний сайт.
 */
@Service
@Slf4j
public class HeatPrefetchService {

    /** Сколько последних заездов прогревать. */
    private static final int PREFETCH_LIMIT = 30;
    /** Пауза между запросами к сайту, мс. */
    private static final long DELAY_MS = 700;

    private final HeatRepository heatRepository;
    private final HeatSyncService heatSyncService;
    private final HeatListParser heatListParser;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "heat-prefetch");
        t.setDaemon(true);
        return t;
    });

    /** Уже поставленные в очередь / обработанные id, чтобы не дублировать работу. */
    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();

    public HeatPrefetchService(HeatRepository heatRepository,
                               HeatSyncService heatSyncService,
                               HeatListParser heatListParser) {
        this.heatRepository = heatRepository;
        this.heatSyncService = heatSyncService;
        this.heatListParser = heatListParser;
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
        if (!scheduled.isEmpty()) {
            return; // прогрев уже запущен
        }
        executor.submit(this::prefetchRecent);
    }

    private void prefetchRecent() {
        try {
            HeatList list = heatListParser.parseCurrentTrack();
            List<HeatList.HeatItem> items = list.getHeats().stream()
                    .limit(PREFETCH_LIMIT)
                    .toList();
            log.info("Фоновый прогрев: {} заездов", items.size());

            for (HeatList.HeatItem item : items) {
                String id = item.getId();
                if (!scheduled.add(id)) {
                    continue;
                }
                try {
                    // Уже есть в БД — не трогаем, данные подтянутся фоновой синхронизацией.
                    if (heatRepository.findBySiteId(id).isPresent()) {
                        continue;
                    }
                    heatSyncService.syncHeat(id);
                    Thread.sleep(DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log.warn("Прогрев заезда {} не удался: {}", id, e.getMessage());
                }
            }
            log.info("Фоновый прогрев завершён");
        } catch (Exception e) {
            log.warn("Фоновый прогрев не удался: {}", e.getMessage());
            scheduled.clear(); // разрешаем повторить позже
        }
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
        executor.submit(() -> {
            try {
                heatSyncService.syncHeat(heatSiteId);
            } catch (Exception e) {
                log.warn("Прогрев заезда {} не удался: {}", heatSiteId, e.getMessage());
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
