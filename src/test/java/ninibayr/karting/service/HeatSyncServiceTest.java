package ninibayr.karting.service;

import ninibayr.karting.dto.HeatData;
import ninibayr.karting.entity.HeatEntity;
import ninibayr.karting.entity.TrackEntity;
import ninibayr.karting.repository.HeatRepository;
import ninibayr.karting.repository.TrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HeatSyncServiceTest {

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private HeatRepository heatRepository;

    @Mock
    private HeatParser heatParser;

    @Mock
    private HeatMapper heatMapper;

    private static final String TRACK_SLUG = "narvskaya";
    private static final String BASE_URL = "https://timing.batyrshin.name/tracks";

    private HeatSyncService heatSyncService;
    private TrackEntity testTrack;

    @BeforeEach
    void setUp() {
        heatSyncService = new HeatSyncService(trackRepository, heatRepository, heatParser, heatMapper, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        ReflectionTestUtils.setField(heatSyncService, "trackSlug", TRACK_SLUG);
        ReflectionTestUtils.setField(heatSyncService, "baseUrl", BASE_URL);

        testTrack = new TrackEntity();
        testTrack.setId(1L);
        testTrack.setSlug(TRACK_SLUG);
        testTrack.setName("Narvskaya");
    }

    @Test
    @DisplayName("syncHeat: должен успешно синхронизировать новый заезд")
    void syncHeat_shouldSyncNewHeatSuccessfully() throws Exception {
        String heatSiteId = "131195";
        String url = BASE_URL + "/" + TRACK_SLUG + "/heats/" + heatSiteId;

        ParsedHeatData parsedData = new ParsedHeatData();
        parsedData.setName("Heat 33 ДЕТИ");

        HeatEntity newHeat = new HeatEntity();
        newHeat.setSiteId(heatSiteId);
        newHeat.setTrack(testTrack);
        newHeat.setName("Heat 33 ДЕТИ");

        HeatData expectedDto = new HeatData("Heat 33 ДЕТИ", null, null, List.of(), List.of());

        when(trackRepository.findBySlug(TRACK_SLUG)).thenReturn(Optional.of(testTrack));
        when(heatRepository.findBySiteIdForUpdate(heatSiteId)).thenReturn(Optional.empty());
        when(heatParser.parse(url)).thenReturn(parsedData);
        when(heatMapper.toDto(any(HeatEntity.class))).thenReturn(expectedDto);

        HeatData result = heatSyncService.syncHeat(heatSiteId);

        assertThat(result).isNotNull();
        assertThat(result.getHeatName()).isEqualTo("Heat 33 ДЕТИ");

        verify(trackRepository).findBySlug(TRACK_SLUG);
        verify(heatRepository).findBySiteIdForUpdate(heatSiteId);
        verify(heatParser).parse(url);
        verify(heatMapper).updateEntity(any(HeatEntity.class), eq(parsedData), eq(testTrack));
        verify(heatRepository).save(any(HeatEntity.class));
        verify(heatMapper).toDto(any(HeatEntity.class));
    }

    @Test
    @DisplayName("syncHeat: должен обновить существующий заезд")
    void syncHeat_shouldUpdateExistingHeat() throws Exception {
        String heatSiteId = "131195";
        String url = BASE_URL + "/" + TRACK_SLUG + "/heats/" + heatSiteId;

        HeatEntity existingHeat = new HeatEntity();
        existingHeat.setId(1L);
        existingHeat.setSiteId(heatSiteId);
        existingHeat.setTrack(testTrack);

        ParsedHeatData parsedData = new ParsedHeatData();
        parsedData.setName("Heat 33 ДЕТИ (обновлено)");

        HeatData expectedDto = new HeatData("Heat 33 ДЕТИ (обновлено)", null, null, List.of(), List.of());

        when(trackRepository.findBySlug(TRACK_SLUG)).thenReturn(Optional.of(testTrack));
        when(heatRepository.findBySiteIdForUpdate(heatSiteId)).thenReturn(Optional.of(existingHeat));
        when(heatParser.parse(url)).thenReturn(parsedData);
        when(heatMapper.toDto(existingHeat)).thenReturn(expectedDto);

        HeatData result = heatSyncService.syncHeat(heatSiteId);

        assertThat(result.getHeatName()).isEqualTo("Heat 33 ДЕТИ (обновлено)");
        verify(heatRepository).save(existingHeat);
    }

    @Test
    @DisplayName("syncHeat: должен выбросить исключение, если трасса не найдена")
    void syncHeat_shouldThrowExceptionWhenTrackNotFound() throws Exception {
        String heatSiteId = "131195";

        when(trackRepository.findBySlug(TRACK_SLUG)).thenReturn(Optional.empty());

        // ИСПРАВЛЕНО: проверяем сообщение верхнего уровня, которое возвращает наш сервис
        assertThatThrownBy(() -> heatSyncService.syncHeat(heatSiteId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Не удалось получить данные для заезда " + heatSiteId);

        verify(heatParser, never()).parse(anyString());
    }

    @Test
    @DisplayName("syncHeat: должен выбросить исключение при ошибке парсинга")
    void syncHeat_shouldThrowExceptionWhenParsingFails() throws Exception {
        String heatSiteId = "131195";
        String url = BASE_URL + "/" + TRACK_SLUG + "/heats/" + heatSiteId;

        when(trackRepository.findBySlug(TRACK_SLUG)).thenReturn(Optional.of(testTrack));
        when(heatRepository.findBySiteIdForUpdate(heatSiteId)).thenReturn(Optional.empty());
        when(heatParser.parse(url)).thenThrow(new RuntimeException("Сайт недоступен"));

        assertThatThrownBy(() -> heatSyncService.syncHeat(heatSiteId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Не удалось получить данные для заезда " + heatSiteId);

        verify(heatRepository, never()).save(any());
    }
}