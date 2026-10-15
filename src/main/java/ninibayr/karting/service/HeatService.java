package ninibayr.karting.service;

import lombok.extern.slf4j.Slf4j;
import ninibayr.karting.dto.HeatData;
import ninibayr.karting.dto.HeatList;
import ninibayr.karting.entity.HeatEntity;
import ninibayr.karting.repository.HeatRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class HeatService {

    private final HeatRepository heatRepository;
    private final HeatSyncService heatSyncService;
    private final HeatMapper heatMapper;

    public HeatService(HeatRepository heatRepository, HeatSyncService heatSyncService, HeatMapper heatMapper) {
        this.heatRepository = heatRepository;
        this.heatSyncService = heatSyncService;
        this.heatMapper = heatMapper;
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

    /**
     * Парсинг списка заездов (остается здесь, так как это чисто read-операция без сложной логики БД)
     */
    public HeatList getHeatList() throws Exception {
        String url = "https://timing.batyrshin.name/tracks/narvskaya/heats";
        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(5000)
                .get();

        List<HeatList.HeatItem> heats = new ArrayList<>();
        String currentDate = null;
        Elements items = doc.select(".list-group-item");

        for (Element item : items) {
            if (item.hasClass("heat-list-date")) {
                Element dateElement = item.selectFirst("b");
                if (dateElement != null) currentDate = dateElement.text().trim();
                continue;
            }

            Element link = item.selectFirst("a[href*='/tracks/narvskaya/heats/']");
            if (link == null) continue;

            String id = link.attr("href").substring(link.attr("href").lastIndexOf('/') + 1);
            if (!id.matches("\\d+")) continue;

            HeatList.HeatItem heatItem = new HeatList.HeatItem();
            heatItem.setId(id);
            heatItem.setName(link.text().trim());
            heatItem.setDate(currentDate);

            Element small = item.selectFirst("small");
            if (small != null) heatItem.setTime(small.text().trim());

            Element resultDiv = item.selectFirst(".col-12.mb-1.small");
            if (resultDiv != null) heatItem.setTopResult(resultDiv.text().trim());

            if (!heatItem.getName().isEmpty() && heats.stream().noneMatch(h -> h.getId().equals(heatItem.getId()))) {
                heats.add(heatItem);
            }
        }
        return new HeatList("Narvskaya", heats);
    }
}