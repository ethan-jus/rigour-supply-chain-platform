-- IAM V57：补齐角色、用户角色、资源授权和数据范围字段描述。
--
-- 业务口径：
-- 1. 角色和权限以我方IAM为准，订货宝角色不直接映射成我方权限。
-- 2. 自定义角色由系统生成 JSyyyyMMdd#### 编码；系统角色仍由IAM迁移和初始化维护。
-- 3. iam_role_resource 是角色权限事实表；页面只展示业务化分组，数据库保留完整授权审计链路。

SET @changed_at = TIMESTAMP('2026-08-22 23:30:00.000000');

UPDATE iam_resource
   SET display_name = '角色权限',
       updated_at = @changed_at,
       version = version + 1
 WHERE resource_code IN ('SYSTEM_ADMIN.MENU.ROLE', 'SYSTEM_ADMIN.PAGE.ROLE_LIST')
   AND display_name <> '角色权限';

ALTER TABLE iam_role
    MODIFY COLUMN id            BINARY(16)      NOT NULL COMMENT '角色ID，UUID二进制存储',
    MODIFY COLUMN tenant_id     BINARY(16)      NOT NULL COMMENT '租户ID',
    MODIFY COLUMN role_code     VARCHAR(64)     NOT NULL COMMENT '角色编码；系统角色为稳定系统编码，自定义角色由IAM编码规则生成',
    MODIFY COLUMN role_name     VARCHAR(128)    NOT NULL COMMENT '角色名称，例如仓库管理员、财务管理员',
    MODIFY COLUMN role_type     VARCHAR(32)     NOT NULL COMMENT '角色类型：SYSTEM系统保护，CUSTOM租户自定义',
    MODIFY COLUMN description   VARCHAR(500)    NULL COMMENT '角色说明或适用场景',
    MODIFY COLUMN status        VARCHAR(32)     NOT NULL COMMENT '角色状态：ACTIVE启用，DISABLED停用',
    MODIFY COLUMN version       BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    MODIFY COLUMN created_at    DATETIME(6)     NOT NULL COMMENT '创建时间',
    MODIFY COLUMN created_by    BINARY(16)      NULL COMMENT '创建人用户ID',
    MODIFY COLUMN updated_at    DATETIME(6)     NOT NULL COMMENT '最后更新时间',
    MODIFY COLUMN updated_by    BINARY(16)      NULL COMMENT '最后更新人用户ID',
    MODIFY COLUMN deleted_at    DATETIME(6)     NULL COMMENT '逻辑删除时间',
    MODIFY COLUMN deleted_by    BINARY(16)      NULL COMMENT '逻辑删除人用户ID',
    MODIFY COLUMN delete_reason VARCHAR(255)    NULL COMMENT '逻辑删除原因';

ALTER TABLE iam_role COMMENT = '租户角色主档';

ALTER TABLE iam_user_role
    MODIFY COLUMN tenant_id      BINARY(16)  NOT NULL COMMENT '租户ID',
    MODIFY COLUMN user_id        BINARY(16)  NOT NULL COMMENT 'IAM登录账号ID',
    MODIFY COLUMN role_id        BINARY(16)  NOT NULL COMMENT '角色ID',
    MODIFY COLUMN status         VARCHAR(32) NOT NULL COMMENT '用户角色关系状态：ACTIVE有效，INACTIVE失效',
    MODIFY COLUMN effective_from DATETIME(6) NOT NULL COMMENT '角色分配生效时间',
    MODIFY COLUMN effective_to   DATETIME(6) NULL COMMENT '角色分配失效时间，NULL表示当前有效',
    MODIFY COLUMN created_at     DATETIME(6) NOT NULL COMMENT '创建时间',
    MODIFY COLUMN created_by     BINARY(16)  NULL COMMENT '创建人用户ID',
    MODIFY COLUMN updated_at     DATETIME(6) NOT NULL COMMENT '最后更新时间',
    MODIFY COLUMN updated_by     BINARY(16)  NULL COMMENT '最后更新人用户ID';

ALTER TABLE iam_user_role COMMENT = '用户与角色分配关系';

ALTER TABLE iam_role_resource
    MODIFY COLUMN tenant_id   BINARY(16)  NOT NULL COMMENT '租户ID',
    MODIFY COLUMN role_id     BINARY(16)  NOT NULL COMMENT '角色ID',
    MODIFY COLUMN resource_id BINARY(16)  NOT NULL COMMENT '资源ID，指向我方应用、菜单、页面、按钮或API资源',
    MODIFY COLUMN status      VARCHAR(32) NOT NULL COMMENT '授权状态：ACTIVE有效，INACTIVE失效',
    MODIFY COLUMN created_at  DATETIME(6) NOT NULL COMMENT '创建时间',
    MODIFY COLUMN created_by  BINARY(16)  NULL COMMENT '创建人用户ID',
    MODIFY COLUMN updated_at  DATETIME(6) NOT NULL COMMENT '最后更新时间',
    MODIFY COLUMN updated_by  BINARY(16)  NULL COMMENT '最后更新人用户ID';

ALTER TABLE iam_role_resource COMMENT = '角色与资源授权关系';

ALTER TABLE iam_data_scope_policy
    MODIFY COLUMN id             BINARY(16)      NOT NULL COMMENT '数据范围策略ID，UUID二进制存储',
    MODIFY COLUMN tenant_id      BINARY(16)      NOT NULL COMMENT '租户ID',
    MODIFY COLUMN role_id        BINARY(16)      NOT NULL COMMENT '适用角色ID',
    MODIFY COLUMN application_id BINARY(16)      NOT NULL COMMENT '适用应用ID',
    MODIFY COLUMN scope_type     VARCHAR(32)     NOT NULL COMMENT '数据范围类型：SELF本人，MY_STORES所属门店，MY_CITY所属城市，MY_REGION所属区域，ALL全部',
    MODIFY COLUMN scope_key      VARCHAR(160)    NOT NULL COMMENT '范围键；同一角色同一应用下唯一',
    MODIFY COLUMN scope_ref      BINARY(16)      NULL COMMENT '范围引用ID，预留给组织、区域或门店等业务对象',
    MODIFY COLUMN status         VARCHAR(32)     NOT NULL COMMENT '策略状态：ACTIVE有效，INACTIVE失效',
    MODIFY COLUMN version        BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    MODIFY COLUMN created_at     DATETIME(6)     NOT NULL COMMENT '创建时间',
    MODIFY COLUMN created_by     BINARY(16)      NULL COMMENT '创建人用户ID',
    MODIFY COLUMN updated_at     DATETIME(6)     NOT NULL COMMENT '最后更新时间',
    MODIFY COLUMN updated_by     BINARY(16)      NULL COMMENT '最后更新人用户ID',
    MODIFY COLUMN deleted_at     DATETIME(6)     NULL COMMENT '逻辑删除时间',
    MODIFY COLUMN deleted_by     BINARY(16)      NULL COMMENT '逻辑删除人用户ID',
    MODIFY COLUMN delete_reason  VARCHAR(255)    NULL COMMENT '逻辑删除原因';

ALTER TABLE iam_data_scope_policy COMMENT = '角色维度的数据范围策略';
