-- Order V46：历史关联组保存两侧金额摘要与已解释差额。
-- V40 已执行，这里只做增量：差额为 0 表示金额守恒；非 0 必须带业务原因（服务层校验）。
-- 来源单与历史订单的业务事实不变，差额只作为可审计的关联口径。

ALTER TABLE order_history_group ADD COLUMN source_amount DECIMAL(18,2) NULL;
ALTER TABLE order_history_group ADD COLUMN order_amount DECIMAL(18,2) NULL;
ALTER TABLE order_history_group ADD COLUMN difference_amount DECIMAL(18,2) NULL;
ALTER TABLE order_history_group ADD COLUMN difference_reason VARCHAR(200) NULL;
