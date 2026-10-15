package ninibayr.karting.service;

import ninibayr.karting.dto.HeatData;
import ninibayr.karting.entity.DriverEntity;
import ninibayr.karting.entity.HeatEntity;
import ninibayr.karting.entity.LapEntity;
import ninibayr.karting.entity.TrackEntity;
import ninibayr.karting.repository.DriverRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList; // <-- ДОБАВЛЕН ИМПОРТ
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HeatMapperTest {

    @Mock
    private DriverRepository driverRepository;

    private HeatMapper heatMapper;
    private TrackEntity testTrack;

    @BeforeEach
    void setUp() {
        heatMapper = new HeatMapper(driverRepository);
        testTrack = new TrackEntity();
        testTrack.setId(1L);
        testTrack.setSlug("narvskaya");
        testTrack.setName("Narvskaya");
    }

    @Test
    @DisplayName("updateEntity: должен заполнить существующую сущность кругами")
    void updateEntity_shouldPopulateExistingEntityWithLaps() {
        ParsedHeatData parsedData = new ParsedHeatData();
        parsedData.setName("Heat 33 ДЕТИ");

        ParsedHeatData.ParsedDriver driver = new ParsedHeatData.ParsedDriver();
        driver.setSiteId("7494");
        driver.setName("Игнатий");
        driver.setKartNumber(33);
        parsedData.setDrivers(List.of(driver));

        ParsedHeatData.ParsedLap lap = new ParsedHeatData.ParsedLap();
        lap.setLapNumber(1);
        lap.setDriverSiteId("7494");
        lap.setLapTime("2:22.523");
        lap.setPosition("P1");
        parsedData.setLaps(List.of(lap));

        DriverEntity existingDriver = new DriverEntity();
        existingDriver.setId(10L);
        existingDriver.setSiteId("7494");
        existingDriver.setName("Игнатий");
        existingDriver.setTrack(testTrack);

        when(driverRepository.findBySiteIdAndTrack(eq("7494"), eq(testTrack)))
                .thenReturn(Optional.of(existingDriver));

        HeatEntity heatEntity = new HeatEntity();
        heatEntity.setId(1L);
        heatEntity.setSiteId("131195");
        heatEntity.setTrack(testTrack);
        heatEntity.setLaps(new ArrayList<>()); // <-- ИСПРАВЛЕНО: изменяемый список

        heatMapper.updateEntity(heatEntity, parsedData, testTrack);

        assertThat(heatEntity.getName()).isEqualTo("Heat 33 ДЕТИ");
        assertThat(heatEntity.getLaps()).hasSize(1);

        LapEntity savedLap = heatEntity.getLaps().get(0);
        assertThat(savedLap.getLapNumber()).isEqualTo(1);
        assertThat(savedLap.getLapTime()).isEqualTo("2:22.523");
        assertThat(savedLap.getPosition()).isEqualTo("P1");
        assertThat(savedLap.getKartNumber()).isEqualTo(33);
        assertThat(savedLap.getDriver()).isEqualTo(existingDriver);
    }

    @Test
    @DisplayName("updateEntity: должен создать нового гонщика, если его нет в БД")
    void updateEntity_shouldCreateNewDriverWhenNotFound() {
        ParsedHeatData parsedData = new ParsedHeatData();
        parsedData.setName("Heat 52");

        ParsedHeatData.ParsedDriver driver = new ParsedHeatData.ParsedDriver();
        driver.setSiteId("8888");
        driver.setName("Новый Гонщик");
        driver.setKartNumber(42);
        parsedData.setDrivers(List.of(driver));

        ParsedHeatData.ParsedLap lap = new ParsedHeatData.ParsedLap();
        lap.setLapNumber(1);
        lap.setDriverSiteId("8888");
        lap.setLapTime("1:15.000");
        parsedData.setLaps(List.of(lap));

        when(driverRepository.findBySiteIdAndTrack(eq("8888"), eq(testTrack)))
                .thenReturn(Optional.empty());

        DriverEntity newDriver = new DriverEntity();
        newDriver.setId(99L);
        newDriver.setSiteId("8888");
        newDriver.setName("Новый Гонщик");
        newDriver.setTrack(testTrack);
        when(driverRepository.save(any(DriverEntity.class))).thenReturn(newDriver);

        HeatEntity heatEntity = new HeatEntity();
        heatEntity.setLaps(new ArrayList<>()); // <-- ИСПРАВЛЕНО: изменяемый список

        heatMapper.updateEntity(heatEntity, parsedData, testTrack);

        assertThat(heatEntity.getLaps()).hasSize(1);
        assertThat(heatEntity.getLaps().get(0).getDriver().getSiteId()).isEqualTo("8888");
        assertThat(heatEntity.getLaps().get(0).getDriver().getName()).isEqualTo("Новый Гонщик");
    }

    @Test
    @DisplayName("toDto: должен корректно преобразовать HeatEntity в DTO")
    void toDto_shouldConvertHeatEntityToDtoCorrectly() {
        HeatEntity heatEntity = new HeatEntity();
        heatEntity.setId(1L);
        heatEntity.setSiteId("131195");
        heatEntity.setName("Heat 33 ДЕТИ");

        DriverEntity driver1 = new DriverEntity();
        driver1.setId(1L);
        driver1.setName("Игнатий");

        DriverEntity driver2 = new DriverEntity();
        driver2.setId(2L);
        driver2.setName("Анастасия");

        LapEntity lap1 = new LapEntity();
        lap1.setLapNumber(1);
        lap1.setDriver(driver1);
        lap1.setLapTime("2:22.523");
        lap1.setPosition("P1");

        LapEntity lap2 = new LapEntity();
        lap2.setLapNumber(1);
        lap2.setDriver(driver2);
        lap2.setLapTime("2:28.348");
        lap2.setPosition("P2");

        heatEntity.setLaps(List.of(lap1, lap2)); // Здесь List.of() ок, так как мы его не модифицируем

        HeatData result = heatMapper.toDto(heatEntity);

        assertThat(result).isNotNull();
        assertThat(result.getHeatName()).isEqualTo("Heat 33 ДЕТИ");
        assertThat(result.getDrivers()).containsExactlyInAnyOrder("Игнатий", "Анастасия");
        assertThat(result.getLaps()).hasSize(1);
        assertThat(result.getLaps().get(0).getLapNumber()).isEqualTo("1");
        assertThat(result.getLaps().get(0).getResults()).hasSize(2);
        assertThat(result.getLaps().get(0).getResults().get(0)).contains("2:22.523");
        assertThat(result.getLaps().get(0).getResults().get(1)).contains("P2");
    }
}