package ninibayr.karting.service;

import ninibayr.karting.dto.HeatData;
import ninibayr.karting.entity.*;
import ninibayr.karting.repository.DriverRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class HeatMapper {

    private final DriverRepository driverRepository;

    public HeatMapper(DriverRepository driverRepository) {
        this.driverRepository = driverRepository;
    }

    /**
     * Обновляет существующую сущность новыми данными с сайта.
     */
    public void updateEntity(HeatEntity heatEntity, ParsedHeatData parsed, TrackEntity track) {
        heatEntity.setName(parsed.getName());
        heatEntity.setLastUpdated(LocalDateTime.now());

        // ВАЖНО: Очищаем старые круги.
        // Благодаря orphanRemoval = true в HeatEntity, Hibernate удалит их из БД,
        // освобождая место для актуальных данных.
        heatEntity.getLaps().clear();

        for (ParsedHeatData.ParsedLap parsedLap : parsed.getLaps()) {
            // Находим или создаем гонщика
            DriverEntity driver = driverRepository.findBySiteIdAndTrack(parsedLap.getDriverSiteId(), track)
                    .orElseGet(() -> {
                        String driverName = parsed.getDrivers().stream()
                                .filter(d -> d.getSiteId().equals(parsedLap.getDriverSiteId()))
                                .map(ParsedHeatData.ParsedDriver::getName)
                                .findFirst()
                                .orElse("Unknown");

                        DriverEntity newDriver = new DriverEntity();
                        newDriver.setSiteId(parsedLap.getDriverSiteId());
                        newDriver.setName(driverName);
                        newDriver.setTrack(track);
                        return driverRepository.save(newDriver);
                    });

            LapEntity lap = new LapEntity();
            lap.setHeat(heatEntity); // Привязываем к ОБНОВЛЯЕМОМУ заезду
            lap.setDriver(driver);

            parsed.getDrivers().stream()
                    .filter(d -> d.getSiteId().equals(parsedLap.getDriverSiteId()))
                    .findFirst()
                    .ifPresent(d -> lap.setKartNumber(d.getKartNumber()));

            lap.setLapNumber(parsedLap.getLapNumber());
            lap.setLapTime(parsedLap.getLapTime());
            lap.setPosition(parsedLap.getPosition());

            heatEntity.getLaps().add(lap);
        }
    }

    /**
     * Преобразует Entity из БД в DTO для отправки во Flutter (без изменений)
     */
    public HeatData toDto(HeatEntity heat) {
        List<String> driverNames = heat.getLaps().stream()
                .map(lap -> lap.getDriver().getName())
                .distinct()
                .collect(Collectors.toList());

        List<Integer> uniqueLapNumbers = heat.getLaps().stream()
                .map(LapEntity::getLapNumber)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        List<HeatData.LapRow> dtoLaps = new ArrayList<>();
        for (Integer lapNum : uniqueLapNumbers) {
            HeatData.LapRow row = new HeatData.LapRow();
            row.setLapNumber(String.valueOf(lapNum));

            List<String> results = heat.getLaps().stream()
                    .filter(l -> l.getLapNumber().equals(lapNum))
                    .map(l -> {
                        String res = l.getLapTime();
                        if (l.getPosition() != null && !l.getPosition().isEmpty()) {
                            res += " " + l.getPosition();
                        }
                        return res;
                    })
                    .collect(Collectors.toList());

            row.setResults(results);
            dtoLaps.add(row);
        }

        return new HeatData(heat.getName(), driverNames, dtoLaps);
    }
}