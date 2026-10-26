package ninibayr.karting.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Отрезок заезда, который гонщик проехал на одном карте.
 * Границы — номера кругов гонки, {@code lapTo} включительно.
 */
@Entity
@Table(name = "heat_stints")
@Data @NoArgsConstructor @AllArgsConstructor
public class HeatStintEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "heat_id", nullable = false)
    private HeatEntity heat;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    private DriverEntity driver;

    /** Номер стинта, начиная с 1. */
    private Integer stintNumber;
    private Integer kartNumber;
    private Integer lapFrom;
    private Integer lapTo;
    private Integer lapsCount;
    private Long bestLapMillis;
    private Long avgLapMillis;
    private Long devMillis;
}
