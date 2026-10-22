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

    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void updateBackgroundCache() {
        List<HeatEntity> allHeats = heatRepository.findAll();
        if (allHeats.isEmpty()) {
            return;
        }
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusHours(6);
        List<HeatEntity> toUpdate = allHeats.stream()
                .filter(h -> {
                    if (h.getRaceDate() != null && h.getRaceDate().isEqual(today)) {
                        return true;
                    }
                    if (h.getLastUpdated() != null && h.getLastUpdated().isAfter(cutoff)) {
                        return true;
                    }
                    // если нет дат - обновляем только очень недавно добавленные
                    if (h.getRaceDate() == null) {
                        return h.getLastUpdated() != null && h.getLastUpdated().isAfter(java.time.LocalDateTime.now().minusHours(2));
                    }
                    return false;
                })
                .collect(java.util.stream.Collectors.toList());

        log.info("Фоновое обновление: всего в БД: {}, к обновлению (активные/недавние): {}", allHeats.size(), toUpdate.size());

        for (HeatEntity entity : toUpdate) {
            try {
                heatSyncService.syncHeat(entity.getSiteId());
                Thread.sleep(2000); // простая задержка чтобы не долбить сайт
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Ошибка фонового обновления для {}: {}", entity.getSiteId(), e.getMessage());
            }
        }
    }

    public HeatList getHeatList() throws Exception {
        return heatListParser.parseCurrentTrack();
    }
}