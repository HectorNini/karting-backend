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
    private List<ParsedDriver> drivers = new ArrayList<>();
    private List<ParsedLap> laps = new ArrayList<>();

    @Data
    static class ParsedDriver {
        private String siteId;
        private String name;
        private Integer kartNumber;
        private int columnIndex;
    }

    @Data
    static class ParsedLap {
        private Integer lapNumber;
        private String driverSiteId;
        private String lapTime;
        private String position;
        private int columnIndex;
    }
}
