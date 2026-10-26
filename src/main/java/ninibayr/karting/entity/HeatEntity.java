package ninibayr.karting.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "heats")
@Data @NoArgsConstructor @AllArgsConstructor
public class HeatEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "site_id", unique = true, nullable = false)
    private String siteId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "track_id")
    private TrackEntity track;

    private String name;
    private LocalDate raceDate;
    private LocalTime raceTime;
    private LocalDateTime lastUpdated;

    // Каскадное сохранение: при сохранении Heat, сохранятся и все его Lap
    @OneToMany(mappedBy = "heat", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LapEntity> laps = new ArrayList<>();

    // Стинты (отрезки на одном карте) — по одному на гонщика и смену карта.
    @OneToMany(mappedBy = "heat", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<HeatStintEntity> stints = new ArrayList<>();
}