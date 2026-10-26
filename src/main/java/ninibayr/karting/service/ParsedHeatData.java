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
        /** Стартовый карт (строка «Kart» в шапке). */
        private Integer kartNumber;
        private Integer startPosition;
        private int columnIndex;
        private String bestTime;
        private String avgTime;
        private String devTime;
        /** Стинты по порядку; у одиночного стинта — одна запись. */
        private List<ParsedStint> stints = new ArrayList<>();
    }

    /**
     * Отрезок гонки на одном карте. Диапазоны кругов — в номерах кругов гонки,
     * {@code lapTo} включительно. Последний круг стинта — это заезд на пит
     * (на сайте помечен классом {@code pitstop-cell}).
     */
    @Data
    static class ParsedStint {
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
    static class ParsedLap {
        /** Номер круга в гонке (первая колонка таблицы). */
        private Integer lapNumber;
        /** То же, что и {@code lapNumber}: в таблице сайта нумерация сквозная. */
        private Integer raceLapNumber;
        /** Номер стинта, начиная с 1. */
        private Integer stintIndex;
        /** Карт, на котором проехан этот круг. */
        private Integer kartNumber;
        private String driverSiteId;
        private String lapTime;
        private Long lapTimeMillis;
        /** Позиция в гонке на этом круге (span.p). */
        private String position;
        /** Позиция в гонке на этом круге (то же значение, оставлено для API). */
        private String racePosition;
        private int columnIndex;
        private String gapToLeader;
        private String gapToPrevious;
        private boolean bestLapOverall;
        private boolean bestLapStint;
    }
}
