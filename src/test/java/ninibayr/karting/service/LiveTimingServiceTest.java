package ninibayr.karting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ninibayr.karting.dto.LiveHeatUpdate;
import ninibayr.karting.dto.LiveSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LiveTimingServiceTest {

    private static final long STALE_MS = 180_000;

    private LiveTimingService service;
    private String sampleJson;

    @BeforeEach
    void setUp() throws Exception {
        // messagingTemplate = null: в тестах рассылка не нужна
        service = new LiveTimingService(null, new ObjectMapper(), null, "narvskaya", STALE_MS / 1000, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("live-sample.json")) {
            assertThat(in).isNotNull();
            sampleJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("parse: разбирает реальный снимок с сайта (пилоты, круги, stint, питлейн)")
    void parse_shouldReadRealPayload() {
        LiveHeatUpdate update = service.parseFromJson(sampleJson);

        assertThat(update).isNotNull();
        assertThat(update.getHeatId()).isEqualTo("133304");
        assertThat(update.getHeatName()).isEqualTo("Финал 3");
        assertThat(update.getElapsed()).isEqualTo(444968L);
        assertThat(update.getMode()).isZero();
        assertThat(update.getPitstopTimeThreshold()).isEqualTo(70000L);
        assertThat(update.getPitlaneKarts()).containsExactly(32, 22);
        assertThat(update.getDrivers()).hasSize(8);

        LiveHeatUpdate.LiveDriver leader = update.getDrivers().get(0);
        assertThat(leader.getDriverName()).isEqualTo("KIRILL ZOLOTOV");
        assertThat(leader.getNumber()).isEqualTo(21);
        assertThat(leader.getPosition()).isEqualTo(1);
        assertThat(leader.getTotalLaps()).isEqualTo(6);
        assertThat(leader.getLastLapNumber()).isEqualTo(6);
        assertThat(leader.getLastLapTime()).isEqualTo(72601L);
        assertThat(leader.getBestLapTime()).isEqualTo(72039L);
        assertThat(leader.getStint()).isNotNull();
        assertThat(leader.getStint().getKart()).isEqualTo(29);
        assertThat(leader.getLaps()).hasSize(6);
        assertThat(leader.getLaps().get(0).getTime()).isEqualTo(77399L);
    }

    @Test
    @DisplayName("live: одно сообщение — это последнее состояние, а не живая сессия")
    void singleMessage_isNotLive() {
        LiveHeatUpdate update = service.parseFromJson(sampleJson);

        service.handleUpdate(update, 1_000_000L);
        LiveSnapshot snapshot = service.getSnapshot(1_000_100L);

        assertThat(snapshot.isLive()).isFalse();
        assertThat(snapshot.getStatus()).isEqualTo(LiveTimingService.STATUS_WAITING);
        assertThat(snapshot.getMessages()).isEqualTo(1);
        assertThat(snapshot.getHeat()).isNotNull();
    }

    @Test
    @DisplayName("live: два сообщения подряд — сессия идёт прямо сейчас")
    void twoMessages_makeItLive() {
        LiveHeatUpdate update = service.parseFromJson(sampleJson);

        service.handleUpdate(update, 1_000_000L);
        service.handleUpdate(update, 1_005_000L);
        LiveSnapshot snapshot = service.getSnapshot(1_005_500L);

        assertThat(snapshot.isLive()).isTrue();
        assertThat(snapshot.getStatus()).isEqualTo(LiveTimingService.STATUS_LIVE);
        assertThat(snapshot.getMessages()).isEqualTo(2);
        assertThat(snapshot.getAgeMs()).isEqualTo(500);
    }

    @Test
    @DisplayName("live: тишина дольше stale-таймаута завершает сессию")
    void silence_endsLiveSession() {
        LiveHeatUpdate update = service.parseFromJson(sampleJson);

        service.handleUpdate(update, 1_000_000L);
        service.handleUpdate(update, 1_005_000L);
        assertThat(service.getSnapshot(1_005_000L).isLive()).isTrue();

        LiveSnapshot stale = service.getSnapshot(1_005_000L + STALE_MS + 1);

        assertThat(stale.isLive()).isFalse();
        assertThat(stale.getStatus()).isEqualTo(LiveTimingService.STATUS_FINISHED);
    }

    @Test
    @DisplayName("live: смена id сессии сбрасывает подтверждение")
    void newHeatId_resetsConfirmation() {
        LiveHeatUpdate first = service.parseFromJson(sampleJson);
        LiveHeatUpdate second = service.parseFromJson(sampleJson.replace("\"id\":133304", "\"id\":133305"));

        service.handleUpdate(first, 1_000_000L);
        service.handleUpdate(first, 1_001_000L);
        assertThat(service.getSnapshot(1_001_000L).isLive()).isTrue();

        service.handleUpdate(second, 1_002_000L);
        assertThat(service.getSnapshot(1_002_000L).isLive()).isFalse();
        assertThat(service.getSnapshot(1_002_000L).getMessages()).isEqualTo(1);
    }

    @Test
    @DisplayName("live: при смене сессии сохраняется завершившаяся гонка, а не новая")
    void newHeatId_persistsPreviousHeat() {
        HeatPrefetchService prefetch = org.mockito.Mockito.mock(HeatPrefetchService.class);
        LiveTimingService svc = new LiveTimingService(null, new ObjectMapper(), prefetch, "narvskaya", 180, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        LiveHeatUpdate first = svc.parseFromJson(sampleJson);
        LiveHeatUpdate second = svc.parseFromJson(sampleJson.replace("\"id\":133304", "\"id\":133305"));

        svc.handleUpdate(first, 1_000_000L);
        svc.handleUpdate(first, 1_001_000L);
        svc.handleUpdate(second, 1_002_000L);

        org.mockito.Mockito.verify(prefetch).finishLiveHeat("133304");
        org.mockito.Mockito.verifyNoMoreInteractions(prefetch);
    }

    @Test
    @DisplayName("live: смена id после одного сообщения тоже завершает previous")
    void newHeatId_singleMessage_persistsPreviousHeat() {
        HeatPrefetchService prefetch = org.mockito.Mockito.mock(HeatPrefetchService.class);
        LiveTimingService svc = new LiveTimingService(null, new ObjectMapper(), prefetch, "narvskaya", 180,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        LiveHeatUpdate first = svc.parseFromJson(sampleJson);
        LiveHeatUpdate second = svc.parseFromJson(sampleJson.replace("\"id\":133304", "\"id\":133305"));

        svc.handleUpdate(first, 1_000_000L); // одно сообщение ещё не считается live
        svc.handleUpdate(second, 1_001_000L);

        org.mockito.Mockito.verify(prefetch).finishLiveHeat("133304");
    }

    @Test
    @DisplayName("stop: ручная остановка запускает пересинхронизацию заезда")
    void stop_persistsHeat() {
        HeatPrefetchService prefetch = org.mockito.Mockito.mock(HeatPrefetchService.class);
        LiveTimingService svc = new LiveTimingService(null, new ObjectMapper(), prefetch, "narvskaya", 180,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        LiveHeatUpdate update = svc.parseFromJson(sampleJson);

        svc.handleUpdate(update, 1_000_000L);
        svc.handleUpdate(update, 1_001_000L);
        assertThat(svc.stopLive()).isTrue();

        org.mockito.Mockito.verify(prefetch).finishLiveHeat("133304");
        assertThat(svc.stopLive()).isFalse();
        org.mockito.Mockito.verifyNoMoreInteractions(prefetch);
    }

    @Test
    @DisplayName("snapshot: уже выданный снимок не меняется при новых дельтах")
    void snapshot_isDeepCopy() {
        service.handleUpdate(service.parseFromJson(HEAT_777_FULL), 1_000_000L);
        LiveHeatUpdate issued = service.getSnapshot(1_000_100L).getHeat();

        assertThat(issued.getElapsed()).isEqualTo(10_000L);
        assertThat(issued.getPitlaneKarts()).containsExactly(10);
        assertThat(issued.getDrivers().get(0).getDriverName()).isEqualTo("A");
        assertThat(issued.getDrivers().get(0).getLaps()).hasSize(1);

        service.handleUpdate(service.parseFromJson("""
                {"id":777,"pitlaneKarts":[],"drivers":[
                  {"driver":{"id":"1","name":"A2"},"laps":[{"stintNum":1,"number":2,"time":29500}]}
                ]}
                """), 1_001_000L);

        // Старый снимок остался неизменным.
        assertThat(issued.getElapsed()).isEqualTo(10_000L);
        assertThat(issued.getPitlaneKarts()).containsExactly(10);
        assertThat(issued.getDrivers().get(0).getDriverName()).isEqualTo("A");
        assertThat(issued.getDrivers().get(0).getLaps()).hasSize(1);
        assertThat(issued.getDrivers().get(0).getLaps().get(0).getTime()).isEqualTo(30_000L);

        // Попытка мутировать старый снимок не портит текущее состояние сервиса.
        issued.getPitlaneKarts().add(99);
        issued.getDrivers().get(0).setDriverName("MUTATED");
        issued.getDrivers().get(0).getLaps().get(0).setTime(1L);

        LiveHeatUpdate fresh = service.getSnapshot(1_001_100L).getHeat();
        assertThat(fresh.getPitlaneKarts()).isEmpty();
        assertThat(fresh.getDrivers().get(0).getDriverName()).isEqualTo("A2");
        assertThat(fresh.getDrivers().get(0).getLaps()).hasSize(2);
        assertThat(fresh.getDrivers().get(0).getLaps().get(0).getTime()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("live: тишина после финиша тоже запускает пересинхронизацию заезда")
    void staleness_persistsFinishedHeat() {
        HeatPrefetchService prefetch = org.mockito.Mockito.mock(HeatPrefetchService.class);
        LiveTimingService svc = new LiveTimingService(null, new ObjectMapper(), prefetch, "narvskaya", 180,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        LiveHeatUpdate update = svc.parseFromJson(sampleJson);
        long now = System.currentTimeMillis();

        svc.handleUpdate(update, now - STALE_MS - 10_000);
        svc.handleUpdate(update, now - STALE_MS - 5_000);
        svc.checkStaleness();

        org.mockito.Mockito.verify(prefetch).finishLiveHeat("133304");
    }

    @Test
    @DisplayName("stop: ручная остановка снимает флаг live")
    void stop_clearsLiveFlag() {
        LiveHeatUpdate update = service.parseFromJson(sampleJson);
        service.handleUpdate(update, 1_000_000L);
        service.handleUpdate(update, 1_001_000L);
        assertThat(service.getSnapshot(1_001_000L).isLive()).isTrue();

        assertThat(service.stopLive()).isTrue();
        LiveSnapshot stopped = service.getSnapshot(1_001_000L);
        assertThat(stopped.isLive()).isFalse();
        assertThat(stopped.getStatus()).isEqualTo(LiveTimingService.STATUS_FINISHED);
        assertThat(service.stopLive()).isFalse();
    }

    @Test
    @DisplayName("stop: новые данные снова включают live")
    void newData_afterStop_revivesLive() {
        LiveHeatUpdate update = service.parseFromJson(sampleJson);
        service.handleUpdate(update, 1_000_000L);
        service.handleUpdate(update, 1_001_000L);
        service.stopLive();
        assertThat(service.getSnapshot(1_001_000L).getStatus())
                .isEqualTo(LiveTimingService.STATUS_FINISHED);

        service.handleUpdate(update, 1_002_000L);
        assertThat(service.getSnapshot(1_002_000L).isLive()).isTrue();
    }

    @Test
    @DisplayName("parse: пустой/битый JSON не ломает сервис")
    void parse_badJson_returnsNull() {
        assertThat(service.parseFromJson("not json")).isNull();
        assertThat(service.parseFromJson("[]")).isNull();
        assertThat(service.parseFromJson("{}")).isNotNull();
    }

    // ------------------------------------------------------------------
    // Слияние дельт
    // ------------------------------------------------------------------

    private static final String HEAT_777_FULL = """
            {"id":777,"name":"Heat 7","elapsed":10000,"mode":0,"pitstopTimeThreshold":70000,
             "pitlaneKarts":[10],
             "drivers":[
               {"driver":{"id":"1","name":"A"},"number":1,"position":1,
                "stint":{"kart":9,"number":1,"length":1},
                "laps":[{"stintNum":1,"number":1,"time":30000}]},
               {"driver":{"id":"2","name":"B"},"number":2,"position":2,
                "stint":{"kart":20,"number":1,"length":1},
                "laps":[{"stintNum":1,"number":1,"time":31000}]}
             ]}
            """;

    @Test
    @DisplayName("merge: дельта не затирает остальных гонщиков и копит круги")
    void merge_keepsOtherDrivers_andAppendsLaps() {
        service.handleUpdate(service.parseFromJson(HEAT_777_FULL), 1_000_000L);
        service.handleUpdate(service.parseFromJson("""
                {"id":777,"drivers":[
                  {"driver":{"id":"1","name":"A"},"position":1,
                   "stint":{"kart":9,"number":1,"length":2},
                   "laps":[{"stintNum":1,"number":2,"time":29500}]}
                ]}
                """), 1_001_000L);

        LiveHeatUpdate heat = service.getSnapshot(1_001_100L).getHeat();

        assertThat(heat.getDrivers()).hasSize(2);
        assertThat(heat.getHeatName()).isEqualTo("Heat 7");

        LiveHeatUpdate.LiveDriver a = heat.getDrivers().get(0);
        assertThat(a.getLaps()).hasSize(2);
        assertThat(a.getTotalLaps()).isEqualTo(2);
        assertThat(a.getBestLapTime()).isEqualTo(29_500L);
        assertThat(a.getLastLapNumber()).isEqualTo(2);

        LiveHeatUpdate.LiveDriver b = heat.getDrivers().get(1);
        assertThat(b.getLaps()).hasSize(1);
        assertThat(b.getTotalLaps()).isEqualTo(1);
    }

    @Test
    @DisplayName("merge: отсутствующие поля дельты не сбрасывают состояние")
    void merge_absentFields_areKept() {
        service.handleUpdate(service.parseFromJson(HEAT_777_FULL), 1_000_000L);
        service.handleUpdate(service.parseFromJson("""
                {"id":777,"drivers":[
                  {"driver":{"id":"1"},"laps":[{"stintNum":1,"number":2,"time":29500}]}
                ]}
                """), 1_001_000L);

        LiveHeatUpdate heat = service.getSnapshot(1_001_100L).getHeat();

        // elapsed — на момент последнего сообщения; ageMs доводит его до запроса.
        assertThat(heat.getElapsed()).isEqualTo(11_000L);
        assertThat(heat.getPitstopTimeThreshold()).isEqualTo(70_000L);
        assertThat(heat.getPitlaneKarts()).containsExactly(10);
    }

    @Test
    @DisplayName("merge: явный пустой pitlaneKarts очищает питлейн")
    void merge_emptyPitlane_clears() {
        service.handleUpdate(service.parseFromJson(HEAT_777_FULL), 1_000_000L);
        service.handleUpdate(service.parseFromJson("""
                {"id":777,"pitlaneKarts":[],"drivers":[]}
                """), 1_001_000L);

        assertThat(service.getSnapshot(1_001_100L).getHeat().getPitlaneKarts()).isEmpty();
    }

    @Test
    @DisplayName("merge: одинаковый номер круга не дублируется")
    void merge_doesNotDuplicateLap() {
        service.handleUpdate(service.parseFromJson(HEAT_777_FULL), 1_000_000L);
        String delta = """
                {"id":777,"drivers":[
                  {"driver":{"id":"1"},"laps":[{"stintNum":1,"number":2,"time":29500}]}
                ]}
                """;
        service.handleUpdate(service.parseFromJson(delta), 1_001_000L);
        service.handleUpdate(service.parseFromJson(delta), 1_002_000L);

        assertThat(service.getSnapshot(1_002_100L).getHeat().getDrivers().get(0).getLaps())
                .hasSize(2);
    }

    @Test
    @DisplayName("merge: карт проставляется каждому кругу по номеру стинта")
    void merge_fillsKartForEveryLap() {
        service.handleUpdate(service.parseFromJson("""
                {"id":888,"drivers":[
                  {"driver":{"id":"1"},"stint":{"kart":9,"number":1,"length":1},
                   "laps":[{"stintNum":1,"number":1,"time":30000}]}
                ]}
                """), 1_000_000L);
        service.handleUpdate(service.parseFromJson("""
                {"id":888,"drivers":[
                  {"driver":{"id":"1"},"stint":{"kart":101,"number":2,"length":1},
                   "laps":[{"stintNum":2,"number":2,"time":31000}]}
                ]}
                """), 1_001_000L);

        LiveHeatUpdate.LiveDriver driver = service.getSnapshot(1_001_100L).getHeat().getDrivers().get(0);

        assertThat(driver.getLaps()).hasSize(2);
        assertThat(driver.getLaps().get(0).getKartNum()).isEqualTo(9);
        assertThat(driver.getLaps().get(1).getKartNum()).isEqualTo(101);
    }

    @Test
    @DisplayName("merge: новый id сессии начинает состояние заново")
    void merge_newSession_resetsState() {
        service.handleUpdate(service.parseFromJson(HEAT_777_FULL), 1_000_000L);
        service.handleUpdate(service.parseFromJson("""
                {"id":778,"drivers":[
                  {"driver":{"id":"3","name":"C"},"stint":{"kart":30,"number":1,"length":1},
                   "laps":[{"stintNum":1,"number":1,"time":32000}]}
                ]}
                """), 1_001_000L);

        LiveHeatUpdate heat = service.getSnapshot(1_001_100L).getHeat();

        assertThat(heat.getHeatId()).isEqualTo("778");
        assertThat(heat.getDrivers()).hasSize(1);
        assertThat(heat.getDrivers().get(0).getDriverId()).isEqualTo("3");
        assertThat(heat.getElapsed()).isNull();
    }

    // ------------------------------------------------------------------
    // Время заезда по receivedAt (сайт присылает elapsed = 0 для заездов)
    // ------------------------------------------------------------------

    private static final String HEAT_WITH_RECEIVED_AT = """
            {"id":900,"name":"Heat 9","elapsed":0,"mode":1,
             "drivers":[
               {"driver":{"id":"1","name":"A"},"number":1,"position":1,
                "stint":{"kart":9,"number":1,"length":2},
                "laps":[
                  {"stintNum":1,"number":1,"time":60000,"receivedAt":1000000},
                  {"stintNum":1,"number":2,"time":62000,"receivedAt":1062000}
                ]}
             ]}
            """;

    @Test
    @DisplayName("parse: читает receivedAt круга")
    void parse_readsLapReceivedAt() {
        LiveHeatUpdate update = service.parseFromJson(HEAT_WITH_RECEIVED_AT);

        assertThat(update.getDrivers().get(0).getLaps().get(0).getReceivedAt())
                .isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("live id: текущий заезд скрывается из списка только пока сессия свежая")
    void getLiveHeatId_onlyWhileFresh() {
        long now = System.currentTimeMillis();
        String fresh = HEAT_WITH_RECEIVED_AT
                .replace("\"receivedAt\":1000000", "\"receivedAt\":" + (now - 62_000))
                .replace("\"receivedAt\":1062000", "\"receivedAt\":" + now);

        service.handleUpdate(service.parseFromJson(fresh), now - 1_000);
        service.handleUpdate(service.parseFromJson(fresh), now);

        assertThat(service.getLiveHeatId()).isEqualTo("900");

        service.stopLive();

        assertThat(service.getLiveHeatId()).isNull();
    }

    @Test
    @DisplayName("heat: при elapsed=0 время считается от старта по receivedAt")
    void heatWithZeroElapsed_derivesTimeFromLapReceivedAt() {
        LiveHeatUpdate update = service.parseFromJson(HEAT_WITH_RECEIVED_AT);

        // Одно сообщение — это ещё не live, но общее время уже можно показать:
        // старт первого круга 1_000_000 - 60_000 = 940_000,
        // последний круг получен в 1_062_000.
        service.handleUpdate(update, 1_100_000L);
        LiveSnapshot waiting = service.getSnapshot(1_100_000L);

        assertThat(waiting.isLive()).isFalse();
        assertThat(waiting.getHeat().getElapsed()).isEqualTo(122_000L);
        assertThat(waiting.getStartedAt()).isEqualTo(940_000L);

        // В live время идёт до текущего момента, а не до последнего круга.
        service.handleUpdate(service.parseFromJson(HEAT_WITH_RECEIVED_AT), 1_110_000L);
        LiveSnapshot live = service.getSnapshot(1_110_000L);

        assertThat(live.isLive()).isTrue();
        assertThat(live.getHeat().getElapsed()).isEqualTo(170_000L);
        assertThat(live.getStartedAt()).isEqualTo(940_000L);
    }

    @Test
    @DisplayName("live: REST не прибавляет возраст сообщения к elapsed второй раз")
    void snapshot_doesNotDoubleCountAge() {
        service.handleUpdate(service.parseFromJson(HEAT_WITH_RECEIVED_AT), 1_100_000L);
        service.handleUpdate(service.parseFromJson(HEAT_WITH_RECEIVED_AT), 1_110_000L);

        LiveSnapshot later = service.getSnapshot(1_115_000L);

        assertThat(later.isLive()).isTrue();
        assertThat(later.getAgeMs()).isEqualTo(5_000L);
        // 170 000 на момент сообщения + 5 000 age добавит клиент, а не бэкенд.
        assertThat(later.getHeat().getElapsed()).isEqualTo(170_000L);
    }

    @Test
    @DisplayName("live: свежие сообщения со старыми кругами не раскручивают таймер")
    void oldLaps_doNotMakeSessionLive() {
        service.handleUpdate(service.parseFromJson(HEAT_WITH_RECEIVED_AT), 2_000_000L);
        service.handleUpdate(service.parseFromJson(HEAT_WITH_RECEIVED_AT), 2_005_000L);

        LiveSnapshot snapshot = service.getSnapshot(2_005_000L);

        assertThat(snapshot.isLive()).isFalse();
        assertThat(snapshot.getStatus()).isEqualTo(LiveTimingService.STATUS_WAITING);
        // Последний круг 1_062_000 - старт 940_000 = 122_000,
        // а не время повторного сообщения 2_005_000.
        assertThat(snapshot.getHeat().getElapsed()).isEqualTo(122_000L);
    }

    @Test
    @DisplayName("live: сессия завершается, когда круги перестали приходить")
    void staleLaps_endLiveSession() {
        service.handleUpdate(service.parseFromJson(HEAT_WITH_RECEIVED_AT), 1_100_000L);
        service.handleUpdate(service.parseFromJson(HEAT_WITH_RECEIVED_AT), 1_110_000L);
        assertThat(service.getSnapshot(1_110_000L).isLive()).isTrue();

        // Сообщение свежее, но последний круг старше stale-таймаута.
        service.handleUpdate(service.parseFromJson(HEAT_WITH_RECEIVED_AT), 1_300_000L);

        LiveSnapshot snapshot = service.getSnapshot(1_300_000L);
        assertThat(snapshot.isLive()).isFalse();
        assertThat(snapshot.getHeat().getElapsed()).isEqualTo(122_000L);
    }

    @Test
    @DisplayName("stale: checkStaleness фиксирует время на последнем круге")
    void checkStaleness_freezesElapsedAtLastLap() {
        service.handleUpdate(service.parseFromJson(HEAT_WITH_RECEIVED_AT), 1_100_000L);
        service.handleUpdate(service.parseFromJson(HEAT_WITH_RECEIVED_AT), 1_110_000L);

        long staleAt = 1_110_000L + STALE_MS + 1;
        service.checkStaleness(staleAt);

        LiveSnapshot snapshot = service.getSnapshot(staleAt);
        assertThat(snapshot.isLive()).isFalse();
        assertThat(snapshot.getHeat().getElapsed()).isEqualTo(122_000L);
    }

    @Test
    @DisplayName("live: гонка с elapsed от сайта тоже не удваивает возраст")
    void raceElapsed_isAgedOnlyOnce() {
        String race = """
                {"id":901,"name":"Финал","elapsed":60000,"mode":0,"drivers":[
                  {"driver":{"id":"1","name":"A"},"number":1,"position":1,
                   "laps":[{"stintNum":1,"number":1,"time":60000}]}]}
                """;

        String raceWithoutElapsed = """
                {"id":901,"name":"Финал","mode":0,"drivers":[
                  {"driver":{"id":"1","name":"A"},"number":1,"position":1,
                   "laps":[{"stintNum":1,"number":1,"time":60000}]}]}
                """;

        service.handleUpdate(service.parseFromJson(race), 1_000_000L);
        service.handleUpdate(service.parseFromJson(raceWithoutElapsed), 1_010_000L);

        LiveSnapshot snapshot = service.getSnapshot(1_015_000L);

        assertThat(snapshot.isLive()).isTrue();
        assertThat(snapshot.getHeat().getElapsed()).isEqualTo(70_000L);
        assertThat(snapshot.getAgeMs()).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("stale: ровно на границе таймаута сессия ещё жива")
    void staleBoundary_exactlyAtTimeoutStillLive() {
        service.handleUpdate(service.parseFromJson(HEAT_WITH_RECEIVED_AT), 1_100_000L);
        service.handleUpdate(service.parseFromJson(HEAT_WITH_RECEIVED_AT), 1_110_000L);

        LiveSnapshot atBoundary = service.getSnapshot(1_110_000L + STALE_MS);

        assertThat(atBoundary.isLive()).isTrue();
        assertThat(atBoundary.getStatus()).isEqualTo(LiveTimingService.STATUS_LIVE);
        assertThat(atBoundary.getHeat().getElapsed()).isEqualTo(170_000L);
    }

    @Test
    @DisplayName("stop: ручная остановка даёт FINISHED и фиксирует время")
    void manualStop_marksSessionFinished() {
        service.handleUpdate(service.parseFromJson(HEAT_WITH_RECEIVED_AT), 1_100_000L);
        service.handleUpdate(service.parseFromJson(HEAT_WITH_RECEIVED_AT), 1_110_000L);
        assertThat(service.getSnapshot(1_110_000L).isLive()).isTrue();

        assertThat(service.stopLive()).isTrue();

        LiveSnapshot stopped = service.getSnapshot(1_110_000L);
        assertThat(stopped.isLive()).isFalse();
        assertThat(stopped.getStatus()).isEqualTo(LiveTimingService.STATUS_FINISHED);
        assertThat(stopped.getHeat().getElapsed()).isEqualTo(122_000L);
    }
}
