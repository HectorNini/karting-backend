package ninibayr.karting.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveTimingClientTest {

    @Test
    @DisplayName("WS-сообщение: фрагменты собираются в целый JSON")
    void accumulator_joinsFragments() {
        var accumulator = new LiveTimingClient.MessageAccumulator();

        assertThat(accumulator.append("{\"id\":", false)).isNull();
        assertThat(accumulator.append("136223}", true)).isEqualTo("{\"id\":136223}");
        assertThat(accumulator.isDiscarding()).isFalse();
    }

    @Test
    @DisplayName("WS-сообщение: слишком большой JSON отбрасывается до последнего фрагмента")
    void accumulator_discardsOversizedMessage() {
        var accumulator = new LiveTimingClient.MessageAccumulator();
        String oversized = "x".repeat(LiveTimingClient.MessageAccumulator.MAX_MESSAGE_CHARS + 1);

        assertThat(accumulator.append(oversized, false)).isNull();
        assertThat(accumulator.isDiscarding()).isTrue();
        assertThat(accumulator.append("мусор", false)).isNull();
        assertThat(accumulator.append("хвост", true)).isNull();
        assertThat(accumulator.isDiscarding()).isFalse();
        assertThat(accumulator.append("ok", true)).isEqualTo("ok");
    }

    @Test
    @DisplayName("WS-сообщение: валидный JSON уходит в сервис, битый не роняет клиент")
    void processMessage_delegatesAndSwallowsErrors() {
        LiveTimingService service = mock(LiveTimingService.class);

        LiveTimingClient.processMessage(service, "{\"id\":1}");
        verify(service).handleRawMessage("{\"id\":1}");

        when(service.handleRawMessage("not json")).thenThrow(new IllegalStateException("boom"));
        assertThatCode(() -> LiveTimingClient.processMessage(service, "not json"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("WS-колбэки: open/close и error обновляют статус и запускают reconnect")
    void listener_updatesConnectionStateAndSchedulesReconnect() {
        LiveTimingService service = mock(LiveTimingService.class);
        SitePollingSchedule schedule = mock(SitePollingSchedule.class);
        LiveTimingClient client = new LiveTimingClient(service, schedule, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(client, "trackSlug", "narvskaya");

        WebSocket socket = mock(WebSocket.class);
        when(socket.sendText(eq("listen track:narvskaya"), eq(true)))
                .thenReturn(CompletableFuture.completedFuture(null));

        var listener = client.newListener(0);
        listener.onOpen(socket);

        assertThat(client.isConnected()).isTrue();
        assertThat(client.getAttempt()).isZero();
        verify(socket).sendText("listen track:narvskaya", true);

        listener.onClose(socket, 1000, "done");

        assertThat(client.isConnected()).isFalse();
        assertThat(client.getAttempt()).isEqualTo(1);
        client.stop();
    }

    @Test
    @DisplayName("WS-колбэки: ошибка соединения сохраняет причину и планирует reconnect")
    void listener_onErrorStoresReasonAndReconnects() {
        LiveTimingClient client = new LiveTimingClient(
                mock(LiveTimingService.class), mock(SitePollingSchedule.class), new SimpleMeterRegistry());
        WebSocket socket = mock(WebSocket.class);

        client.newListener(0).onError(socket, new RuntimeException("network down"));

        assertThat(client.isConnected()).isFalse();
        assertThat(client.getLastError()).isEqualTo("network down");
        assertThat(client.getAttempt()).isEqualTo(1);
        client.stop();
    }

    @Test
    @DisplayName("WS-колбэки: старая генерация игнорирует сообщения")
    void listener_ignoresMessagesFromOldGeneration() {
        LiveTimingService service = mock(LiveTimingService.class);
        LiveTimingClient client = new LiveTimingClient(
                service, mock(SitePollingSchedule.class), new SimpleMeterRegistry());
        var listener = client.newListener(1);
        ReflectionTestUtils.setField(client, "generation", 2);

        listener.onText(mock(WebSocket.class), "{\"id\":1}", true);

        verify(service, never()).handleRawMessage(anyString());
        client.stop();
    }

    @Test
    @DisplayName("reconnect: повторный вызов не планирует вторую попытку")
    void scheduleReconnect_deduplicates() {
        LiveTimingClient client = new LiveTimingClient(
                mock(LiveTimingService.class), mock(SitePollingSchedule.class), new SimpleMeterRegistry());

        client.scheduleReconnect();
        client.scheduleReconnect();

        assertThat(client.getAttempt()).isEqualTo(1);
        client.stop();
    }
}
