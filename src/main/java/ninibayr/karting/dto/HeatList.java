package ninibayr.karting.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HeatList {
    private String trackName;
    private List<HeatItem> heats;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HeatItem {
        private String id;
        private String name;
        private String date;
        private String time;
        private String topResult; // Например: "1. RK (1:14.719)"
        /** Тип события: HEAT (заезд) или RACE (гонка/финал). */
        private String type;

        public HeatItem(String id, String name, String date, String time, String topResult) {
            this(id, name, date, time, topResult, "HEAT");
        }
    }
}
