package ninibayr.karting.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import ninibayr.karting.dto.HeatData;
import ninibayr.karting.exception.SiteRateLimitedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class HeatSyncService {

    private final HeatParser heatParser;
    private final HeatPersistenceService heatPersistenceService;
    private final MeterRegistry meterRegistry;

    @Value("${karting.track.slug}")
    private String trackSlug;

    @Value("${karting.base-url}")
    private String baseUrl;

    public HeatSyncService(HeatParser heatParser,
                           HeatPersistenceService heatPersistenceService,
                           MeterRegistry meterRegistry) {
        this.heatParser = heatParser;
        this.heatPersistenceService = heatPersistenceService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Скачивает и разбирает заезд, затем сохраняет его короткой транзакцией.
     * Сетевой запрос выполняется вне транзакции БД.
     */
    public HeatData syncHeat(String heatSiteId) {
        long startedAt = System.nanoTime();
        try {
            String url = baseUrl + "/" + trackSlug + "/heats/" + heatSiteId;
            ParsedHeatData parsedData = heatParser.parse(url);
            HeatData data = heatPersistenceService.persist(heatSiteId, parsedData);
            recordSync("ok", startedAt);
            return data;
        } catch (SiteRateLimitedException e) {
            recordSync("rate_limited", startedAt);
            log.warn("Синхронизация заезда {} отложена: {}", heatSiteId, e.getMessage());
            throw e;
        } catch (Exception e) {
            recordSync("error", startedAt);
            log.error("Критическая ошибка синхронизации для {}: {}", heatSiteId, e.getMessage(), e);
            throw new RuntimeException("Не удалось получить данные для заезда " + heatSiteId, e);
        }
    }

    private void recordSync(String result, long startedAtNanos) {
        Timer.builder("karting.heat.sync")
                .description("Синхронизация заезда с сайтом (fetch + парсинг + запись в БД)")
                .tag("result", result)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);
    }
}
