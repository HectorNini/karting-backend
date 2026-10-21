ALTER TABLE laps ADD COLUMN column_index INT;
CREATE INDEX idx_laps_heat_column ON laps(heat_id, column_index);
