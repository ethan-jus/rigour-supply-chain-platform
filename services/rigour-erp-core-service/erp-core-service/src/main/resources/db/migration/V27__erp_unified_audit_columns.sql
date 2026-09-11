-- ERP V27：统一仍在使用的 ERP 业务/同步表本地审计字段命名。
-- source_created_at/source_updated_at 是订货宝来源证据字段，保留原名。

ALTER TABLE erp_master_data_sync_checkpoint RENAME COLUMN version TO revision;
ALTER TABLE erp_master_data_sync_checkpoint RENAME COLUMN created_at TO created_time;
ALTER TABLE erp_master_data_sync_checkpoint RENAME COLUMN updated_at TO updated_time;
ALTER TABLE erp_master_data_sync_checkpoint ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE erp_master_data_sync_checkpoint ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE erp_master_data_sync_checkpoint ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE erp_master_source_binding RENAME COLUMN version TO revision;
ALTER TABLE erp_master_source_binding RENAME COLUMN created_at TO created_time;
ALTER TABLE erp_master_source_binding RENAME COLUMN updated_at TO updated_time;
ALTER TABLE erp_master_source_binding ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE erp_master_source_binding ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE erp_master_source_binding ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE erp_master_data_sync_run RENAME COLUMN created_at TO created_time;
ALTER TABLE erp_master_data_sync_run RENAME COLUMN updated_at TO updated_time;
ALTER TABLE erp_master_data_sync_run ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER finished_at;
ALTER TABLE erp_master_data_sync_run ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE erp_master_data_sync_run ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE erp_master_data_sync_lock ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER expires_at;
ALTER TABLE erp_master_data_sync_lock ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE erp_master_data_sync_lock ADD COLUMN created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间' AFTER created_by;
ALTER TABLE erp_master_data_sync_lock ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE erp_master_data_sync_lock ADD COLUMN updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间' AFTER updated_by;
ALTER TABLE erp_master_data_sync_lock ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE erp_procurement_order_line ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER remark;
ALTER TABLE erp_procurement_order_line ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE erp_procurement_order_line ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;

ALTER TABLE erp_purchase_return_order_line ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER remark;
ALTER TABLE erp_purchase_return_order_line ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE erp_purchase_return_order_line ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;

ALTER TABLE erp_stock_balance ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE erp_stock_balance ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE erp_stock_balance ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE erp_stock_flow ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER remark;
ALTER TABLE erp_stock_flow ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE erp_stock_flow ADD COLUMN updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间' AFTER updated_by;
ALTER TABLE erp_stock_flow ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE erp_stock_in_order_line ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER remark;
ALTER TABLE erp_stock_in_order_line ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE erp_stock_in_order_line ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;

ALTER TABLE erp_stock_out_order_line ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER remark;
ALTER TABLE erp_stock_out_order_line ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE erp_stock_out_order_line ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;

ALTER TABLE erp_transfer_order_line ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER remark;
ALTER TABLE erp_transfer_order_line ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE erp_transfer_order_line ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
