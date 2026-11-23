ALTER TABLE order_transport_shipment_source
    ADD COLUMN awaiting_huolala_no VARCHAR(80) NULL COMMENT '等待核实的货拉拉订单号',
    ADD COLUMN awaiting_huolala_data JSON NULL COMMENT '待关联销售订单及运输信息快照',
    ADD INDEX idx_shipment_awaiting_huolala (tenant_id, awaiting_huolala_no);
