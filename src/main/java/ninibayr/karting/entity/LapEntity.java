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
    private String lapTime;   // Оставляем String, т.к. может быть "DNF" или пусто
    private String position;  // "P1", "P2" и т.д.
}