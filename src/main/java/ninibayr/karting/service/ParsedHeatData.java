package ninibayr.karting.service;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** Чистые данные с сайта тайминга, без привязки к БД. */
@Data
class ParsedHeatData {
    private String name;
    private LocalDate raceDate;
    private LocalTime raceTime;
    private String weather;
    private List<ParsedDriver> drivers = new ArrayList<>();
    private List<ParsedLap> laps = new ArrayList<>();

    @Data
    static class ParsedDriver {
        private String siteId;
        private String name;
        private Integer kartNumber;
        private Integer startPosition;
        private int columnIndex;
        private String bestTime;
        private String avgTime;
        private String devTime;
    }

    @Data
    static class ParsedLap {
        /** Номер круга внутри стинта (как в span данных сайта: 1..N). */
        private Integer lapNumber;
        /** Номер круга в гонке (с учётом предыдущих стинтов). */
        private Integer raceLapNumber;
        /** Идентификатор стинта (номер карта + порядковый номер смены). */
        private Integer stintIndex;
        private String driverSiteId;
        private String lapTime;
        private Long lapTimeMillis;
        /** Позиция внутри стинта (span.p). */
        private String position;
        /** Позиция в гонке на соответствующем круге. */
        private String racePosition;
        private int columnIndex;
        private String gapToLeader;
        private String gapToPrevious;
        private boolean bestLapOverall;
        private boolean bestLapStint;
    }
}
