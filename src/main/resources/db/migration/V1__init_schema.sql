-- 1. Таблица трасс
CREATE TABLE tracks (
                        id BIGSERIAL PRIMARY KEY,
                        slug VARCHAR(50) UNIQUE NOT NULL,
                        name VARCHAR(100) NOT NULL
);

-- 2. Таблица гонщиков
CREATE TABLE drivers (
                         id BIGSERIAL PRIMARY KEY,
                         site_id VARCHAR(50) NOT NULL,
                         name VARCHAR(100) NOT NULL,
                         track_id BIGINT REFERENCES tracks(id),
                         UNIQUE(site_id, track_id)
);

-- 3. Таблица заездов
CREATE TABLE heats (
                       id BIGSERIAL PRIMARY KEY,
                       site_id VARCHAR(50) UNIQUE NOT NULL,
                       track_id BIGINT REFERENCES tracks(id),
                       name VARCHAR(200) NOT NULL,
                       race_date DATE,
                       race_time TIME,
                       last_updated TIMESTAMP
);

-- 4. Таблица кругов
CREATE TABLE laps (
                      id BIGSERIAL PRIMARY KEY,
                      heat_id BIGINT REFERENCES heats(id) ON DELETE CASCADE,
                      driver_id BIGINT REFERENCES drivers(id),
                      kart_number INT,
                      lap_number INT,
                      lap_time VARCHAR(50),
                      position VARCHAR(10)
);

-- Индексы для производительности
CREATE INDEX idx_laps_heat_id ON laps(heat_id);
CREATE INDEX idx_laps_driver_id ON laps(driver_id);
CREATE INDEX idx_heats_track_id ON heats(track_id);

-- 5. ИНИЦИАЛИЗАЦИЯ ДАННЫХ (То, что мы хотим автоматизировать)
INSERT INTO tracks (slug, name) VALUES ('narvskaya', 'Narvskaya') ON CONFLICT (slug) DO NOTHING;