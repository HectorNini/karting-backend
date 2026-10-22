package ninibayr.karting.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LiveHeatUpdate {
    private String heatId;
    private String heatName;
    private String trackSlug;
    private Long elapsed;
    private Integer mode;
    private List<LiveDriver> drivers;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LiveDriver {
        private String driverId;
        private String driverName;
        private Integer number;
        private Integer position;
        private Long bestTime;
        private Stint stint;
        private List<LiveLap> laps;
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
    }
}
