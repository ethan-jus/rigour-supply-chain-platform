-- CRM 自研业务基线：客户、商家、门店统一为客户管理。
-- 本表只服务我们的客户管理流程，不保存订货宝来源字段；外部数据通过 Integration 映射到本表。

CREATE TABLE crm_customer (
    id                    BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    customer_code         VARCHAR(50)    NOT NULL COMMENT '客户编号，由CRM编码规则生成',
    customer_name         VARCHAR(200)   NOT NULL COMMENT '客户名称，即业务门店名称',
    contact_name          VARCHAR(100)   NULL COMMENT '联系人',
    contact_phone         VARCHAR(50)    NULL COMMENT '联系电话',
    region_code           VARCHAR(64)    NULL COMMENT '客户归属地区，关联 REGION 字典项',
    owner_sales_user_id   VARCHAR(64)    NULL COMMENT '归属销售用户ID，跨IAM/销售组织引用',
    owner_sales_name      VARCHAR(100)   NULL COMMENT '归属销售名称快照',
    settlement_type_code  VARCHAR(64)    NULL COMMENT '客户结算类型，关联 CUSTOMER_SETTLEMENT_TYPE 字典项',
    address               VARCHAR(1000)  NULL COMMENT '客户地址',
    status_code           VARCHAR(64)    NOT NULL DEFAULT 'ACTIVE' COMMENT '客户状态，关联 CUSTOMER_STATUS 字典项',
    remark                VARCHAR(1000)  NULL COMMENT '备注',
    revision              INT            NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    created_by            VARCHAR(50)    NULL COMMENT '创建人',
    created_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by            VARCHAR(50)    NULL COMMENT '更新人',
    updated_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted               INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_customer_code (tenant_id, customer_code),
    KEY idx_crm_customer_name (tenant_id, customer_name),
    KEY idx_crm_customer_region (tenant_id, region_code),
    KEY idx_crm_customer_owner (tenant_id, owner_sales_user_id),
    KEY idx_crm_customer_status (tenant_id, status_code, updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM客户主表';
