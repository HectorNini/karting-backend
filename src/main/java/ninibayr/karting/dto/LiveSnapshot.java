package ninibayr.karting.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Состояние live-тайминга: идёт ли гонка прямо сейчас и её последний снимок.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LiveSnapshot {
    /** true — прямо сейчас идёт сессия и данные продолжают поступать. */
    private boolean live;
    /** LIVE | WAITING | FINISHED | IDLE */
    private String status;
    /** Последнее полученное состояние (может быть от уже завершённой сессии). */
    private LiveHeatUpdate heat;
    /** Время последнего сообщения от сайта (epoch ms). */
    private Long lastUpdateAt;
    /** Сколько миллисекунд назад пришло последнее сообщение. */
    private Long ageMs;
    /** Оценка момента старта сессии (epoch ms). */
    private Long startedAt;
    /** Сколько сообщений получено для текущей сессии. */
    private Integer messages;
    /** Через сколько тишины сессия считается завершённой. */
    private Long staleTimeoutMs;
}
