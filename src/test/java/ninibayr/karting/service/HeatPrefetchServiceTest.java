package ninibayr.karting.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ninibayr.karting.repository.HeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HeatPrefetchServiceTest {

    private HeatSyncService heatSyncService;
    private HeatListParser heatListParser;
    private SiteRequestGate siteRequestGate;
    private HeatPrefetchService service;

    @BeforeEach
    void setUp() {
        heatSyncService = mock(HeatSyncService.class);
        heatListParser = mock(HeatListParser.class);
        siteRequestGate = mock(SiteRequestGate.class);
        service = new HeatPrefetchService(
                mock(HeatRepository.class),
                heatSyncService,
                heatListParser,
                mock(SitePollingSchedule.class),
                siteRequestGate,
                new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("finish: после финиша live сбрасываем список и читаем детали заезда")
    void finishLiveHeat_resetsListAndSyncsDetail() {
        service.finishLiveHeat("321");

        verify(heatListParser, timeout(2_000)).invalidate();
        verify(heatSyncService, timeout(2_000)).syncHeat("321");
    }

    @Test
    @DisplayName("finish: во время cooldown запрос не теряется, а откладывается")
    void finishLiveHeat_waitsForCooldown() throws Exception {
        when(siteRequestGate.remainingBackgroundPauseSeconds()).thenReturn(120L);

        service.finishLiveHeat("322");

        Thread.sleep(200);
        verifyNoInteractions(heatListParser, heatSyncService);
    }
}
