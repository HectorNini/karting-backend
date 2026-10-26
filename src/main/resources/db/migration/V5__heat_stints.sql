-- Стинты: отрезки заезда, которые гонщик проехал на одном карте.
-- В отличие от laps, здесь одна строка на смену карта, а не на круг.
CREATE TABLE IF NOT EXISTS heat_stints (
                                       id BIGSERIAL PRIMARY KEY,
                                       heat_id BIGINT NOT NULL REFERENCES heats(id) ON DELETE CASCADE,
                                       driver_id BIGINT REFERENCES drivers(id),
                                       stint_number INT NOT NULL,
                                       kart_number INT,
                                       lap_from INT,
                                       lap_to INT,
                                       laps_count INT,
                                       best_lap_millis BIGINT,
                                       avg_lap_millis BIGINT,
                                       dev_millis BIGINT,
                                       UNIQUE (heat_id, driver_id, stint_number)
);

CREATE INDEX IF NOT EXISTS idx_heat_stints_heat ON heat_stints(heat_id);

-- Старые stint_index посчитаны эвристикой «P1 снова» и неверны (0-based, лишние
-- смены). Сбрасываем: интерфейс не будет показывать ложные карты, а заезды
-- дозаполнятся пересинхронизацией с сайта.
UPDATE laps SET stint_index = NULL;
