package ninibayr.karting.exception;

/**
 * Сайт тайминга временно ограничил запросы (429/403/5xx) или локальный
 * ограничитель решил, что сейчас запрос отправлять нельзя.
 *
 * <p>В отличие от обычной ошибки синхронизации, это не 500: клиенту нужно
 * вернуть 503 с заголовком {@code Retry-After}.</p>
 */
public class SiteRateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;
    private final int upstreamStatus;

    public SiteRateLimitedException(String message, long retryAfterSeconds, int upstreamStatus) {
        super(message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
        this.upstreamStatus = upstreamStatus;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public int getUpstreamStatus() {
        return upstreamStatus;
    }
}
