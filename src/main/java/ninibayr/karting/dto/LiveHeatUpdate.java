package ninibayr.karting.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Снимок live-сессии, полученный с сайта тайминга (WebSocket).
 * Все времена — в миллисекундах.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LiveHeatUpdate {
    private String heatId;
    private String heatName;
    private String trackSlug;
    private Long elapsed;
    private Integer mode;
    private List<Integer> pitlaneKarts;
    private Long pitstopTimeThreshold;
    private List<LiveDriver> drivers;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LiveDriver {
        private String driverId;
        private String driverName;
        private Integer number;
        /** Позиция, которую отдаёт сервер тайминга. */
        private Integer position;
        /** Лучшее время, отданное сервером (часто null — тогда считаем сами). */
        private Long bestTime;
        private Stint stint;
        private List<LiveLap> laps;

        // --- Поля, которые считаем сами при разборе ---
        private Integer totalLaps;
        private Integer lastLapNumber;
        private Long bestLapTime;
        private Long lastLapTime;
        /** Интервал до машины впереди на последнем круге (мс). */
        private Long lastGapTime;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Stint {
        private Integer kart;
        private Integer number;
        private Long bestTime;
        private Integer length;
        private Long avgTime;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LiveLap {
        private Integer stintNum;
        private Integer number;
        private Long time;
        private Long gapTime;
        private Integer kartNum;
    }
}
