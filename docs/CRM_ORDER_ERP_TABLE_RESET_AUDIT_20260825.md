# CRM / Order / ERP 表清理与重新同步审计

审计时间：2026-08-25

## 结论

本次已完成代码和迁移的静态审计，并生成清空/删表维护脚本。当前本机未拿到可执行的数据库客户端和连接凭据，未实际清空数据，未 DROP 表，未触发真实订货宝同步。

已确认：

- 新方案边界是：Integration 负责订货宝协议、Raw Landing、来源映射、同步运行和对账；CRM、Order、ERP 负责自己的业务主表。
- Order 旧订货宝表已经没有当前 Java 主代码引用，可以进入 DBA 物理清理候选。
- ERP 旧订货宝投影表已经没有当前 Java 主代码引用，可以进入 DBA 物理清理候选。
- CRM 已有 `crm_customer` 新业务主表，订货宝客户同步也会写入该表；但 V1 的 party/profile/contact/address/assignment 旧结构仍在同步和详情/地址查询中使用，不能直接删。
- 字典服务主流程表是 `data_dictionary` / `data_dictionary_item`；旧 `biz_dict` / `biz_dict_item` 当前 Java 主代码未引用，可纳入物理清理。
- 订货宝出库同步已按出库类型分流：销售出库投影到 Order 销售发货和 ERP 出库；调拨出库反推 ERP 调拨单并生成 ERP 出库；其他出库只落 ERP 出库。

暂无法验证：

- 真实库中各表当前行数、重复数据、失败记录和 Flyway 历史。
- 最新订货宝 API 返回的真实业务量、未知出库类型、缺失映射和重复单号。
- 目标执行环境的真实表行数、备份状态和 Flyway 历史。

## 表归属

### CRM

新方案业务主表：

- `crm_customer`：CRM 自研客户主表；列表、创建、修改、销售订单选客户应以它为主。

仍在运行链路中的过渡表，不能直接删：

- `crm_customer_type`
- `crm_customer_area`
- `crm_party`
- `crm_party_role`
- `crm_customer_profile`
- `crm_customer_policy`
- `crm_contact`
- `crm_address`
- `crm_sales_assignment`
- `crm_source_binding`
- `crm_source_identity_alias`
- `crm_sync_run`
- `crm_sync_checkpoint`
- `crm_sync_lock`

原因：CRM 同步仓储仍用这些表完成订货宝客户、地址、客户类型、客户地区投影；CRM 详情、地址簿、客户类型和地区查询也仍读取这些表。现在清库可以清，但不能 DROP。

可清理候选：

- `crm_external_staff`：V9 已经移除销售归属对旧外部员工表的依赖，现有 DBA 清理脚本已包含。
- `crm_outbox_event`：当前 Java 主代码未引用。清理前仍需确认生产是否有外部消费或保留规划。

暂不能直接 DROP 的疑似孤表：

- `crm_store`：当前没有 Store 仓储，但 `crm_contact.store_id`、`crm_address.store_id`、`crm_sales_assignment.store_id` 仍有外键或字段引用。要删它必须先新增迁移移除相关外键、索引、字段或一起完成 CRM 旧结构重构。

核心优化方向：

- 把客户类型、客户地区、联系人、地址、归属人员投影收口到新 CRM 业务模型和明确的新表。
- 让 Portal 客户管理、客户地址、客户类型、归属地区入口只依赖新业务模型，不再依赖旧 party/profile 查询。
- 完成后再下线 `crm_party*`、`crm_customer_profile`、`crm_customer_policy` 等旧结构。

### Order

新方案业务主表：

- `order_sales_order`
- `order_sales_order_line`
- `order_payment_record`
- `order_sales_shipment`
- `order_sales_shipment_line`
- `order_refund_record`
- `order_fund_document`

可清理的旧订货宝导向表：

- `dhb_order`
- `dhb_order_line`
- `dhb_order_shipment`
- `dhb_order_sync_run`
- `order_order`
- `order_order_line`
- `order_order_shipment`
- `order_source_record`
- `order_sync_run`
- `order_outbox_event`
- `order_dhb_shipment`
- `order_dhb_shipment_line`
- `order_dhb_return`
- `order_dhb_return_line`
- `order_dhb_financial_document`
- `order_dhb_sync_checkpoint`
- `order_dhb_shipment_logistics`
- `order_dhb_shipment_logistics_line`
- `order_sync_reconciliation`

