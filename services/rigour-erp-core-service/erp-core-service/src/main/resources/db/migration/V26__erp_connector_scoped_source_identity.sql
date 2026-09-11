-- ERP 外部来源幂等键补充 Integration connector 维度。
-- erp_master_source_binding 是外部来源绑定表，connector_id 保持非空以避免 MySQL UNIQUE + NULL 放开重复。
-- 出库/调拨业务表保留 NULL，兼容人工单据和旧历史单据；订货宝同步入口在服务层强制非空。

ALTER TABLE erp_master_source_binding
    ADD COLUMN connector_id CHAR(36) NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'
        COMMENT 'Integration连接器ID；旧来源绑定无法追溯时使用全0占位' AFTER tenant_id;

UPDATE erp_master_source_binding binding
    JOIN erp_master_data_sync_run run
      ON run.tenant_id = binding.tenant_id
     AND run.id = binding.last_sync_run_id
   SET binding.connector_id = run.connector_id
 WHERE run.connector_id IS NOT NULL
   AND binding.connector_id = '00000000-0000-0000-0000-000000000000';

ALTER TABLE erp_master_source_binding
    DROP INDEX uk_erp_master_source_binding_source,
    DROP INDEX uk_erp_master_source_binding_target,
    ADD UNIQUE KEY uk_erp_master_source_binding_source (
        tenant_id, connector_id, source_system, source_object_type, source_object_id
    ),
    ADD UNIQUE KEY uk_erp_master_source_binding_target (
        tenant_id, connector_id, source_system, target_type, target_id
    ),
    ADD KEY idx_erp_master_source_binding_connector (
        tenant_id, connector_id, source_system, source_object_type
    );

ALTER TABLE erp_stock_out_order
    ADD COLUMN connector_id CHAR(36) NULL
        COMMENT 'Integration连接器ID；订货宝同步非空，人工或旧历史单据可为空' AFTER stock_out_no,
    DROP INDEX uk_erp_stock_out_source,
    DROP INDEX idx_erp_stock_out_source,
    ADD UNIQUE KEY uk_erp_stock_out_source (
        tenant_id, connector_id, source_system_code, source_document_no
    ),
    ADD KEY idx_erp_stock_out_source (
        tenant_id, connector_id, source_system_code, source_document_no
    );

ALTER TABLE erp_transfer_order
    ADD COLUMN connector_id CHAR(36) NULL
        COMMENT 'Integration连接器ID；订货宝同步非空，人工或旧历史单据可为空' AFTER transfer_no,
    DROP INDEX uk_erp_transfer_source,
    DROP INDEX idx_erp_transfer_external_source,
    ADD UNIQUE KEY uk_erp_transfer_source (
        tenant_id, connector_id, source_system_code, source_document_no
    ),
    ADD KEY idx_erp_transfer_external_source (
        tenant_id, connector_id, source_system_code, source_document_no
    );
