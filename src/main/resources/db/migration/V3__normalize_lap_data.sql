-- Migration to normalize lap data and add fields
ALTER TABLE laps ADD COLUMN IF NOT EXISTS lap_time_millis BIGINT;
ALTER TABLE laps ADD COLUMN IF NOT EXISTS gap_to_leader VARCHAR(50);
ALTER TABLE laps ADD COLUMN IF NOT EXISTS gap_to_previous VARCHAR(50);
ALTER TABLE laps ADD COLUMN IF NOT EXISTS best_lap_overall BOOLEAN;
ALTER TABLE laps ADD COLUMN IF NOT EXISTS best_lap_stint BOOLEAN;

CREATE INDEX IF NOT EXISTS idx_laps_heat_lapnum ON laps(heat_id, lap_number);
CREATE INDEX IF NOT EXISTS idx_laps_time_millis ON laps(lap_time_millis);
