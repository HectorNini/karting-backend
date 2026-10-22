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
            lap.setRaceLapNumber(parsedLap.getRaceLapNumber());
            lap.setStintIndex(parsedLap.getStintIndex());
            lap.setLapTime(parsedLap.getLapTime());
            lap.setLapTimeMillis(parsedLap.getLapTimeMillis());
            lap.setPosition(parsedLap.getPosition());
            lap.setRacePosition(parsedLap.getRacePosition());
            lap.setGapToLeader(parsedLap.getGapToLeader());
            lap.setGapToPrevious(parsedLap.getGapToPrevious());
            lap.setBestLapOverall(parsedLap.isBestLapOverall());
            lap.setBestLapStint(parsedLap.isBestLapStint());
            heatEntity.getLaps().add(lap);
        }
    }

    public HeatData toDto(HeatEntity heat) {
        return toDto(heat, null);
    }

    public HeatData toDto(HeatEntity heat, ParsedHeatData parsed) {
        Map<String, ParsedHeatData.ParsedDriver> parsedDriversBySiteId = new LinkedHashMap<>();
        if (parsed != null && parsed.getDrivers() != null) {
            for (ParsedHeatData.ParsedDriver pd : parsed.getDrivers()) {
                if (pd.getSiteId() != null) {
                    parsedDriversBySiteId.put(pd.getSiteId(), pd);
                }
            }
        }

        // Группируем круги по колонке (гонщику) и сортируем по номеру круга в гонке.
        Map<Integer, List<LapEntity>> lapsByColumn = new LinkedHashMap<>();
        heat.getLaps().stream()
                .filter(l -> l.getColumnIndex() != null)
                .sorted(Comparator.comparing(LapEntity::getColumnIndex))
                .forEach(l -> lapsByColumn
                        .computeIfAbsent(l.getColumnIndex(), k -> new ArrayList<>())
                        .add(l));

        List<HeatData.DriverInfo> drivers = new ArrayList<>();
        for (Map.Entry<Integer, List<LapEntity>> entry : lapsByColumn.entrySet()) {
            List<LapEntity> driverLaps = entry.getValue();
            driverLaps.sort(Comparator.comparing(
                    l -> l.getRaceLapNumber() != null ? l.getRaceLapNumber() : Integer.MAX_VALUE));

            LapEntity firstLap = driverLaps.get(0);
            HeatData.DriverInfo info = toDriverInfo(firstLap, parsedDriversBySiteId, entry.getKey());

            // Последний круг гонщика (с непустым временем) определяет текущую позицию.
            LapEntity lastLap = driverLaps.stream()
                    .filter(l -> l.getLapTimeMillis() != null)
                    .reduce((a, b) -> b)
                    .orElse(firstLap);
            info.setPosition(lastLap.getRacePosition());
            info.setTotalLaps((int) driverLaps.stream()
                    .filter(l -> l.getLapTimeMillis() != null)
                    .count());
            Long best = driverLaps.stream()
                    .map(LapEntity::getLapTimeMillis)
                    .filter(Objects::nonNull)
                    .min(Long::compareTo)
                    .orElse(null);
            info.setBestLapMillis(best);

            drivers.add(info);
        }

        // Сортируем гонщиков по текущей позиции в гонке.
        drivers.sort(Comparator.comparingInt(d -> parsePosition(d.getPosition())));

        // Строки по номерам кругов в гонке.
        List<Integer> raceLapNumbers = heat.getLaps().stream()
                .map(LapEntity::getRaceLapNumber)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        List<HeatData.LapRow> dtoLaps = new ArrayList<>();
        for (Integer raceLap : raceLapNumbers) {
            HeatData.LapRow row = new HeatData.LapRow();
            row.setLapNumber(String.valueOf(raceLap));
            List<LapEntity> lapsForRow = heat.getLaps().stream()
                    .filter(l -> raceLap.equals(l.getRaceLapNumber()))
                    .sorted(Comparator.comparing(l -> columnIndexOrMax(l.getColumnIndex())))
                    .collect(Collectors.toList());
            row.setResults(lapsForRow.stream()
                    .map(this::formatResult)
                    .collect(Collectors.toList()));
            row.setDetails(lapsForRow.stream()
                    .map(this::toLapDetail)
                    .collect(Collectors.toList()));
            dtoLaps.add(row);
        }

        return new HeatData(heat.getName(), heat.getRaceDate(), heat.getRaceTime(), drivers, dtoLaps);
    }

    private static int parsePosition(String position) {
        if (position == null) {
            return Integer.MAX_VALUE;
        }
        String cleaned = position.replaceAll("[^0-9]", "");
        if (cleaned.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
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

    private HeatData.DriverInfo toDriverInfo(LapEntity lap, Map<String, ParsedHeatData.ParsedDriver> parsedDriversBySiteId, Integer columnIndex) {
        DriverEntity driver = lap.getDriver();
        String siteId = driver != null ? driver.getSiteId() : null;
        ParsedHeatData.ParsedDriver pd = siteId != null && parsedDriversBySiteId != null ? parsedDriversBySiteId.get(siteId) : null;
        return new HeatData.DriverInfo(
                siteId,
                driver != null ? driver.getName() : (pd != null ? pd.getName() : null),
                lap.getKartNumber(),
                pd != null ? pd.getStartPosition() : null,
                pd != null ? pd.getBestTime() : null,
                pd != null ? pd.getAvgTime() : null,
                pd != null ? pd.getDevTime() : null,
                null,
                null,
                null,
                columnIndex
        );
    }

    private String formatResult(LapEntity lap) {
        String res = lap.getLapTime() != null ? lap.getLapTime() : "";
        String pos = lap.getRacePosition() != null ? lap.getRacePosition() : lap.getPosition();
        if (pos != null && !pos.isEmpty()) {
            res = res.isEmpty() ? pos : res + " " + pos;
        }
        return res;
    }

    private HeatData.LapDetail toLapDetail(LapEntity lap) {
        HeatData.LapDetail detail = new HeatData.LapDetail();
        detail.setLapTime(lap.getLapTime());
        detail.setLapTimeMillis(lap.getLapTimeMillis());
        detail.setPosition(lap.getPosition());
        detail.setRacePosition(lap.getRacePosition());
        detail.setRaceLapNumber(lap.getRaceLapNumber());
        detail.setStintIndex(lap.getStintIndex());
        detail.setKartNumber(lap.getKartNumber());
        detail.setColumnIndex(lap.getColumnIndex());
        detail.setGapToLeader(lap.getGapToLeader());
        detail.setGapToPrevious(lap.getGapToPrevious());
        detail.setBestLapOverall(lap.getBestLapOverall());
        detail.setBestLapStint(lap.getBestLapStint());
        return detail;
    }

    private static int columnIndexOrMax(Integer columnIndex) {
        return columnIndex != null ? columnIndex : Integer.MAX_VALUE;
    }
}
