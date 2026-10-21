package ninibayr.karting.service;

import ninibayr.karting.dto.HeatData;
import ninibayr.karting.entity.DriverEntity;
import ninibayr.karting.entity.HeatEntity;
import ninibayr.karting.entity.LapEntity;
import ninibayr.karting.entity.TrackEntity;
import ninibayr.karting.repository.DriverRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class HeatMapper {

    private final DriverRepository driverRepository;

    public HeatMapper(DriverRepository driverRepository) {
        this.driverRepository = driverRepository;
    }

    public void updateEntity(HeatEntity heatEntity, ParsedHeatData parsed, TrackEntity track) {
        heatEntity.setName(parsed.getName());
        heatEntity.setRaceDate(parsed.getRaceDate());
        heatEntity.setRaceTime(parsed.getRaceTime());
        heatEntity.setLastUpdated(LocalDateTime.now());

        Map<String, DriverEntity> driversBySiteId = resolveDrivers(parsed, track);

        heatEntity.getLaps().clear();

        for (ParsedHeatData.ParsedLap parsedLap : parsed.getLaps()) {
            DriverEntity driver = driversBySiteId.get(parsedLap.getDriverSiteId());
            if (driver == null) {
                continue;
            }

            LapEntity lap = new LapEntity();
            lap.setHeat(heatEntity);
            lap.setDriver(driver);
            lap.setColumnIndex(parsedLap.getColumnIndex());
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

    public HeatData toDto(HeatEntity heat) {
        Map<Integer, HeatData.DriverInfo> driversByColumn = new LinkedHashMap<>();
        heat.getLaps().stream()
                .sorted(Comparator.comparing(lap -> columnIndexOrMax(lap.getColumnIndex())))
                .forEach(lap -> {
                    int column = columnIndexOrMax(lap.getColumnIndex());
                    driversByColumn.putIfAbsent(column, toDriverInfo(lap));
                });

        List<HeatData.DriverInfo> drivers = new ArrayList<>(driversByColumn.values());

        List<Integer> uniqueLapNumbers = heat.getLaps().stream()
                .map(LapEntity::getLapNumber)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        List<HeatData.LapRow> dtoLaps = new ArrayList<>();
        for (Integer lapNum : uniqueLapNumbers) {
            HeatData.LapRow row = new HeatData.LapRow();
            row.setLapNumber(String.valueOf(lapNum));
            List<String> results = heat.getLaps().stream()
                    .filter(l -> lapNum.equals(l.getLapNumber()))
                    .sorted(Comparator.comparing(lap -> columnIndexOrMax(lap.getColumnIndex())))
                    .map(this::formatResult)
                    .collect(Collectors.toList());
            row.setResults(results);
            dtoLaps.add(row);
        }

        return new HeatData(heat.getName(), heat.getRaceDate(), heat.getRaceTime(), drivers, dtoLaps);
    }

    private Map<String, DriverEntity> resolveDrivers(ParsedHeatData parsed, TrackEntity track) {
        Map<String, DriverEntity> driversBySiteId = new LinkedHashMap<>();
        for (ParsedHeatData.ParsedDriver parsedDriver : parsed.getDrivers()) {
            DriverEntity driver = driverRepository.findBySiteIdAndTrack(parsedDriver.getSiteId(), track)
                    .orElseGet(DriverEntity::new);

            driver.setSiteId(parsedDriver.getSiteId());
            driver.setName(parsedDriver.getName());
            driver.setTrack(track);
            driversBySiteId.put(parsedDriver.getSiteId(), driverRepository.save(driver));
        }
        return driversBySiteId;
    }

    private HeatData.DriverInfo toDriverInfo(LapEntity lap) {
        DriverEntity driver = lap.getDriver();
        return new HeatData.DriverInfo(
                driver != null ? driver.getSiteId() : null,
                driver != null ? driver.getName() : null,
                lap.getKartNumber()
        );
    }

    private String formatResult(LapEntity lap) {
        String res = lap.getLapTime() != null ? lap.getLapTime() : "";
        if (lap.getPosition() != null && !lap.getPosition().isEmpty()) {
            res = res.isEmpty() ? lap.getPosition() : res + " " + lap.getPosition();
        }
        return res;
    }

    private static int columnIndexOrMax(Integer columnIndex) {
        return columnIndex != null ? columnIndex : Integer.MAX_VALUE;
    }
}
