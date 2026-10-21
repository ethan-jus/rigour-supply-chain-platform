ALTER TABLE order_logistics_cost ADD INDEX ix_logistics_cost_creator (tenant_id,created_by,cost_date);
