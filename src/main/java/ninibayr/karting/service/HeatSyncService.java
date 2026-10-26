package ninibayr.karting.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import ninibayr.karting.dto.HeatData;
import ninibayr.karting.entity.HeatEntity;
import ninibayr.karting.entity.TrackEntity;
import ninibayr.karting.repository.HeatRepository;
import ninibayr.karting.repository.TrackRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class HeatSyncService {

    private final TrackRepository trackRepository;
    private final HeatRepository heatRepository;
    private final HeatParser heatParser;
    private final HeatMapper heatMapper;
    private final MeterRegistry meterRegistry;

    @Value("${karting.track.slug}")
    private String trackSlug;

    @Value("${karting.base-url}")
    private String baseUrl;

    public HeatSyncService(TrackRepository trackRepository, HeatRepository heatRepository,
                           HeatParser heatParser, HeatMapper heatMapper,
                           MeterRegistry meterRegistry) {
        this.trackRepository = trackRepository;
        this.heatRepository = heatRepository;
        this.heatParser = heatParser;
        this.heatMapper = heatMapper;
        this.meterRegistry = meterRegistry;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public HeatData syncHeat(String heatSiteId) {
        long startedAt = System.nanoTime();
        try {
            return doSyncHeat(heatSiteId, startedAt);
        } catch (RuntimeException e) {
            recordSync("error", startedAt);
            throw e;
        }
    }

    private HeatData doSyncHeat(String heatSiteId, long startedAt) {
        try {
            TrackEntity track = trackRepository.findBySlug(trackSlug)
                    .orElseThrow(() -> new RuntimeException("Трасса '" + trackSlug + "' не найдена в БД"));

            // 1. ПАТТЕРН UPSERT: Находим существующий или готовим новый.
            // Блокируем строку заезда, чтобы две синхронизации одного заезда
            // (шедулер + открытие в приложении) не шли параллельно и не ловили
            // конфликт уникальности стинтов.
            HeatEntity heatEntity = heatRepository.findBySiteIdForUpdate(heatSiteId).orElseGet(() -> {
                HeatEntity newHeat = new HeatEntity();
                newHeat.setSiteId(heatSiteId);
                newHeat.setTrack(track);
                return newHeat;
            });

            String url = baseUrl + "/" + trackSlug + "/heats/" + heatSiteId;

            // 2. Парсим свежие данные
            ParsedHeatData parsedData = heatParser.parse(url);

            // 3. Обновляем сущность (внутри очистятся старые круги и добавятся новые)
            heatMapper.updateEntity(heatEntity, parsedData, track);

            // 4. Сохраняем. Hibernate увидит, что у heatEntity есть ID (если он был найден),
            // и выполнит UPDATE, а не INSERT.
            heatRepository.save(heatEntity);

            log.info("Успешно синхронизировано (обновлено): {} (ID: {}), кругов: {}",
                    heatEntity.getName(), heatSiteId, heatEntity.getLaps().size());

            recordSync("ok", startedAt);
            return heatMapper.toDto(heatEntity);

        } catch (Exception e) {
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
