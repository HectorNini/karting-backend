package ninibayr.karting.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "laps")
@Data @NoArgsConstructor @AllArgsConstructor
public class LapEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "heat_id", nullable = false)
    private HeatEntity heat;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    private DriverEntity driver;

    private Integer kartNumber;
    private Integer columnIndex;
    private Integer lapNumber;
    private Integer raceLapNumber; // Номер круга в гонке (с учётом смен карта)
    private Integer stintIndex;    // Номер стинта (смены карта), начиная с 0
    private String lapTime;      // Форматированное время как на сайте (m:ss.mmm или s.mmm) или "DNF"/пусто
    private Long lapTimeMillis; // Нормализованное время в миллисекундах для корректной сортировки/сравнения
    private String position;    // Позиция внутри стинта ("P1", "P2" и т.д.)
    private String racePosition; // Позиция в гонке на этом круге
    private String gapToLeader;
    private String gapToPrevious;
    private Boolean bestLapOverall;
    private Boolean bestLapStint;
}