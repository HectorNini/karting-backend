package ninibayr.karting.service;

import ninibayr.karting.dto.HeatData;
import ninibayr.karting.entity.DriverEntity;
import ninibayr.karting.entity.HeatEntity;
import ninibayr.karting.entity.HeatStintEntity;
import ninibayr.karting.entity.LapEntity;
import ninibayr.karting.entity.TrackEntity;
import ninibayr.karting.repository.DriverRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

@Component
public class HeatMapper {

    private static final Pattern NON_DIGITS = Pattern.compile("[^0-9]");

    /** Круги внутри одного гонщика: сначала по номеру круга гонки. */
    private static final Comparator<LapEntity> LAP_BY_RACE_NUMBER = Comparator.comparing(
            lap -> lap.getRaceLapNumber() != null ? lap.getRaceLapNumber() : Integer.MAX_VALUE);

    /** Гонщики: по последней позиции, затем по числу кругов. */
    private static final Comparator<HeatData.DriverInfo> DRIVER_ORDER = Comparator
            .comparingInt((HeatData.DriverInfo d) -> parsePosition(d.getPosition()))
            .thenComparing(Comparator.comparingInt(
                    (HeatData.DriverInfo d) -> d.getTotalLaps() == null ? 0 : d.getTotalLaps()).reversed());

    private final DriverRepository driverRepository;

    @PersistenceContext
    private EntityManager entityManager;

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
        heatEntity.getStints().clear();
        // Явно сбрасываем удаление старых кругов/стинтов до вставки новых:
        // иначе Hibernate вставляет новые стинты раньше orphan-removal и ловит
        // unique constraint (heat_id, driver_id, stint_number).
        entityManager.flush();

        for (ParsedHeatData.ParsedLap parsedLap : parsed.getLaps()) {
            DriverEntity driver = driversBySiteId.get(parsedLap.getDriverSiteId());
            if (driver == null) {
                continue;
            }

            LapEntity lap = new LapEntity();
            lap.setHeat(heatEntity);
            lap.setDriver(driver);
            lap.setColumnIndex(parsedLap.getColumnIndex());
            lap.setKartNumber(parsedLap.getKartNumber());
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

        for (ParsedHeatData.ParsedDriver parsedDriver : parsed.getDrivers()) {
            DriverEntity driver = driversBySiteId.get(parsedDriver.getSiteId());
            if (driver == null) {
                continue;
            }
            for (ParsedHeatData.ParsedStint parsedStint : parsedDriver.getStints()) {
                HeatStintEntity stint = new HeatStintEntity();
                stint.setHeat(heatEntity);
                stint.setDriver(driver);
                stint.setStintNumber(parsedStint.getNumber());
                stint.setKartNumber(parsedStint.getKartNumber());
                stint.setLapFrom(parsedStint.getLapFrom());
                stint.setLapTo(parsedStint.getLapTo());
                stint.setLapsCount(parsedStint.getLapsCount());
                stint.setBestLapMillis(parsedStint.getBestLapMillis());
                stint.setAvgLapMillis(parsedStint.getAvgLapMillis());
                stint.setDevMillis(parsedStint.getDevMillis());
                heatEntity.getStints().add(stint);
            }
        }
    }

