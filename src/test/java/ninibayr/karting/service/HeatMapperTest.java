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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
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

    private void stubDriverSave() {
        when(driverRepository.save(any(DriverEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("updateEntity: должен заполнить заезд датой, кругами и порядком колонок")
    void updateEntity_shouldPopulateExistingEntityWithLaps() {
        stubDriverSave();
        ParsedHeatData parsedData = heatWithOneDriver();

        DriverEntity existingDriver = new DriverEntity();
        existingDriver.setId(10L);
        existingDriver.setSiteId("7494");
        existingDriver.setName("Старое имя");
        existingDriver.setTrack(testTrack);

        when(driverRepository.findBySiteIdAndTrack(eq("7494"), eq(testTrack)))
                .thenReturn(Optional.of(existingDriver));

        HeatEntity heatEntity = new HeatEntity();
        heatEntity.setId(1L);
        heatEntity.setSiteId("131195");
        heatEntity.setTrack(testTrack);
        heatEntity.setLaps(new ArrayList<>());

        heatMapper.updateEntity(heatEntity, parsedData, testTrack);

        assertThat(heatEntity.getName()).isEqualTo("Heat 33 ДЕТИ");
        assertThat(heatEntity.getRaceDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(heatEntity.getRaceTime()).isEqualTo(LocalTime.of(16, 44));
        assertThat(heatEntity.getLaps()).hasSize(1);
        assertThat(existingDriver.getName()).isEqualTo("Игнатий");

        LapEntity savedLap = heatEntity.getLaps().get(0);
        assertThat(savedLap.getLapNumber()).isEqualTo(1);
        assertThat(savedLap.getLapTime()).isEqualTo("2:22.523");
        assertThat(savedLap.getPosition()).isEqualTo("P1");
        assertThat(savedLap.getKartNumber()).isEqualTo(33);
        assertThat(savedLap.getColumnIndex()).isEqualTo(0);
        assertThat(savedLap.getDriver()).isEqualTo(existingDriver);
    }

    @Test
    @DisplayName("updateEntity: должен создать нового гонщика, если его нет в БД")
    void updateEntity_shouldCreateNewDriverWhenNotFound() {
        stubDriverSave();
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

        HeatEntity heatEntity = new HeatEntity();
        heatEntity.setLaps(new ArrayList<>());

        heatMapper.updateEntity(heatEntity, parsedData, testTrack);

        assertThat(heatEntity.getLaps()).hasSize(1);
        assertThat(heatEntity.getLaps().get(0).getDriver().getSiteId()).isEqualTo("8888");
        assertThat(heatEntity.getLaps().get(0).getDriver().getName()).isEqualTo("Новый Гонщик");
    }

    @Test
    @DisplayName("toDto: должен отдать гонщиков с siteId в порядке колонок")
    void toDto_shouldConvertHeatEntityToDtoCorrectly() {
        HeatEntity heatEntity = new HeatEntity();
        heatEntity.setId(1L);
        heatEntity.setSiteId("131195");
        heatEntity.setName("Heat 33 ДЕТИ");
        heatEntity.setRaceDate(LocalDate.of(2026, 10, 10));
        heatEntity.setRaceTime(LocalTime.of(16, 44));

        DriverEntity driver1 = new DriverEntity();
        driver1.setId(1L);
        driver1.setSiteId("7494");
        driver1.setName("Игнатий");

        DriverEntity driver2 = new DriverEntity();
        driver2.setId(2L);
        driver2.setSiteId("152");
        driver2.setName("Анастасия");

        LapEntity lap1 = new LapEntity();
        lap1.setLapNumber(1);
        lap1.setColumnIndex(0);
        lap1.setKartNumber(33);
        lap1.setDriver(driver1);
        lap1.setLapTime("2:22.523");
        lap1.setPosition("P1");

        LapEntity lap2 = new LapEntity();
        lap2.setLapNumber(1);
        lap2.setColumnIndex(1);
        lap2.setKartNumber(39);
        lap2.setDriver(driver2);
        lap2.setLapTime("2:28.348");
        lap2.setPosition("P2");

        heatEntity.setLaps(List.of(lap2, lap1));

        HeatData result = heatMapper.toDto(heatEntity);

        assertThat(result.getHeatName()).isEqualTo("Heat 33 ДЕТИ");
        assertThat(result.getRaceDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(result.getRaceTime()).isEqualTo(LocalTime.of(16, 44));
        assertThat(result.getDrivers()).extracting(HeatData.DriverInfo::getSiteId)
                .containsExactly("7494", "152");
        assertThat(result.getDrivers()).extracting(HeatData.DriverInfo::getName)
                .containsExactly("Игнатий", "Анастасия");
        assertThat(result.getLaps()).hasSize(1);
        assertThat(result.getLaps().get(0).getResults().get(0)).contains("2:22.523");
        assertThat(result.getLaps().get(0).getResults().get(1)).contains("P2");
    }

    private static ParsedHeatData heatWithOneDriver() {
        ParsedHeatData parsedData = new ParsedHeatData();
        parsedData.setName("Heat 33 ДЕТИ");
        parsedData.setRaceDate(LocalDate.of(2026, 10, 10));
        parsedData.setRaceTime(LocalTime.of(16, 44));

        ParsedHeatData.ParsedDriver driver = new ParsedHeatData.ParsedDriver();
        driver.setSiteId("7494");
        driver.setName("Игнатий");
        driver.setKartNumber(33);
        driver.setColumnIndex(0);
        parsedData.setDrivers(List.of(driver));

        ParsedHeatData.ParsedLap lap = new ParsedHeatData.ParsedLap();
        lap.setLapNumber(1);
        lap.setDriverSiteId("7494");
        lap.setLapTime("2:22.523");
        lap.setPosition("P1");
        lap.setColumnIndex(0);
        parsedData.setLaps(List.of(lap));
        return parsedData;
    }
}
