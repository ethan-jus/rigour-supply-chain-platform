-- BI 本地源投影，只由受信领域 API 装载，禁止回写业务源表。

CREATE TABLE bi_source_crm_crm_contact (
 `id` binary(16) NOT NULL,
 `tenant_id` binary(16) NOT NULL,
 `party_id` binary(16) NULL,
 `contact_name` varchar(160) NULL,
 `phone` varchar(128) NULL,
 `is_primary` tinyint(1) NULL,
 `status` varchar(24) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_party_id` (tenant_id,`party_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_crm_crm_customer (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `customer_code` varchar(50) NULL,
 `party_id` binary(16) NULL,
 `customer_name` varchar(200) NULL,
 `contact_name` varchar(100) NULL,
 `contact_phone` varchar(50) NULL,
 `customer_type_code` varchar(64) NULL,
 `region_code` varchar(64) NULL,
 `region_name` varchar(80) NULL,
 `owner_sales_name` varchar(100) NULL,
 `owner_employee_code` varchar(50) NULL,
 `owner_employee_name_snapshot` varchar(100) NULL,
 `status_code` varchar(64) NULL,
 `source_system_code` varchar(32) NULL,
 `source_document_no` varchar(128) NULL,
 `source_created_at` datetime(6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_customer_code` (tenant_id,`customer_code`),
 KEY `idx_party_id` (tenant_id,`party_id`),
 KEY `idx_customer_type_code` (tenant_id,`customer_type_code`),
 KEY `idx_region_code` (tenant_id,`region_code`),
 KEY `idx_owner_employee_code` (tenant_id,`owner_employee_code`),
 KEY `idx_status_code` (tenant_id,`status_code`),
 KEY `idx_source_system_code` (tenant_id,`source_system_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_crm_crm_customer_area (
 `id` binary(16) NOT NULL,
 `tenant_id` binary(16) NOT NULL,
 `area_code` varchar(128) NULL,
 `area_name` varchar(160) NULL,
 `status` varchar(24) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_area_code` (tenant_id,`area_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_crm_crm_customer_policy (
 `id` binary(16) NOT NULL,
 `tenant_id` binary(16) NOT NULL,
 `party_id` binary(16) NULL,
 `payment_term_days` int unsigned NULL,
 `status` varchar(24) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_party_id` (tenant_id,`party_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_crm_crm_customer_type (
 `id` binary(16) NOT NULL,
 `tenant_id` binary(16) NOT NULL,
 `type_code` varchar(128) NULL,
 `type_name` varchar(160) NULL,
 `status` varchar(24) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_type_code` (tenant_id,`type_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_erp_erp_inventory_warehouse (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `warehouse_code` varchar(50) NULL,
 `warehouse_name` varchar(120) NULL,
 `region_code` varchar(64) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_warehouse_code` (tenant_id,`warehouse_code`),
 KEY `idx_region_code` (tenant_id,`region_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_erp_erp_procurement_order (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `procurement_no` varchar(50) NULL,
 `source_document_no` varchar(128) NULL,
 `target_warehouse_id` bigint NULL,
 `status_code` varchar(64) NULL,
 `expected_arrival_time` datetime(6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_target_warehouse_id` (tenant_id,`target_warehouse_id`),
 KEY `idx_status_code` (tenant_id,`status_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_erp_erp_procurement_order_line (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `procurement_order_id` bigint NULL,
 `product_id` bigint NULL,
 `product_variant_id` bigint NULL,
 `product_code_snapshot` varchar(50) NULL,
 `variant_code_snapshot` varchar(50) NULL,
 `product_name_snapshot` varchar(200) NULL,
 `unit_code` varchar(64) NULL,
 `quantity` decimal(24,6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_procurement_order_id` (tenant_id,`procurement_order_id`),
 KEY `idx_product_id` (tenant_id,`product_id`),
 KEY `idx_product_variant_id` (tenant_id,`product_variant_id`),
 KEY `idx_unit_code` (tenant_id,`unit_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_erp_erp_product (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `product_code` varchar(50) NULL,
 `product_name` varchar(200) NULL,
 `category_id` bigint NULL,
 `brand_id` bigint NULL,
 `unit_code` varchar(64) NULL,
 `shelf_status_code` varchar(64) NULL,
 `submit_status_code` varchar(64) NULL,
 `source_system_code` varchar(32) NULL,
 `source_document_no` varchar(128) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_product_code` (tenant_id,`product_code`),
 KEY `idx_category_id` (tenant_id,`category_id`),
 KEY `idx_brand_id` (tenant_id,`brand_id`),
 KEY `idx_unit_code` (tenant_id,`unit_code`),
 KEY `idx_shelf_status_code` (tenant_id,`shelf_status_code`),
 KEY `idx_submit_status_code` (tenant_id,`submit_status_code`),
 KEY `idx_source_system_code` (tenant_id,`source_system_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_erp_erp_product_brand (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `brand_code` varchar(50) NULL,
 `brand_name` varchar(120) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_brand_code` (tenant_id,`brand_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_erp_erp_product_category (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `parent_id` bigint NULL,
 `category_code` varchar(50) NULL,
 `category_name` varchar(120) NULL,
 `category_level` int NULL,
 `ordinal` int NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_parent_id` (tenant_id,`parent_id`),
 KEY `idx_category_code` (tenant_id,`category_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_erp_erp_product_variant (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `product_id` bigint NULL,
 `variant_code` varchar(50) NULL,
 `specification_snapshot` varchar(500) NULL,
 `unit_code` varchar(64) NULL,
 `purchase_price` decimal(24,6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_product_id` (tenant_id,`product_id`),
 KEY `idx_variant_code` (tenant_id,`variant_code`),
 KEY `idx_unit_code` (tenant_id,`unit_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_erp_erp_stock_balance (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `warehouse_id` bigint NULL,
 `product_id` bigint NULL,
 `product_variant_id` bigint NULL,
 `available_quantity` decimal(24,6) NULL,
 `locked_quantity` decimal(24,6) NULL,
 `in_transit_quantity` decimal(24,6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_warehouse_id` (tenant_id,`warehouse_id`),
 KEY `idx_product_id` (tenant_id,`product_id`),
 KEY `idx_product_variant_id` (tenant_id,`product_variant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_erp_erp_stock_out_order (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `stock_out_no` varchar(50) NULL,
 `source_document_no` varchar(128) NULL,
 `stock_out_type_code` varchar(64) NULL,
 `warehouse_id` bigint NULL,
 `status_code` varchar(64) NULL,
 `stock_out_time` datetime(6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_stock_out_type_code` (tenant_id,`stock_out_type_code`),
 KEY `idx_warehouse_id` (tenant_id,`warehouse_id`),
 KEY `idx_status_code` (tenant_id,`status_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_erp_erp_stock_out_order_line (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `stock_out_order_id` bigint NULL,
 `product_id` bigint NULL,
 `product_variant_id` bigint NULL,
 `product_code_snapshot` varchar(50) NULL,
 `variant_code_snapshot` varchar(50) NULL,
 `product_name_snapshot` varchar(200) NULL,
 `unit_code` varchar(64) NULL,
 `quantity` decimal(24,6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_stock_out_order_id` (tenant_id,`stock_out_order_id`),
 KEY `idx_product_id` (tenant_id,`product_id`),
 KEY `idx_product_variant_id` (tenant_id,`product_variant_id`),
 KEY `idx_unit_code` (tenant_id,`unit_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_integration_integration_feishu_import_batch (
 `id` binary(16) NOT NULL,
 `tenant_id` binary(16) NOT NULL,
 `source_url` varchar(2000) NULL,
 `original_file_name` varchar(255) NULL,
 `file_sha256` char(64) NULL,
 `status` varchar(32) NULL,
 `total_rows` bigint unsigned NULL,
 `created_at` datetime(6) NULL,
 PRIMARY KEY(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_integration_integration_feishu_import_raw_row (
 `id` binary(16) NOT NULL,
 `batch_id` binary(16) NULL,
 `tenant_id` binary(16) NOT NULL,
 `sheet_name` varchar(255) NULL,
 `table_code` varchar(64) NULL,
 `source_row_number` int unsigned NULL,
 `source_document_no` varchar(128) NULL,
 `source_created_at` datetime(6) NULL,
 `raw_row_hash` char(64) NULL,
 `row_json` json NULL,
 `import_status` varchar(32) NULL,
 `projection_status` varchar(32) NULL,
 `error_code` varchar(64) NULL,
 `created_at` datetime(6) NULL,
 `updated_at` datetime(6) NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_batch_id` (tenant_id,`batch_id`),
 KEY `idx_table_code` (tenant_id,`table_code`),
 KEY `idx_error_code` (tenant_id,`error_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_integration_integration_feishu_online_capture (
 `id` binary(16) NOT NULL,
 `tenant_id` binary(16) NOT NULL,
 `source_id` varchar(128) NULL,
 `source_name` varchar(255) NULL,
 `source_url` varchar(2000) NULL,
 `started_at` datetime(6) NULL,
 `completed_at` datetime(6) NULL,
 `complete` tinyint(1) NULL,
 `filtered` tinyint(1) NULL,
 `checksum` char(64) NULL,
 `record_count` int NULL,
 `page_count` int NULL,
 `payload_json` longtext NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_source_id` (tenant_id,`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_integration_integration_raw_landing (
 `id` binary(16) NOT NULL,
 `tenant_id` binary(16) NOT NULL,
 `source_system` varchar(32) NULL,
 `source_object_type` varchar(64) NULL,
 `source_id` varchar(128) NULL,
 `received_at` datetime(6) NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_source_id` (tenant_id,`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_order_order_payment_record (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `payment_no` varchar(50) NULL,
 `source_system_code` varchar(64) NULL,
 `source_document_no` varchar(128) NULL,
 `order_id` bigint NULL,
 `sales_order_no_snapshot` varchar(50) NULL,
 `customer_id` bigint NULL,
 `customer_code_snapshot` varchar(50) NULL,
 `customer_name_snapshot` varchar(200) NULL,
 `collector_staff_code` varchar(50) NULL,
 `collector_name_snapshot` varchar(100) NULL,
 `payment_time` datetime(6) NULL,
 `payment_method_code` varchar(64) NULL,
 `paid_amount` decimal(24,6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_source_system_code` (tenant_id,`source_system_code`),
 KEY `idx_order_id` (tenant_id,`order_id`),
 KEY `idx_customer_id` (tenant_id,`customer_id`),
 KEY `idx_collector_staff_code` (tenant_id,`collector_staff_code`),
 KEY `idx_payment_method_code` (tenant_id,`payment_method_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_order_order_refund_record (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `order_id` bigint NULL,
 `customer_id` bigint NULL,
 `customer_code_snapshot` varchar(50) NULL,
 `customer_name_snapshot` varchar(200) NULL,
 `refund_status_code` varchar(64) NULL,
 `refund_amount` decimal(24,6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_order_id` (tenant_id,`order_id`),
 KEY `idx_customer_id` (tenant_id,`customer_id`),
 KEY `idx_refund_status_code` (tenant_id,`refund_status_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_order_order_sales_order (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `order_no` varchar(50) NULL,
 `source_system_code` varchar(32) NULL,
 `source_order_no` varchar(80) NULL,
 `customer_id` bigint NULL,
 `customer_code_snapshot` varchar(50) NULL,
 `customer_name_snapshot` varchar(200) NULL,
 `region_code` varchar(64) NULL,
 `owner_sales_name` varchar(100) NULL,
 `owner_employee_code` varchar(50) NULL,
 `owner_employee_name_snapshot` varchar(100) NULL,
 `order_date` datetime(6) NULL,
 `order_status_code` varchar(64) NULL,
 `order_type_code` varchar(64) NULL,
 `payment_method_code` varchar(64) NULL,
 `payment_status_code` varchar(64) NULL,
 `outbound_status_code` varchar(64) NULL,
 `total_quantity` decimal(24,6) NULL,
 `discount_rate` decimal(10,6) NULL,
 `discount_amount` decimal(24,6) NULL,
 `payable_amount` decimal(24,6) NULL,
 `paid_amount` decimal(24,6) NULL,
 `unpaid_amount` decimal(24,6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 `payment_time` datetime(6) NULL,
 `shipment_time` datetime(6) NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_source_system_code` (tenant_id,`source_system_code`),
 KEY `idx_customer_id` (tenant_id,`customer_id`),
 KEY `idx_region_code` (tenant_id,`region_code`),
 KEY `idx_owner_employee_code` (tenant_id,`owner_employee_code`),
 KEY `idx_order_status_code` (tenant_id,`order_status_code`),
 KEY `idx_order_type_code` (tenant_id,`order_type_code`),
 KEY `idx_payment_method_code` (tenant_id,`payment_method_code`),
 KEY `idx_payment_status_code` (tenant_id,`payment_status_code`),
 KEY `idx_outbound_status_code` (tenant_id,`outbound_status_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_order_order_sales_order_line (
 `id` bigint NOT NULL,
 `tenant_id` varchar(64) NOT NULL,
 `order_id` bigint NULL,
 `product_id` bigint NULL,
 `product_variant_id` bigint NULL,
 `product_code_snapshot` varchar(128) NULL,
 `sku_code_snapshot` varchar(128) NULL,
 `product_name_snapshot` varchar(200) NULL,
 `specification_snapshot` varchar(500) NULL,
 `unit_code` varchar(64) NULL,
 `quantity` decimal(24,6) NULL,
 `unit_price` decimal(24,6) NULL,
 `discount_rate` decimal(10,6) NULL,
 `discount_amount` decimal(24,6) NULL,
 `line_amount` decimal(24,6) NULL,
 `created_time` datetime(6) NULL,
 `updated_time` datetime(6) NULL,
 `deleted` int NULL,
 PRIMARY KEY(tenant_id,id),
 KEY `idx_order_id` (tenant_id,`order_id`),
 KEY `idx_product_id` (tenant_id,`product_id`),
 KEY `idx_product_variant_id` (tenant_id,`product_variant_id`),
 KEY `idx_unit_code` (tenant_id,`unit_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_source_snapshot_checkpoint (
 tenant_id VARCHAR(64) NOT NULL,dataset VARCHAR(100) NOT NULL,source_version VARCHAR(1000) NOT NULL,
 projected_at DATETIME(6) NOT NULL,PRIMARY KEY(tenant_id,dataset)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