    public HeatData toDto(HeatEntity heat) {
        List<LapEntity> allLaps = heat.getLaps() == null ? List.of() : heat.getLaps();

        // Группируем круги по колонке (гонщику) и по номеру круга гонки —
        // по одному проходу вместо N filter() на каждый круг/гонщика.
        Map<Integer, List<LapEntity>> lapsByColumn = new TreeMap<>();
        Map<Integer, List<LapEntity>> lapsByRaceLap = new TreeMap<>();
        for (LapEntity lap : allLaps) {
            if (lap.getColumnIndex() != null) {
                lapsByColumn.computeIfAbsent(lap.getColumnIndex(), k -> new ArrayList<>()).add(lap);
            }
            if (lap.getRaceLapNumber() != null) {
                lapsByRaceLap.computeIfAbsent(lap.getRaceLapNumber(), k -> new ArrayList<>()).add(lap);
            }
        }
        lapsByColumn.values().forEach(list -> list.sort(LAP_BY_RACE_NUMBER));

        Map<String, List<HeatData.StintInfo>> stintsByDriver = groupStints(heat);

        List<HeatData.DriverInfo> drivers = new ArrayList<>();
        for (Map.Entry<Integer, List<LapEntity>> entry : lapsByColumn.entrySet()) {
            List<LapEntity> driverLaps = entry.getValue();
            LapEntity firstLap = driverLaps.get(0);

            String finalPosition = null;
            for (LapEntity lap : driverLaps) {
                if (lap.getRacePosition() != null) {
                    finalPosition = lap.getRacePosition();
                }
            }

            HeatData.DriverInfo info = toDriverInfo(
                    firstLap, entry.getKey(), finalPosition,
                    stintsByDriver.getOrDefault(driverKey(firstLap.getDriver()), List.of()));

            int totalLaps = 0;
            Long best = null;
            for (LapEntity lap : driverLaps) {
                if (lap.getLapTimeMillis() == null) {
                    continue;
                }
                totalLaps++;
                if (best == null || lap.getLapTimeMillis() < best) {
                    best = lap.getLapTimeMillis();
                }
            }
            info.setTotalLaps(totalLaps);
            info.setBestLapMillis(best);

            drivers.add(info);
        }

        // Строки по номерам кругов в гонке.
        List<HeatData.LapRow> dtoLaps = new ArrayList<>();
        for (Map.Entry<Integer, List<LapEntity>> entry : lapsByRaceLap.entrySet()) {
            List<LapEntity> lapsForRow = entry.getValue();
            lapsForRow.sort(Comparator.comparingInt(
                    lap -> columnIndexOrMax(lap.getColumnIndex())));

            HeatData.LapRow row = new HeatData.LapRow();
            row.setLapNumber(String.valueOf(entry.getKey()));
            row.setResults(lapsForRow.stream().map(this::formatResult).toList());
            row.setDetails(lapsForRow.stream().map(this::toLapDetail).toList());
            dtoLaps.add(row);
        }

        // Позиции на кругах и итоговые места — ровно те, что отдал сайт.
        // По времени считаются только отдельные режимы в приложении (lapTime/bestLap),
        // backend ничего не «догоняет» по разрывам.
        applyFinalPositions(drivers, dtoLaps);

        return new HeatData(heat.getName(), heat.getRaceDate(), heat.getRaceTime(), drivers, dtoLaps);
    }

    private Map<String, List<HeatData.StintInfo>> groupStints(HeatEntity heat) {
        Map<String, List<HeatData.StintInfo>> result = new HashMap<>();
        if (heat.getStints() == null) {
            return result;
        }
        for (HeatStintEntity stint : heat.getStints()) {
            String key = driverKey(stint.getDriver());
            if (key == null) {
                continue;
            }
            result.computeIfAbsent(key, k -> new ArrayList<>()).add(new HeatData.StintInfo(
                    stint.getStintNumber(),
                    stint.getKartNumber(),
                    stint.getLapFrom(),
                    stint.getLapTo(),
                    stint.getLapsCount(),
                    stint.getBestLapMillis(),
                    stint.getAvgLapMillis(),
                    stint.getDevMillis()));
        }
        Comparator<HeatData.StintInfo> byNumber = Comparator.comparing(
                HeatData.StintInfo::getNumber,
                Comparator.nullsLast(Integer::compareTo));
        result.values().forEach(list -> list.sort(byNumber));
        return result;
    }

    private void applyFinalPositions(List<HeatData.DriverInfo> drivers,
                                     List<HeatData.LapRow> rows) {
        Map<Integer, String> lastPositionByColumn = new HashMap<>();
        for (HeatData.LapRow row : rows) {
            if (row.getDetails() == null) {
                continue;
            }
            for (HeatData.LapDetail detail : row.getDetails()) {
                if (detail.getColumnIndex() != null && detail.getRacePosition() != null) {
                    lastPositionByColumn.put(detail.getColumnIndex(), detail.getRacePosition());
                }
            }
        }

        for (HeatData.DriverInfo driver : drivers) {
            String position = lastPositionByColumn.get(driver.getColumnIndex());
            if (position != null) {
                driver.setPosition(position);
            }
        }

        drivers.sort(DRIVER_ORDER);
    }

    private static int parsePosition(String position) {
        if (position == null) {
            return Integer.MAX_VALUE;
        }
        String cleaned = NON_DIGITS.matcher(position).replaceAll("");
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

    private HeatData.DriverInfo toDriverInfo(LapEntity lap,
                                             Integer columnIndex,
                                             String position,
                                             List<HeatData.StintInfo> stints) {
        DriverEntity driver = lap.getDriver();
        return new HeatData.DriverInfo(
                driver != null ? driver.getSiteId() : null,
                driver != null ? driver.getName() : null,
                lap.getKartNumber(),
                null,
                null,
                null,
                null,
                position,
                null,
                null,
                columnIndex,
                stints
        );
    }

    private static String driverKey(DriverEntity driver) {
        if (driver == null) {
            return null;
        }
        if (driver.getId() != null) {
            return "id:" + driver.getId();
        }
        if (driver.getSiteId() != null) {
            return "site:" + driver.getSiteId();
        }
        return null;
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
