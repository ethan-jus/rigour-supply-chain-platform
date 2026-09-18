-- A user-confirmed duplicate can keep multiple source IDs without alternating ownership on each sync.
ALTER TABLE crm_source_binding
    ADD COLUMN primary_customer_source_id VARCHAR(128) NULL
        COMMENT '合并客户的主订货宝来源ID；NULL表示本记录负责主档投影',
    ADD COLUMN primary_projection_target_id BINARY(16)
        GENERATED ALWAYS AS (CASE WHEN primary_customer_source_id IS NULL THEN target_id ELSE NULL END) STORED,
    DROP INDEX uk_crm_source_binding_target,
    ADD UNIQUE KEY uk_crm_source_binding_target
        (tenant_id, connector_id, source_system, target_type, primary_projection_target_id),
    ADD CONSTRAINT ck_crm_customer_merge_primary CHECK
        (primary_customer_source_id IS NULL OR
            (source_object_type = 'CUSTOMER' AND target_type = 'PARTY'
                AND primary_customer_source_id <> source_object_id));
