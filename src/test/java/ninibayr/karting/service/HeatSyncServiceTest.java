package ninibayr.karting.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ninibayr.karting.dto.HeatData;
import ninibayr.karting.exception.SiteRateLimitedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HeatSyncServiceTest {

    @Mock
    private HeatParser heatParser;

    @Mock
    private HeatPersistenceService heatPersistenceService;

    private static final String TRACK_SLUG = "narvskaya";
    private static final String BASE_URL = "https://timing.batyrshin.name/tracks";

    private HeatSyncService heatSyncService;

    @BeforeEach
    void setUp() {
        heatSyncService = new HeatSyncService(
                heatParser,
                heatPersistenceService,
                new SimpleMeterRegistry());
        ReflectionTestUtils.setField(heatSyncService, "trackSlug", TRACK_SLUG);
        ReflectionTestUtils.setField(heatSyncService, "baseUrl", BASE_URL);
    }

    @Test
    @DisplayName("syncHeat: парсит вне транзакции и сохраняет через persistence-сервис")
    void syncHeat_shouldParseThenPersist() throws Exception {
        String heatSiteId = "131195";
        String url = BASE_URL + "/" + TRACK_SLUG + "/heats/" + heatSiteId;

        ParsedHeatData parsedData = new ParsedHeatData();
        parsedData.setName("Heat 33 ДЕТИ");

        HeatData expectedDto = new HeatData("Heat 33 ДЕТИ", null, null, List.of(), List.of());

        when(heatParser.parse(url)).thenReturn(parsedData);
        when(heatPersistenceService.persist(heatSiteId, parsedData)).thenReturn(expectedDto);

        HeatData result = heatSyncService.syncHeat(heatSiteId);

        assertThat(result).isSameAs(expectedDto);
        verify(heatParser).parse(url);
        verify(heatPersistenceService).persist(heatSiteId, parsedData);
    }

    @Test
    @DisplayName("syncHeat: пробрасывает rate limit как отдельный тип, а не 500")
    void syncHeat_shouldPropagateRateLimit() throws Exception {
        String heatSiteId = "134848";
        String url = BASE_URL + "/" + TRACK_SLUG + "/heats/" + heatSiteId;

        when(heatParser.parse(url))
                .thenThrow(new SiteRateLimitedException("HTTP 429", 60, 429));

        assertThatThrownBy(() -> heatSyncService.syncHeat(heatSiteId))
                .isInstanceOf(SiteRateLimitedException.class)
                .hasMessageContaining("HTTP 429");

        verify(heatPersistenceService, never()).persist(anyString(), any(ParsedHeatData.class));
    }

    @Test
    @DisplayName("syncHeat: оборачивает ошибку парсинга в понятное исключение")
    void syncHeat_shouldThrowExceptionWhenParsingFails() throws Exception {
        String heatSiteId = "131195";
        String url = BASE_URL + "/" + TRACK_SLUG + "/heats/" + heatSiteId;

        when(heatParser.parse(url)).thenThrow(new RuntimeException("Сайт недоступен"));

        assertThatThrownBy(() -> heatSyncService.syncHeat(heatSiteId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Не удалось получить данные для заезда " + heatSiteId);

        verify(heatPersistenceService, never()).persist(anyString(), any(ParsedHeatData.class));
    }

    @Test
    @DisplayName("syncHeat: оборачивает ошибку сохранения в понятное исключение")
    void syncHeat_shouldWrapPersistenceFailure() throws Exception {
        String heatSiteId = "131195";
        String url = BASE_URL + "/" + TRACK_SLUG + "/heats/" + heatSiteId;

        ParsedHeatData parsedData = new ParsedHeatData();
        parsedData.setName("Heat 33 ДЕТИ");

        when(heatParser.parse(url)).thenReturn(parsedData);
        when(heatPersistenceService.persist(heatSiteId, parsedData))
                .thenThrow(new RuntimeException("Трасса не найдена"));

        assertThatThrownBy(() -> heatSyncService.syncHeat(heatSiteId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Не удалось получить данные для заезда " + heatSiteId);
    }
}