当前风险：

- `order_sales_order.source_system_code + source_order_no` 只有普通索引，没有唯一键；代码注释也明确它不是幂等依据。
- Order 幂等依赖 `integration_external_object_mapping`。如果只清 Integration 映射、不清 Order 新业务表，再重跑同步可能生成重复销售订单。
- 正确做法是：要么业务表和来源映射一起清；要么先保留映射，通过映射修复业务表，不能只删一边。

### ERP

新方案业务主表：

- `erp_product_category`
- `erp_product_brand`
- `erp_product_tag`
- `erp_product_specification`
- `erp_product_specification_value`
- `erp_supplier_profile`
- `erp_inventory_warehouse`
- `erp_product`
- `erp_product_variant`
- `erp_procurement_order`
- `erp_procurement_order_line`
- `erp_purchase_return_order`
- `erp_purchase_return_order_line`
- `erp_stock_balance`
- `erp_stock_flow`
- `erp_stock_in_order`
- `erp_stock_in_order_line`
- `erp_stock_out_order`
- `erp_stock_out_order_line`
- `erp_transfer_order`
- `erp_transfer_order_line`

仍需保留的来源/同步表：

- `erp_master_source_binding`
- `erp_master_data_sync_run`
- `erp_master_data_sync_checkpoint`
- `erp_master_data_sync_lock`

原因：当前 ERP 商品和供应链同步仓储仍使用这些表做来源幂等、来源缺失标记、运行批次和锁。它们可以在全量重跑前清空，但不能 DROP。

可清理的旧订货宝投影表：

- `erp_brand`
- `erp_category`
- `erp_specification`
- `erp_specification_value`
- `erp_tag`
- `erp_tag_group`
- `erp_product_spu`
- `erp_product_sku`
- `erp_product_spu_category`
- `erp_product_spu_specification`
- `erp_product_sku_specification_value`
- `erp_product_spu_tag`
- `erp_product_image`
- `erp_product_price`
- `erp_product_unit`
- `erp_product_inventory_policy`
- `erp_product_custom_field`
- `erp_supplier`
- `erp_warehouse`
- `erp_purchase_order`
- `erp_purchase_order_line`
- `erp_purchase_return`
- `erp_purchase_return_line`
- `erp_warehousing_receipt`
- `erp_warehousing_receipt_line`
- `erp_warehousing_purchase_link`
- `erp_inventory_balance`

已补强：

- ERP V26 已给 `erp_master_source_binding`、`erp_stock_out_order`、`erp_transfer_order` 补充 `connector_id` 维度。
- 外部来源绑定唯一键调整为 `tenant_id + connector_id + source_system + source_object_type + source_object_id`。
- 出库和调拨来源唯一键调整为 `tenant_id + connector_id + source_system_code + source_document_no`。
- 订货宝同步入口要求 `connectorId` 非空；人工或旧历史出库/调拨单允许为空。

### Integration

应保留的配置/控制面表：

- `integration_dhb_connector`
- `integration_sync_task`
- `integration_field_mapping`

全量重跑前建议清空的运行/证据表：

- `integration_connector_sync_lease`
- `integration_sync_run`
- `integration_sync_checkpoint`
- `integration_sync_log`
- `integration_raw_landing`
- `integration_external_object_mapping`
- `integration_dead_letter`
- `integration_reconciliation_case`
- `integration_outbox_event`
- `integration_product_media_job`
- `integration_product_media_item`
- `integration_order_mirror`

暂不能 DROP 的旧镜像表：

- `integration_order_mirror`：虽然是订货宝订单只读镜像，但当前 API、仓储和测试仍在读写。要删必须先下线 `/api/v1/integration/dhb/orders/mirrors` 和写入逻辑。

疑似未落地使用的表：

- `integration_domain_ownership`：当前只在迁移和文档中出现，未看到 Java 实体/仓储引用。若确认暂不使用，可列为后续物理清理候选。

### Business Settings

新方案业务主表：

- `data_dictionary`
- `data_dictionary_item`

可清理的旧字典表：

