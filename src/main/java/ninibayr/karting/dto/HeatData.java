package ninibayr.karting.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HeatData {
    private String heatName;
    private LocalDate raceDate;
    private LocalTime raceTime;
    private List<DriverInfo> drivers;
    private List<LapRow> laps;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DriverInfo {
        private String siteId;
        private String name;
        private Integer kartNumber;
        private Integer startPosition;
        private String bestTime;
        private String avgTime;
        private String devTime;
        /** Текущая (последняя известная) позиция в гонке. */
        private String position;
        /** Лучшее время круга гонщика (Best из tfoot). */
        private Long bestLapMillis;
        /** Сколько кругов проехал гонщик. */
        private Integer totalLaps;
        /** Индекс колонки гонщика в исходной таблице. */
        private Integer columnIndex;
        /** Стинты гонщика по порядку (у одиночного стинта — одна запись). */
        private List<StintInfo> stints;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StintInfo {
        /** Номер стинта, начиная с 1. */
        private Integer number;
        private Integer kartNumber;
        private Integer lapFrom;
        private Integer lapTo;
        private Integer lapsCount;
        private Long bestLapMillis;
        private Long avgLapMillis;
        private Long devMillis;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LapRow {
        private String lapNumber;
        private List<String> results;
        private List<LapDetail> details;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LapDetail {
        private String lapTime;
        private Long lapTimeMillis;
        /** Позиция в гонке на этом круге (span.p). */
        private String position;
        /** Позиция в гонке на этом круге. */
        private String racePosition;
        /** Номер круга в гонке. */
        private Integer raceLapNumber;
        /** Номер стинта (смены карта). */
        private Integer stintIndex;
        /** Номер карта на этом круге. */
        private Integer kartNumber;
        /** Индекс колонки гонщика. */
        private Integer columnIndex;
        private String gapToLeader;
        private String gapToPrevious;
        private Boolean bestLapOverall;
        private Boolean bestLapStint;
    }
}
