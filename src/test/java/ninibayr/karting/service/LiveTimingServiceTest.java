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
        service = new LiveTimingService(null, new ObjectMapper(), null, "narvskaya", STALE_MS / 1000);
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
}
