package ninibayr.karting.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ninibayr.karting.dto.HeatData;
import ninibayr.karting.dto.HeatList;
import ninibayr.karting.exception.SiteRateLimitedException;
import ninibayr.karting.repository.HeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HeatServiceTest {

    @Mock
    private HeatRepository heatRepository;
    @Mock
    private HeatSyncService heatSyncService;
    @Mock
    private HeatReadService heatReadService;
    @Mock
    private HeatListParser heatListParser;
    @Mock
    private HeatPrefetchService heatPrefetchService;
    @Mock
    private LiveTimingService liveTimingService;
    @Mock
    private SiteRequestGate siteRequestGate;

    private HeatService heatService;

    @BeforeEach
    void setUp() {
        heatService = new HeatService(
                heatRepository,
                heatSyncService,
                heatReadService,
                heatListParser,
                heatPrefetchService,
                liveTimingService,
                siteRequestGate,
                new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("list: текущий live-заезд не попадает в общий список")
    void getHeatList_hidesCurrentLiveHeat() throws Exception {
        when(heatListParser.parseCurrentTrack()).thenReturn(listWith("100", "101"));
        when(liveTimingService.getLiveHeatId()).thenReturn("100");

        HeatList result = heatService.getHeatList();

        assertThat(result.getHeats())
                .extracting(HeatList.HeatItem::getId)
                .containsExactly("101");
        verify(heatPrefetchService).prefetchRecentAsync();
    }

    @Test
    @DisplayName("list: после окончания live-заезд снова виден")
    void getHeatList_keepsHeatWhenNotLive() throws Exception {
        when(heatListParser.parseCurrentTrack()).thenReturn(listWith("100", "101"));
        when(liveTimingService.getLiveHeatId()).thenReturn(null);

        HeatList result = heatService.getHeatList();

        assertThat(result.getHeats())
                .extracting(HeatList.HeatItem::getId)
                .containsExactly("100", "101");
    }

    @Test
    @DisplayName("list: помечает заезды, которых ещё нет в БД")
    void getHeatList_marksUnsyncedHeats() throws Exception {
        when(heatListParser.parseCurrentTrack()).thenReturn(listWith("100", "101"));
        when(liveTimingService.getLiveHeatId()).thenReturn(null);
        when(heatRepository.findExistingSiteIds(List.of("100", "101")))
                .thenReturn(List.of("100"));

        HeatList result = heatService.getHeatList();

        assertThat(result.getHeats())
                .extracting(HeatList.HeatItem::isSynced)
                .containsExactly(true, false);
    }

    @Test
    @DisplayName("sync: синхронизирует только новые заезды и не трогает live")
    void syncNewHeats_syncsOnlyMissingHeats() throws Exception {
        ReflectionTestUtils.setField(heatService, "newHeatsSyncEnabled", true);
        ReflectionTestUtils.setField(heatService, "newHeatsPerRun", 2);
        when(heatListParser.parseCurrentTrack()).thenReturn(listWith("100", "101", "102", "103"));
        when(heatRepository.findExistingSiteIds(List.of("100", "101", "102", "103")))
                .thenReturn(List.of("100"));
        when(liveTimingService.getLiveHeatId()).thenReturn("102");

        int synced = heatService.syncNewHeats();

        assertThat(synced).isEqualTo(2);
        verify(heatSyncService).syncHeat("101");
        verify(heatSyncService).syncHeat("103");
        verify(heatSyncService, never()).syncHeat("100");
        verify(heatSyncService, never()).syncHeat("102");
    }

    @Test
    @DisplayName("sync: при 429 не продолжает синхронизировать следующие заезды")
    void syncNewHeats_stopsOnRateLimit() throws Exception {
        ReflectionTestUtils.setField(heatService, "newHeatsSyncEnabled", true);
        ReflectionTestUtils.setField(heatService, "newHeatsPerRun", 3);
        when(heatListParser.parseCurrentTrack()).thenReturn(listWith("200", "201"));
        when(heatRepository.findExistingSiteIds(List.of("200", "201"))).thenReturn(List.of());
        when(liveTimingService.getLiveHeatId()).thenReturn(null);
        when(heatSyncService.syncHeat("200"))
                .thenThrow(new SiteRateLimitedException("HTTP 429", 60, 429));

        int synced = heatService.syncNewHeats();

        assertThat(synced).isZero();
        verify(heatSyncService).syncHeat("200");
        verify(heatSyncService, never()).syncHeat("201");
    }

    @Test
    @DisplayName("heat: отдаёт заезд из кэша без синхронизации")
    void getHeatData_returnsCachedHeat() {
        HeatData data = new HeatData("Heat 1", null, null, List.of(), List.of());
        when(heatReadService.findCached("100"))
                .thenReturn(Optional.of(new HeatReadService.CachedHeat(data, false)));

        HeatData result = heatService.getHeatData("100");

        assertThat(result).isSameAs(data);
        verify(heatSyncService, never()).syncHeat("100");
    }

    private static HeatList listWith(String... ids) {
        List<HeatList.HeatItem> items = new ArrayList<>();
        for (String id : ids) {
            items.add(new HeatList.HeatItem(
                    id,
                    "Heat " + id,
                    "2026-11-06",
                    "12:00",
                    "1. Driver 1:00.000",
                    "HEAT"));
        }
        return new HeatList("Narvskaya", items);
    }
}
