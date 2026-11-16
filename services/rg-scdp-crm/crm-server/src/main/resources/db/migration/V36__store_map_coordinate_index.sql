-- Fixed map tiles constrain source coordinates; keep cold tile loads from scanning the tenant.
CREATE INDEX ix_store_map_coordinates
    ON crm_store_master (tenant_id, longitude, latitude, id);