- `biz_dict`
- `biz_dict_item`

当前风险：

- 字典服务内部同步接口只补齐已有 `dictionary_code` 下的来源值，不创建字典定义。
- 如果清空 `data_dictionary` / `data_dictionary_item` 后不立即重建 V8-V19 字典基线，CRM/ERP/Order 的单位、状态、支付方式、出库类型等解析会失败。

## 清库前置条件

必须先完成：

1. 明确环境：本地、共享 DEV、测试库或生产库。
2. 暂停 Integration 统一同步调度和人工同步入口，避免清库过程中有新批次写入。
3. 对 `rigour_crm`、`rigour_order`、`rigour_erp`、`rigour_integration`、`rigour_settings` 做备份。
4. 确认 Flyway 历史不清空、不改写、不删除。
5. 先记录清库前行数、重复项、最新批次和异常数量。

## 建议清空顺序

不建议用散乱的 `TRUNCATE`。有外键的表用 child-to-parent `DELETE`，必要时再 `ALTER TABLE ... AUTO_INCREMENT = 1`。如果使用 `SET FOREIGN_KEY_CHECKS=0`，也要先写清楚目标表和回滚方式。

### rigour_order

1. `order_sales_shipment_line`
2. `order_sales_shipment`
3. `order_refund_record`
4. `order_fund_document`
5. `order_payment_record`
6. `order_sales_order_line`
7. `order_sales_order`

### rigour_erp

1. `erp_transfer_order_line`
2. `erp_stock_out_order_line`
3. `erp_stock_in_order_line`
4. `erp_procurement_order_line`
5. `erp_purchase_return_order_line`
6. `erp_stock_flow`
7. `erp_stock_balance`
8. `erp_transfer_order`
9. `erp_stock_out_order`
10. `erp_stock_in_order`
11. `erp_procurement_order`
12. `erp_purchase_return_order`
13. `erp_product_variant`
14. `erp_product`
15. `erp_product_specification_value`
16. `erp_product_specification`
17. `erp_product_category`
18. `erp_product_brand`
19. `erp_product_tag`
20. `erp_supplier_profile`
21. `erp_inventory_warehouse`
22. `erp_master_source_binding`
23. `erp_master_data_sync_checkpoint`
24. `erp_master_data_sync_lock`
25. `erp_master_data_sync_run`

### rigour_crm

1. `crm_source_identity_alias`
2. `crm_source_binding`
3. `crm_sync_lock`
4. `crm_sync_checkpoint`
5. `crm_sync_run`
6. `crm_sales_assignment`
7. `crm_address`
8. `crm_contact`
9. `crm_customer_policy`
10. `crm_customer_profile`
11. `crm_party_role`
12. `crm_customer`
13. `crm_party`
14. `crm_customer_area`
15. `crm_customer_type`

### rigour_integration

1. `integration_connector_sync_lease`
2. `integration_dead_letter`
3. `integration_reconciliation_case`
4. `integration_outbox_event`
5. `integration_product_media_item`
6. `integration_product_media_job`
7. `integration_order_mirror`
8. `integration_raw_landing`
9. `integration_external_object_mapping`
10. `integration_sync_log`
11. `integration_sync_checkpoint`
12. `integration_sync_run`

保留 `integration_dhb_connector`、`integration_sync_task`、`integration_field_mapping`，否则需要重新配置连接器和任务。

### rigour_settings

1. `data_dictionary_item`
2. `data_dictionary`

清空后必须立即执行 `docs/CRM_ORDER_ERP_SETTINGS_DICTIONARY_RESEED.sql`，否则业务字典为空，同步阶段会产生大量映射失败。

## 物理删表方案

本次生成的维护脚本：

- `docs/CRM_ORDER_ERP_DHB_RESYNC_RESET.sql`：清空 Integration 运行证据表、CRM/Order/ERP 可由订货宝全量同步重建的数据，以及 Settings 新字典数据。
- `docs/CRM_ORDER_ERP_SETTINGS_DICTIONARY_RESEED.sql`：按当前 Business Settings V8、V9、V11-V19 字典种子恢复 `data_dictionary` / `data_dictionary_item`。
- `docs/CRM_ORDER_ERP_UNUSED_TABLE_DROP.sql`：物理删除当前主代码无引用、且本轮确认可清理的旧订货宝导向表和旧字典表。
- `scripts/run-crm-order-erp-dhb-maintenance.sh`：在本机没有 `mysql` 客户端时，使用项目已有 MySQL Connector/J 执行上述 SQL；执行前必须显式设置确认变量。

