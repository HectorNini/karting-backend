package ninibayr.karting.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.LocalTime;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Держит постоянное подключение к WebSocket сайта тайминга и передаёт
 * сообщения в {@link LiveTimingService}.
 *
 * <p>Протокол сайта: после подключения нужно отправить строку
 * {@code listen track:<slug>}, после чего сервер присылает JSON-снимки.
 * Подключение переустанавливается с нарастающей паузой.</p>
 */
@Component
@Slf4j
public class LiveTimingClient {

    private static final long[] BACKOFF_MS = {2_000, 5_000, 10_000, 15_000, 20_000, 30_000, 45_000, 60_000};

    private final LiveTimingService liveTimingService;
    private final SitePollingSchedule schedule;

    @Value("${karting.live.enabled:true}")
    private boolean enabled;

    @Value("${karting.live.ws-url:wss://timing.batyrshin.name/ws}")
    private String wsUrl;

    @Value("${karting.live.origin:https://timing.batyrshin.name}")
    private String origin;

    @Value("${karting.track.slug:narvskaya}")
    private String trackSlug;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "live-timing-ws");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final Counter reconnects;

    private volatile WebSocket socket;
    private volatile boolean connected;
    private volatile int attempt;
    private volatile String lastError;
    private volatile long connectedAt;
    private volatile boolean shuttingDown;

    public LiveTimingClient(LiveTimingService liveTimingService, SitePollingSchedule schedule,
                            MeterRegistry meterRegistry) {
        this.liveTimingService = liveTimingService;
        this.schedule = schedule;
        Gauge.builder("karting.live.ws.connected", this, c -> c.connected ? 1 : 0)
                .description("Подключён ли WebSocket к live-таймингу (1/0)")
                .register(meterRegistry);
        this.reconnects = meterRegistry.counter("karting.live.ws.reconnects");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            log.info("Live-тайминг отключён (karting.live.enabled=false)");
            return;
        }
        log.info("Подключаемся к live-таймингу: {}", wsUrl);
        connect();
    }

    @PreDestroy
    public void stop() {
        shuttingDown = true;
        closeSocket();
        scheduler.shutdownNow();
    }

    private void connect() {
        if (shuttingDown) {
            return;
        }
        if (!connecting.compareAndSet(false, true)) {
            return;
        }
        closeSocket();
        try {
            log.debug("Live WS: попытка подключения {} к {}", attempt + 1, wsUrl);
            httpClient.newWebSocketBuilder()
                    .header("Origin", origin)
                    .connectTimeout(Duration.ofSeconds(20))
                    .buildAsync(URI.create(wsUrl), new Listener())
                    .whenComplete((ws, error) -> {
                        connecting.set(false);
                        if (error != null) {
                            lastError = rootMessage(error);
                            log.warn("Live WS: не удалось подключиться: {}", lastError);
                            scheduleReconnect();
                        }
                    });
        } catch (Exception e) {
            connecting.set(false);
            lastError = rootMessage(e);
            log.warn("Live WS: ошибка подключения: {}", lastError);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (shuttingDown) {
            return;
        }
        if (!reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        int index = Math.min(attempt, BACKOFF_MS.length - 1);
        long delay = BACKOFF_MS[index];
        // Ночью сессий нет: не долбим сайт каждую минуту, ждём дольше.
        if (schedule.isNight(LocalTime.now())) {
            delay = Math.max(delay, schedule.nightReconnectDelay().toMillis());
        }
        attempt++;
        reconnects.increment();
        log.info("Live WS: переподключение через {} мс (попытка {})", delay, attempt);
        scheduler.schedule(() -> {
            reconnectScheduled.set(false);
            connect();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void closeSocket() {
        WebSocket ws = socket;
        socket = null;
        connected = false;
        if (ws != null) {
            try {
                ws.abort();
            } catch (Exception ignored) {
                // соединение уже закрыто
            }
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg != null ? msg : t.getClass().getSimpleName();
    }

    // ------------------------------------------------------------------
    // Статус для API
    // ------------------------------------------------------------------

    public boolean isConnected() {
        return connected;
    }

    public String getWsUrl() {
        return wsUrl;
    }

    public int getAttempt() {
        return attempt;
    }

    public String getLastError() {
        return lastError;
    }

    public long getConnectedAt() {
        return connectedAt;
    }

    // ------------------------------------------------------------------
    // WebSocket listener
    // ------------------------------------------------------------------

    private final class Listener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            connected = true;
            attempt = 0;
            lastError = null;
            connectedAt = System.currentTimeMillis();
            log.info("Live WS: подключено к {}", wsUrl);

            String listen = "listen track:" + trackSlug;
            try {
                webSocket.sendText(listen, true);
                log.info("Live WS: отправлено \"{}\"", listen);
            } catch (Exception e) {
                log.warn("Live WS: не удалось отправить подписку: {}", e.getMessage());
            }
            webSocket.request(Long.MAX_VALUE);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                try {
                    liveTimingService.handleRawMessage(message);
                } catch (Exception e) {
                    log.debug("Live WS: ошибка обработки сообщения: {}", e.getMessage());
                }
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            connected = false;
            socket = null;
            log.warn("Live WS: соединение закрыто ({} {})", statusCode, reason);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            connected = false;
            socket = null;
            lastError = rootMessage(error);
            log.warn("Live WS: ошибка соединения: {}", lastError);
            scheduleReconnect();
        }
    }
}
