-- Добавляем номер круга в гонке, индекс стинта и позицию в гонке.
ALTER TABLE laps ADD COLUMN IF NOT EXISTS race_lap_number INT;
ALTER TABLE laps ADD COLUMN IF NOT EXISTS stint_index INT;
ALTER TABLE laps ADD COLUMN IF NOT EXISTS race_position VARCHAR(20);

CREATE INDEX IF NOT EXISTS idx_laps_heat_racelap ON laps(heat_id, race_lap_number);