历史脚本：`docs/LEGACY_DHB_TABLE_CLEANUP.sql`。

已纳入物理删表脚本的旧表：

- Order 旧表：`dhb_order*`、`order_dhb_*`、`order_order*`、`order_source_record`、`order_outbox_event`、`order_sync_*`。
- ERP 旧表：旧商品、SKU、规格、品牌、分类、标签、供应商、仓库、采购、入库、退货、库存投影表。
- CRM `crm_external_staff`、`crm_outbox_event`。
- Integration `integration_domain_ownership`。
- Settings `biz_dict`、`biz_dict_item`。

仍不能立即 DROP 的候选：

- `crm_store`：必须先移除 `crm_contact`、`crm_address`、`crm_sales_assignment` 上的 store 外键、索引和字段；否则不应单独 DROP。
- `integration_order_mirror`：必须先删除接口、仓储写入和测试后再加入。

## 重新同步顺序

统一入口：

`POST /api/v1/integration/dhb/orchestration/sync`

一次全量参数：

```json
{
  "maxPages": 100,
  "includeDictionary": true,
  "includeIam": true,
  "includeErp": true,
  "includeCrm": true,
  "includeOrder": true
}
```

分段重跑参数：

1. 字典/IAM：

```json
{
  "maxPages": 100,
  "includeDictionary": true,
  "includeIam": true,
  "includeErp": false,
  "includeErpProduct": false,
  "includeErpSupply": false,
  "includeCrm": false,
  "includeOrder": false
}
```

2. ERP 商品主数据：

```json
{
  "maxPages": 100,
  "includeDictionary": false,
  "includeIam": false,
  "includeErp": false,
  "includeErpProduct": true,
  "includeErpSupply": false,
  "includeCrm": false,
  "includeOrder": false
}
```

3. CRM 客户主数据：

```json
{
  "maxPages": 100,
  "includeDictionary": false,
  "includeIam": false,
  "includeErp": false,
  "includeErpProduct": false,
  "includeErpSupply": false,
  "includeCrm": true,
  "includeOrder": false
}
```

4. ERP 供应链数据：

```json
{
  "maxPages": 100,
  "includeDictionary": false,
  "includeIam": false,
  "includeErp": false,
  "includeErpProduct": false,
  "includeErpSupply": true,
  "includeCrm": false,
  "includeOrder": false
}
```

5. Order 订单域：

```json
{
  "maxPages": 100,
  "includeDictionary": false,
  "includeIam": false,
  "includeErp": false,
  "includeErpProduct": false,
  "includeErpSupply": false,
  "includeCrm": false,
  "includeOrder": true
}
```

现有编排顺序：

1. Dictionary 前置说明和来源值补齐。
2. IAM 员工来源映射。
3. ERP 商品主数据。
4. CRM 客户主数据。
5. ERP 供应链数据。
6. Order 订单域。

这个顺序符合 Order 对客户、商品、SKU、仓库映射的依赖。

## 重跑后对账检查

必须检查：

- `integration_sync_run`：每个对象的 `fetched_count`、`accepted_count`、`duplicate_count`、`rejected_count`。
- `integration_raw_landing`：按 `source_object_type` 统计 Raw 数量和 `landing_status`。
- `integration_external_object_mapping`：按 `source_object_type` 统计映射数量，并检查同一来源对象是否重复。
- `integration_dead_letter`、`integration_reconciliation_case`：所有 OPEN/ERROR 记录逐条归因。
- CRM：`crm_customer` 与客户来源映射数量是否一致；客户类型、地区是否都能映射到业务编码。
- ERP：商品、SKU、仓库、供应商、库存、出入库和调拨单是否都有来源映射；出库来源单号是否重复。
- Order：销售订单、回款、退款、资金单、销售发货是否都能从来源映射回 Raw。

重点 SQL 检查方向：

