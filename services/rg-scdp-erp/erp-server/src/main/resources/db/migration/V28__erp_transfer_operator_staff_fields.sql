-- ERP 调拨单补充出库/入库经办人快照。
-- 订货宝调拨出库和调拨入库可能由不同业务人员操作，不能只用一个经办人字段覆盖。

ALTER TABLE erp_transfer_order
    ADD COLUMN outbound_operator_staff_code VARCHAR(50) NULL
        COMMENT '调拨出库经办员工编码，关联IAM员工中心员工编码' AFTER target_warehouse_id,
    ADD COLUMN outbound_operator_staff_name_snapshot VARCHAR(100) NULL
        COMMENT '调拨出库经办员工名称快照' AFTER outbound_operator_staff_code,
    ADD COLUMN inbound_operator_staff_code VARCHAR(50) NULL
        COMMENT '调拨入库经办员工编码，关联IAM员工中心员工编码' AFTER outbound_operator_staff_name_snapshot,
    ADD COLUMN inbound_operator_staff_name_snapshot VARCHAR(100) NULL
        COMMENT '调拨入库经办员工名称快照' AFTER inbound_operator_staff_code,
    ADD KEY idx_erp_transfer_outbound_operator (tenant_id, outbound_operator_staff_code, created_time),
    ADD KEY idx_erp_transfer_inbound_operator (tenant_id, inbound_operator_staff_code, created_time);
