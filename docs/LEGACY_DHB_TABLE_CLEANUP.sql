-- 旧订货宝导向表物理清理脚本。
--
-- 使用场景：
-- 1. ERP/CRM/Order/字典服务已经完成新方案切流；
-- 2. 已确认新业务表、Integration Raw、来源绑定和同步审计可以支撑回放与对账；
-- 3. 已完成数据库备份；
-- 4. 使用 DBA 或具备 DROP 权限的维护账号执行。
--
-- 不要把本脚本内容复制回服务自动 Flyway 迁移。
-- 日常迁移账号不授予 DROP 权限，服务启动迁移只负责新模型结构和引用关系切换。

SET FOREIGN_KEY_CHECKS = 0;

USE rigour_settings;
DROP TABLE IF EXISTS biz_dict_item;
DROP TABLE IF EXISTS biz_dict;

USE rigour_crm;
DROP TABLE IF EXISTS crm_external_staff;

USE rigour_order;
DROP TABLE IF EXISTS order_dhb_shipment_logistics_line;
DROP TABLE IF EXISTS order_dhb_shipment_logistics;
DROP TABLE IF EXISTS order_dhb_shipment_line;
DROP TABLE IF EXISTS order_dhb_shipment;
DROP TABLE IF EXISTS order_dhb_return_line;
DROP TABLE IF EXISTS order_dhb_return;
DROP TABLE IF EXISTS order_dhb_financial_document;
DROP TABLE IF EXISTS order_dhb_sync_checkpoint;

DROP TABLE IF EXISTS order_order_shipment;
DROP TABLE IF EXISTS order_order_line;
DROP TABLE IF EXISTS order_source_record;
DROP TABLE IF EXISTS order_outbox_event;
DROP TABLE IF EXISTS order_order;
DROP TABLE IF EXISTS order_sync_reconciliation;
DROP TABLE IF EXISTS order_sync_run;

DROP TABLE IF EXISTS dhb_order_shipment;
DROP TABLE IF EXISTS dhb_order_line;
DROP TABLE IF EXISTS dhb_order_sync_run;
DROP TABLE IF EXISTS dhb_order;

USE rigour_erp;
DROP TABLE IF EXISTS erp_warehousing_purchase_link;
DROP TABLE IF EXISTS erp_warehousing_receipt_line;
DROP TABLE IF EXISTS erp_purchase_return_line;
DROP TABLE IF EXISTS erp_purchase_order_line;
DROP TABLE IF EXISTS erp_inventory_balance;

DROP TABLE IF EXISTS erp_product_custom_field;
DROP TABLE IF EXISTS erp_product_inventory_policy;
DROP TABLE IF EXISTS erp_product_unit;
DROP TABLE IF EXISTS erp_product_price;
DROP TABLE IF EXISTS erp_product_image;
DROP TABLE IF EXISTS erp_product_sku_specification_value;
DROP TABLE IF EXISTS erp_product_spu_tag;
DROP TABLE IF EXISTS erp_product_spu_specification;
DROP TABLE IF EXISTS erp_product_spu_category;

DROP TABLE IF EXISTS erp_warehousing_receipt;
DROP TABLE IF EXISTS erp_purchase_return;
DROP TABLE IF EXISTS erp_purchase_order;
DROP TABLE IF EXISTS erp_warehouse;
DROP TABLE IF EXISTS erp_supplier;

DROP TABLE IF EXISTS erp_product_sku;
DROP TABLE IF EXISTS erp_product_spu;
DROP TABLE IF EXISTS erp_tag;
DROP TABLE IF EXISTS erp_tag_group;
DROP TABLE IF EXISTS erp_specification_value;
DROP TABLE IF EXISTS erp_specification;
DROP TABLE IF EXISTS erp_brand;
DROP TABLE IF EXISTS erp_category;

SET FOREIGN_KEY_CHECKS = 1;
