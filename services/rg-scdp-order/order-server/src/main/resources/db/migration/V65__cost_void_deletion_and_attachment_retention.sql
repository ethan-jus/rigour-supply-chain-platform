ALTER TABLE order_logistics_cost
    ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：仅已作废费用可逻辑删除';
ALTER TABLE order_logistics_cost_attachment
    ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：移除和更换保留原附件';
