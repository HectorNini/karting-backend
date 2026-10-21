package ninibayr.karting.service;

import lombok.extern.slf4j.Slf4j;
import ninibayr.karting.dto.HeatData;
import ninibayr.karting.dto.HeatList;
import ninibayr.karting.entity.HeatEntity;
import ninibayr.karting.repository.HeatRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
public class HeatService {

    private final HeatRepository heatRepository;
    private final HeatSyncService heatSyncService;
    private final HeatMapper heatMapper;
    private final HeatListParser heatListParser;

    public HeatService(HeatRepository heatRepository, HeatSyncService heatSyncService,
                       HeatMapper heatMapper, HeatListParser heatListParser) {
        this.heatRepository = heatRepository;
        this.heatSyncService = heatSyncService;
        this.heatMapper = heatMapper;
        this.heatListParser = heatListParser;
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
                .map(heatMapper::toDto)
                .orElseGet(() -> {
                    log.info("Заезд {} не найден в БД, запускаем синхронизацию...", heatSiteId);
                    return heatSyncService.syncHeat(heatSiteId);
                });
    }

    @Scheduled(fixedDelay = 15000)
    @Transactional
    public void updateBackgroundCache() {
        List<HeatEntity> allHeats = heatRepository.findAll();
        log.info("Фоновое обновление: найдено заездов в БД: {}", allHeats.size());

        for (HeatEntity entity : allHeats) {
            try {
                // Делегируем синхронизацию правильному сервису
                heatSyncService.syncHeat(entity.getSiteId());
            } catch (Exception e) {
                log.error("Ошибка фонового обновления для {}: {}", entity.getSiteId(), e.getMessage());
            }
        }
    }

    public HeatList getHeatList() throws Exception {
        return heatListParser.parseCurrentTrack();
    }
}