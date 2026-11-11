package ninibayr.karting.service;

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

/**
 * Короткая транзакция записи разобранного заезда.
 *
 * <p>Вынесена отдельно от {@link HeatSyncService}, чтобы HTTP-запрос к сайту
 * не выполнялся внутри транзакции БД.</p>
 */
@Service
@Slf4j
public class HeatPersistenceService {

    private final TrackRepository trackRepository;
    private final HeatRepository heatRepository;
    private final HeatMapper heatMapper;

    @Value("${karting.track.slug}")
    private String trackSlug;

    public HeatPersistenceService(TrackRepository trackRepository,
                                  HeatRepository heatRepository,
                                  HeatMapper heatMapper) {
        this.trackRepository = trackRepository;
        this.heatRepository = heatRepository;
        this.heatMapper = heatMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public HeatData persist(String heatSiteId, ParsedHeatData parsedData) {
        TrackEntity track = trackRepository.findBySlug(trackSlug)
                .orElseThrow(() -> new RuntimeException("Трасса '" + trackSlug + "' не найдена в БД"));

        // Блокируем строку заезда, чтобы две синхронизации одного заезда
        // (шедулер + открытие в приложении) не шли параллельно и не ловили
        // конфликт уникальности стинтов.
        HeatEntity heatEntity = heatRepository.findBySiteIdForUpdate(heatSiteId).orElseGet(() -> {
            HeatEntity newHeat = new HeatEntity();
            newHeat.setSiteId(heatSiteId);
            newHeat.setTrack(track);
            return newHeat;
        });

        heatMapper.updateEntity(heatEntity, parsedData, track);
        heatRepository.save(heatEntity);

        log.info("Успешно синхронизировано (обновлено): {} (ID: {}), кругов: {}",
                heatEntity.getName(), heatSiteId, heatEntity.getLaps().size());

        return heatMapper.toDto(heatEntity);
    }
}
