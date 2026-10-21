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
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LapRow {
        private String lapNumber;
        private List<String> results;
    }
}
