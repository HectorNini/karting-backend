package ninibayr.karting.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HeatData {
    private String heatName;
    private List<String> drivers;
    private List<LapRow> laps;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LapRow {
        private String lapNumber;
        private List<String> results;
    }
}