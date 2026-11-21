package ninibayr.karting.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ninibayr.karting.dto.HeatList;
import ninibayr.karting.repository.HeatRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HeatPrefetchServiceTest {

    private HeatRepository heatRepository;
    private HeatSyncService heatSyncService;
    private HeatListParser heatListParser;
    private SitePollingSchedule schedule;
    private SiteRequestGate siteRequestGate;
    private HeatPrefetchService service;

    @BeforeEach
    void setUp() {
        heatRepository = mock(HeatRepository.class);
        heatSyncService = mock(HeatSyncService.class);
        heatListParser = mock(HeatListParser.class);
        schedule = mock(SitePollingSchedule.class);
        siteRequestGate = mock(SiteRequestGate.class);
        service = new HeatPrefetchService(
                heatRepository,
                heatSyncService,
                heatListParser,
                schedule,
                siteRequestGate,
                new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    @DisplayName("finish: после финиша дважды сбрасываем список и читаем детали заезда")
    void finishLiveHeat_resetsListBeforeAndAfterSync() {
        service.finishLiveHeat("321");

        verify(heatListParser, timeout(2_000).times(2)).invalidate();
        verify(heatSyncService, timeout(2_000)).syncHeat("321");
    }

    @Test
    @DisplayName("finish: пересинхронизация того же id не блокирует обращение после финиша")
    @SuppressWarnings("unchecked")
    void finishLiveHeat_isNotBlockedByResyncingSet() {
        Set<String> resyncing = (Set<String>) ReflectionTestUtils.getField(service, "resyncing");
        resyncing.add("333");

        service.finishLiveHeat("333");

        verify(heatListParser, timeout(2_000).times(2)).invalidate();
        verify(heatSyncService, timeout(2_000)).syncHeat("333");
    }

    @Test
    @DisplayName("finish: во время cooldown запрос не теряется, а откладывается")
    void finishLiveHeat_waitsForCooldown() throws Exception {
        when(siteRequestGate.remainingBackgroundPauseSeconds()).thenReturn(120L);

        service.finishLiveHeat("322");

        Thread.sleep(200);
        verifyNoInteractions(heatListParser, heatSyncService);
    }

    @Test
    @DisplayName("prefetch: после успешного прохода можно запустить прогрев ещё раз")
    void prefetchRecentAsync_canRunAgainAfterPreviousSuccess() throws Exception {
        when(schedule.prefetchLimit(any(LocalTime.class))).thenReturn(1);
        when(heatListParser.parseCurrentTrack()).thenReturn(new HeatList("narvskaya", List.of(
                new HeatList.HeatItem("401", "Heat 1", "2026-11-21", "12:00", "1. RK 50.000"))));
        when(heatRepository.findBySiteId("401")).thenReturn(Optional.empty());

        service.prefetchRecentAsync();
        verify(heatSyncService, timeout(2_000)).syncHeat("401");

        service.prefetchRecentAsync();
        verify(heatSyncService, timeout(2_000).times(2)).syncHeat("401");
    }
}
