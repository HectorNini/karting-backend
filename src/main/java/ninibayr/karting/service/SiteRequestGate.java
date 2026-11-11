package ninibayr.karting.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import ninibayr.karting.exception.SiteRateLimitedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Единая точка ограничения исходящих HTTP-запросов к сайту тайминга.
 *
 * <p>Задачи:</p>
 * <ul>
 *   <li>не отправлять запросы чаще, чем раз в {@code min-request-interval-ms};</li>
 *   <li>при 429/403/5xx включать общий cooldown для всего приложения;</li>
 *   <li>уважать заголовок {@code Retry-After}, если сайт его прислал;</li>
 *   <li>не давать фоновому прогреву и шедулеру «долбить» сайт после ошибки.</li>
 * </ul>
 *
 * <p>Cooldown хранится в памяти процесса. После перезапуска он сбрасывается,
 * но первый же ответ 429 снова его включит.</p>
 */
@Component
@Slf4j
public class SiteRequestGate {

    private final long minIntervalMs;
    private final long baseCooldownMs;
    private final long maxCooldownMs;

    private final Counter rateLimitedCounter;

    /** Сериализует выдачу «слотов» на отправку запросов. */
    private final ReentrantLock slotLock = new ReentrantLock(true);

    /** Время, раньше которого следующий запрос отправлять нельзя. */
    private final AtomicLong nextAllowedAt = new AtomicLong();

    /** Время, до которого сайт явно попросил не присылать запросы. */
    private final AtomicLong cooldownUntil = new AtomicLong();

    /** Сколько подряд было ошибок 429/403/5xx/сети. */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /** После rate limit фоновые задачи не трогают сайт дольше, чем пользователь. */
    private volatile long backgroundPauseUntil;

    public SiteRequestGate(
            @Value("${karting.site.min-request-interval-ms:1500}") long minIntervalMs,
            @Value("${karting.site.base-cooldown-ms:60000}") long baseCooldownMs,
            @Value("${karting.site.max-cooldown-ms:600000}") long maxCooldownMs,
            MeterRegistry meterRegistry) {
        this.minIntervalMs = Math.max(0, minIntervalMs);
        this.baseCooldownMs = Math.max(1_000, baseCooldownMs);
        this.maxCooldownMs = Math.max(this.baseCooldownMs, maxCooldownMs);
        this.rateLimitedCounter = meterRegistry.counter("karting.site.rate_limited");
    }

    /**
     * Ждёт разрешённый слот или сразу бросает исключение, если сайт на cooldown.
     * Вызывать непосредственно перед HTTP-запросом.
     */
    public void beforeRequest() {
        long now = System.currentTimeMillis();
        long until = cooldownUntil.get();
        if (now < until) {
            throw cooldownException(until, now, 0);
        }

        boolean locked = false;
        try {
            locked = slotLock.tryLock(5, TimeUnit.SECONDS);
            if (!locked) {
                throw new SiteRateLimitedException(
                        "Сайт тайминга сейчас занят синхронизацией. Повторите через несколько секунд.",
                        5, 0);
            }

            now = System.currentTimeMillis();
            until = cooldownUntil.get();
            if (now < until) {
                throw cooldownException(until, now, 0);
            }

            long allowedAt = nextAllowedAt.get();
            if (allowedAt > now) {
                long waitMs = allowedAt - now;
                if (waitMs > 10_000) {
                    throw new SiteRateLimitedException(
                            "Сайт тайминга перегружен запросами. Повторите позже.",
                            (waitMs + 999) / 1_000, 0);
                }
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SiteRateLimitedException(
                            "Запрос к сайту тайминга был прерван. Повторите позже.", 1, 0);
                }
            }

            nextAllowedAt.set(System.currentTimeMillis() + minIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SiteRateLimitedException(
                    "Запрос к сайту тайминга был прерван. Повторите позже.", 1, 0);
        } finally {
            if (locked) {
                slotLock.unlock();
            }
        }
    }

    /** Успешный HTTP-ответ: сбрасываем счётчик подряд идущих ошибок. */
    public void onSuccess() {
        consecutiveFailures.set(0);
    }

