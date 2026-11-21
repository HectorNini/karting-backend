package ninibayr.karting.service;

import ninibayr.karting.dto.HeatData;
import ninibayr.karting.entity.TrackEntity;
import ninibayr.karting.repository.TrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "karting.track.slug=narvskaya"
})
@Import({HeatMapper.class, HeatPersistenceService.class, HeatReadService.class})
class HeatPersistenceIntegrationTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private HeatPersistenceService persistenceService;

    @Autowired
    private HeatReadService readService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        // HeatPersistenceService.persist() открывает REQUIRES_NEW: трек должен
        // быть зафиксирован до её вызова, иначе новая транзакция его не увидит.
        new TransactionTemplate(transactionManager, new org.springframework.transaction.support.DefaultTransactionDefinition(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW)).executeWithoutResult(status -> {
            if (trackRepository.findBySlug("narvskaya").isEmpty()) {
                trackRepository.save(new TrackEntity(null, "narvskaya", "Нарвская"));
            }
        });
    }

    @Test
    @DisplayName("persist → findCached: заезд читается из БД со стинтами и кругами")
    void persist_thenFindCached_returnsMappedHeat() {
        HeatData saved = persistenceService.persist("4242", parsedHeat(2, true));

        assertThat(saved.getHeatName()).isEqualTo("Heat 42");
        assertThat(saved.getDrivers()).hasSize(1);
        assertThat(saved.getDrivers().get(0).getStints())
                .extracting(HeatData.StintInfo::getKartNumber)
                .containsExactly(9);

        entityManager.flush();
        entityManager.clear();

        var cached = readService.findCached("4242");
        assertThat(cached).isPresent();
        assertThat(cached.get().needsResync()).isFalse();
        assertThat(cached.get().data().getLaps()).hasSize(2);
        assertThat(cached.get().data().getDrivers().get(0).getTotalLaps()).isEqualTo(2);
    }

    @Test
    @DisplayName("persist повторно: старые круги и стинты заменяются, а не дублируются")
    void persistTwice_replacesLapsAndStints() {
        persistenceService.persist("4243", parsedHeat(2, true));

        HeatData updated = persistenceService.persist("4243", parsedHeat(1, true));

        entityManager.flush();
        entityManager.clear();

        assertThat(updated.getLaps()).hasSize(1);
        var cached = readService.findCached("4243");
        assertThat(cached).isPresent();
        assertThat(cached.get().data().getLaps()).hasSize(1);
        assertThat(cached.get().data().getDrivers().get(0).getStints()).hasSize(1);
    }

    @Test
    @DisplayName("findCached: заезд с кругами без стинтов помечается на resync")
    void findCached_lapsWithoutStints_needsResync() {
        persistenceService.persist("4244", parsedHeat(2, false));

        entityManager.flush();
        entityManager.clear();

        var cached = readService.findCached("4244");
        assertThat(cached).isPresent();
        assertThat(cached.get().needsResync()).isTrue();
        assertThat(cached.get().data().getDrivers().get(0).getStints()).isEmpty();
    }

    private static ParsedHeatData parsedHeat(int lapCount, boolean withStints) {
        ParsedHeatData parsed = new ParsedHeatData();
        parsed.setName("Heat 42");
        parsed.setRaceDate(LocalDate.of(2026, 11, 21));
        parsed.setRaceTime(LocalTime.of(12, 0));

        ParsedHeatData.ParsedDriver driver = new ParsedHeatData.ParsedDriver();
        driver.setSiteId("D1");
        driver.setName("Гонщик 1");
        driver.setKartNumber(9);
        driver.setStartPosition(1);
        driver.setColumnIndex(0);
        if (withStints) {
            ParsedHeatData.ParsedStint stint = new ParsedHeatData.ParsedStint();
            stint.setNumber(1);
            stint.setKartNumber(9);
            stint.setLapFrom(1);
            stint.setLapTo(lapCount);
            stint.setLapsCount(lapCount);
            stint.setBestLapMillis(60_000L);
            stint.setAvgLapMillis(61_000L);
            driver.getStints().add(stint);
        }
        parsed.getDrivers().add(driver);

        for (int lapNumber = 1; lapNumber <= lapCount; lapNumber++) {
            ParsedHeatData.ParsedLap lap = new ParsedHeatData.ParsedLap();
            lap.setLapNumber(lapNumber);
            lap.setRaceLapNumber(lapNumber);
            lap.setStintIndex(1);
            lap.setKartNumber(9);
            lap.setDriverSiteId("D1");
            lap.setLapTime("1:00.000");
            lap.setLapTimeMillis(60_000L);
            lap.setPosition("P1");
            lap.setRacePosition("P1");
            lap.setColumnIndex(0);
            parsed.getLaps().add(lap);
        }
        return parsed;
    }
}
