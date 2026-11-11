package ninibayr.karting.service;

import ninibayr.karting.dto.HeatData;
import ninibayr.karting.entity.HeatEntity;
import ninibayr.karting.repository.HeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Чтение заезда из локального кэша.
 *
 * <p>Транзакция открыта только на время чтения и маппинга. Синхронизация
 * с сайтом выполняется уже после её закрытия, поэтому медленный HTTP-запрос
 * не держит соединение с БД и не блокирует строку заезда.</p>
 */
@Service
public class HeatReadService {

    private final HeatRepository heatRepository;
    private final HeatMapper heatMapper;

    public HeatReadService(HeatRepository heatRepository, HeatMapper heatMapper) {
        this.heatRepository = heatRepository;
        this.heatMapper = heatMapper;
    }

    @Transactional(readOnly = true)
    public Optional<CachedHeat> findCached(String heatSiteId) {
        return heatRepository.findBySiteId(heatSiteId)
                .map(heat -> new CachedHeat(
                        heatMapper.toDto(heat),
                        heat.getStints().isEmpty() && !heat.getLaps().isEmpty()));
    }

    public record CachedHeat(HeatData data, boolean needsResync) {
    }
}