    /** 429/403/5xx: включаем cooldown и возвращаем исключение для клиента. */
    public SiteRateLimitedException rateLimited(int status, String retryAfterHeader) {
        int failures = consecutiveFailures.incrementAndGet();
        long now = System.currentTimeMillis();

        long delayMs = parseRetryAfterMs(retryAfterHeader);
        if (delayMs <= 0) {
            delayMs = backoffMs(failures);
        }
        delayMs = Math.min(delayMs, maxCooldownMs);

        long until = now + delayMs;
        cooldownUntil.accumulateAndGet(until, Math::max);

        // Фон не должен занимать первый же слот после окончания cooldown:
        // иначе он снова получит 429 и сайт никогда не «отпустит» IP.
        long backgroundDelayMs = Math.min(maxCooldownMs, Math.max(delayMs * 5, 300_000L));
        backgroundPauseUntil = Math.max(backgroundPauseUntil, now + backgroundDelayMs);

        rateLimitedCounter.increment();

        long seconds = Math.max(1, (delayMs + 999) / 1_000);
        log.warn("Сайт тайминга ответил HTTP {}, cooldown {} с (подряд ошибок: {})",
                status, seconds, failures);
        return new SiteRateLimitedException(messageFor(status, seconds), seconds, status);
    }

    /** Сетевая ошибка/таймаут: тоже делаем паузу, чтобы не повторять сразу. */
    public void transportError() {
        int failures = consecutiveFailures.incrementAndGet();
        long delayMs = Math.min(backoffMs(failures), maxCooldownMs);
        long now = System.currentTimeMillis();
        long until = now + delayMs;
        cooldownUntil.accumulateAndGet(until, Math::max);
        backgroundPauseUntil = Math.max(backgroundPauseUntil, now + Math.min(maxCooldownMs, delayMs * 5));
        log.warn("Сетевая ошибка при запросе к сайту тайминга, cooldown {} мс", delayMs);
    }

    public boolean isRateLimitStatus(int status) {
        return status == 429 || status == 403 || status >= 500;
    }

    public boolean isCoolingDown() {
        return System.currentTimeMillis() < cooldownUntil.get();
    }

    public long remainingCooldownSeconds() {
        long remaining = cooldownUntil.get() - System.currentTimeMillis();
        return remaining <= 0 ? 0 : (remaining + 999) / 1_000;
    }

    /** После rate limit фоновому прогреву и шедулеру лучше подождать дольше. */
    public boolean isBackgroundPaused() {
        return System.currentTimeMillis() < backgroundPauseUntil;
    }

    public long remainingBackgroundPauseSeconds() {
        long remaining = backgroundPauseUntil - System.currentTimeMillis();
        return remaining <= 0 ? 0 : (remaining + 999) / 1_000;
    }

    private long backoffMs(int failures) {
        int exponent = Math.min(Math.max(0, failures - 1), 10);
        long multiplier = 1L << exponent;
        return Math.min(maxCooldownMs, baseCooldownMs * multiplier);
    }

    private SiteRateLimitedException cooldownException(long until, long now, int status) {
        long seconds = Math.max(1, (until - now + 999) / 1_000);
        rateLimitedCounter.increment();
        return new SiteRateLimitedException(
                messageFor(status, seconds), seconds, status);
    }

    private static String messageFor(int status, long seconds) {
        if (status == 429) {
            return "Сайт тайминга временно ограничил запросы (HTTP 429). Повторите через "
                    + seconds + " сек.";
        }
        if (status == 403) {
            return "Сайт тайминга временно заблокировал запросы (HTTP 403). Повторите через "
                    + seconds + " сек.";
        }
        if (status >= 500) {
            return "Сайт тайминга временно недоступен (HTTP " + status + "). Повторите через "
                    + seconds + " сек.";
        }
        return "Сайт тайминга временно перегружен. Повторите через " + seconds + " сек.";
    }

    private static long parseRetryAfterMs(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        String text = value.trim();
        try {
            return Math.max(0, Long.parseLong(text) * 1_000L);
        } catch (NumberFormatException ignored) {
            // Может быть HTTP-дата.
        }
        try {
            ZonedDateTime date = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME);
            long delay = Duration.between(Instant.now(), date.toInstant()).toMillis();
            return Math.max(0, delay);
        } catch (Exception ignored) {
            return -1;
        }
    }
}