```sql
SELECT source_object_type, landing_status, COUNT(*)
  FROM integration_raw_landing
 GROUP BY source_object_type, landing_status;

SELECT source_object_type, mapping_status, COUNT(*)
  FROM integration_external_object_mapping
 GROUP BY source_object_type, mapping_status;

SELECT tenant_id, source_system_code, source_order_no, COUNT(*)
  FROM order_sales_order
 WHERE source_system_code IS NOT NULL AND source_order_no IS NOT NULL AND deleted = 0
 GROUP BY tenant_id, source_system_code, source_order_no
HAVING COUNT(*) > 1;

SELECT tenant_id, source_system_code, source_document_no, COUNT(*)
  FROM erp_stock_out_order
 WHERE source_system_code IS NOT NULL AND source_document_no IS NOT NULL AND deleted = 0
 GROUP BY tenant_id, source_system_code, source_document_no
HAVING COUNT(*) > 1;

SELECT source_object_type, source_id, last_error_code, COUNT(*)
  FROM integration_dead_letter
 WHERE status = 'OPEN'
 GROUP BY source_object_type, source_id, last_error_code;
```

## 需要优化的问题

1. CRM 仍处于新旧双模型并行：`crm_customer` 是新业务主表，但客户详情、地址簿、类型、地区仍依赖旧 party/profile/address/source_binding 查询。
2. Order 本地业务表不保存 connector 维度，幂等完全依赖 Integration 映射；清库时必须业务表和映射一起处理。
3. ERP 出库/调拨来源唯一键缺 connector 维度，多连接器场景下有误判风险。
4. `integration_order_mirror` 仍作为旧订货宝订单镜像被接口使用；如果新方案完全以 Order 业务表为主，应下线该镜像接口和写入路径。
5. 未知订货宝出库类型当前会进入失败和对账，不会自动落 `OTHER`。这有利于第一次重跑发现新类型；确认类型语义后再决定是否加入映射。

## 执行状态和下一步

已完成：

- 生成 `docs/CRM_ORDER_ERP_DHB_RESYNC_RESET.sql`。
- 生成 `docs/CRM_ORDER_ERP_SETTINGS_DICTIONARY_RESEED.sql`。
- 生成 `docs/CRM_ORDER_ERP_UNUSED_TABLE_DROP.sql`。
- 生成 `scripts/run-crm-order-erp-dhb-maintenance.sh`，可在拿到有权限的数据库账号后按顺序执行三份 SQL。
- 校验本机服务状态：Settings、Integration、CRM、ERP、Order 服务均已启动且健康接口返回 `UP`。

当前阻塞：

- 本机未发现 `mysql`、`mariadb`、`docker`、`psql` 等可用数据库客户端。
- 当前 shell 未暴露数据库密码或可直接复用的连接环境变量。
- Integration 手工同步入口需要 Gateway 签名的调用人上下文，不能直接从本机 curl 伪造租户身份触发。

实际执行时：

- 先备份 `rigour_crm`、`rigour_order`、`rigour_erp`、`rigour_integration`、`rigour_settings`。
- 暂停同步。
- 执行 `docs/CRM_ORDER_ERP_DHB_RESYNC_RESET.sql`。
- 执行 `docs/CRM_ORDER_ERP_SETTINGS_DICTIONARY_RESEED.sql`。
- 执行 `docs/CRM_ORDER_ERP_UNUSED_TABLE_DROP.sql`。
- 如果目标机器没有 MySQL 客户端，可执行：

```bash
CONFIRMATION=RESET_CRM_ORDER_ERP_SETTINGS_DHB \
DB_USER=<有 rigour_crm/rigour_order/rigour_erp/rigour_integration/rigour_settings DML+DDL 权限的账号> \
scripts/run-crm-order-erp-dhb-maintenance.sh
```

- 通过 Portal/Gateway 或带签名上下文的内部入口重跑统一同步。
- 按本文“重跑后对账检查”逐项核对行数、Raw、映射、死信和重复来源单号。

对账后改代码：

- 先处理缺映射和重复风险，再处理性能。
- 优先把 CRM 旧 party/profile 依赖收口到新业务主表和明确的新地址/类型/地区模型。
- 对真实出现的新订货宝出库类型，按业务语义补充映射或保留失败对账。
