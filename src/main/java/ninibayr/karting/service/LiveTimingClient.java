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
    /** Увеличивается при каждом новом подключении: старые колбэки игнорируются. */
    private volatile int generation;
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
        // Сначала invalidation старых колбэков, затем закрытие старого сокета:
        // его onClose не должен запланировать лишнее переподключение.
        int connectionGeneration = ++generation;
        closeSocket();
        try {
            log.debug("Live WS: попытка подключения {} к {}", attempt + 1, wsUrl);
            httpClient.newWebSocketBuilder()
                    .header("Origin", origin)
                    .connectTimeout(Duration.ofSeconds(20))
                    .buildAsync(URI.create(wsUrl), new Listener(connectionGeneration))
                    .whenComplete((ws, error) -> {
                        connecting.set(false);
                        if (connectionGeneration != generation) {
                            return;
                        }
                        if (error != null) {
                            lastError = rootMessage(error);
                            log.warn("Live WS: не удалось подключиться: {}", lastError);
                            scheduleReconnect();
                        }
                    });
        } catch (Exception e) {
            connecting.set(false);
            if (connectionGeneration != generation) {
                return;
            }
            lastError = rootMessage(e);
            log.warn("Live WS: ошибка подключения: {}", lastError);
            scheduleReconnect();
        }
    }

    /** Пакетно-доступно для тестов reconnect-логики без реальной сети. */
    void scheduleReconnect() {
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

    /** Фабрика для тестов: колбэки можно проверять без реального подключения. */
    WebSocket.Listener newListener(int listenerGeneration) {
        return new Listener(listenerGeneration);
    }

    /**
     * Разбор и передача одного целого сообщения в сервис. Вынесено из listener,
     * чтобы можно было проверить обработку битых JSON без WebSocket.
     */
    static void processMessage(LiveTimingService service, String message) {
        try {
            service.handleRawMessage(message);
        } catch (Exception e) {
            log.debug("Live WS: ошибка обработки сообщения: {}", e.getMessage());
        }
    }

    /**
     * Накопление фрагментов WebSocket-сообщения. Слишком большие сообщения
     * отбрасываются до следующего флага {@code last}, чтобы не съесть хвост.
     */
    static final class MessageAccumulator {

        static final int MAX_MESSAGE_CHARS = 1_000_000;

        private final StringBuilder buffer = new StringBuilder();
        private boolean discarding;

        /** Возвращает целое сообщение, когда пришел последний фрагмент. */
        String append(CharSequence data, boolean last) {
            if (discarding) {
                if (last) {
                    discarding = false;
                }
                return null;
            }
            buffer.append(data);
            if (buffer.length() > MAX_MESSAGE_CHARS) {
                log.warn("Live WS: сообщение больше {} символов, отбрасываем", MAX_MESSAGE_CHARS);
                buffer.setLength(0);
                discarding = !last;
                return null;
            }
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                return message;
            }
            return null;
        }

        boolean isDiscarding() {
            return discarding;
        }
    }

    private final class Listener implements WebSocket.Listener {

        private final int listenerGeneration;
        private final MessageAccumulator messages = new MessageAccumulator();

        private Listener(int listenerGeneration) {
            this.listenerGeneration = listenerGeneration;
        }

        private boolean isCurrent() {
            return listenerGeneration == generation;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            if (!isCurrent()) {
                webSocket.abort();
                return;
            }
            socket = webSocket;
            connected = true;
            attempt = 0;
            lastError = null;
            connectedAt = System.currentTimeMillis();
            log.info("Live WS: подключено к {}", wsUrl);

            String listen = "listen track:" + trackSlug;
            try {
                webSocket.sendText(listen, true).whenComplete((unused, error) -> {
                    if (error != null && isCurrent()) {
                        log.warn("Live WS: не удалось отправить подписку: {}", rootMessage(error));
                        closeSocket();
                        scheduleReconnect();
                    }
                });
                log.info("Live WS: отправлено \"{}\"", listen);
            } catch (Exception e) {
                log.warn("Live WS: не удалось отправить подписку: {}", rootMessage(e));
                closeSocket();
                scheduleReconnect();
            }
            webSocket.request(Long.MAX_VALUE);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (!isCurrent()) {
                return null;
            }
            String message = messages.append(data, last);
            if (message != null) {
                processMessage(liveTimingService, message);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!isCurrent()) {
                return null;
            }
            connected = false;
            if (socket == webSocket) {
                socket = null;
            }
            log.warn("Live WS: соединение закрыто ({} {})", statusCode, reason);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (!isCurrent()) {
                return;
            }
            connected = false;
            if (socket == webSocket) {
                socket = null;
            }
            lastError = rootMessage(error);
            log.warn("Live WS: ошибка соединения: {}", lastError);
            scheduleReconnect();
        }
    }
}
