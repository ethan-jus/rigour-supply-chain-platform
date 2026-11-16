-- Client-reported original coordinates, kept separately from normalized map coordinates.
-- Historical points remain NULL; no synthetic original GPS evidence is backfilled.
ALTER TABLE sales_location_point
    ADD COLUMN raw_longitude DECIMAL(12,8) NULL,
    ADD COLUMN raw_latitude DECIMAL(12,8) NULL,
    ADD COLUMN raw_coordinate_system VARCHAR(12) NULL;
