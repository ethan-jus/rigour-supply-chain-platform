-- CRM / Order / ERP / Business Settings 可删除旧表物理清理脚本。
--
-- 适用场景：
-- 1. 已明确执行环境，并确认不是生产误操作；
-- 2. 已备份 rigour_crm、rigour_order、rigour_erp、rigour_integration、rigour_settings；
-- 3. 已确认新业务表、Integration Raw、来源映射和同步审计足以支撑回放与对账；
-- 4. 使用 DBA 或具备 DROP 权限的维护账号执行。
--
-- 本脚本不删除仍被当前代码或外键引用的表：
-- - rigour_crm.crm_store：仍被 crm_contact/crm_address/crm_sales_assignment 的 store_id 外键牵住；
-- - rigour_integration.integration_order_mirror：当前 API、仓储和测试仍在读写。
-- - rigour_settings.data_dictionary/data_dictionary_item：当前字典服务主流程表，清空后必须重建，不 DROP。
--
-- 不要把本脚本复制到服务自动 Flyway 迁移里。

SET FOREIGN_KEY_CHECKS = 0;

USE rigour_crm;
DROP TABLE IF EXISTS crm_external_staff;
DROP TABLE IF EXISTS crm_outbox_event;

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

USE rigour_integration;
DROP TABLE IF EXISTS integration_domain_ownership;

USE rigour_settings;
DROP TABLE IF EXISTS biz_dict_item;
DROP TABLE IF EXISTS biz_dict;

SET FOREIGN_KEY_CHECKS = 1;
