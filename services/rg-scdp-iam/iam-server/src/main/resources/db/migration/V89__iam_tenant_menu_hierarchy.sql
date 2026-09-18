-- 展示层级可覆盖为根节点、系统目录或租户目录；保留既有租户名称和层级。
ALTER TABLE iam_tenant_menu_config
    ADD COLUMN parent_resource_id BINARY(16) NULL COMMENT '租户指定的系统目录父节点',
    ADD COLUMN parent_overridden TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '1使用租户父级，父级为空表示根节点',
    ADD CONSTRAINT fk_iam_menu_parent_resource FOREIGN KEY (parent_resource_id) REFERENCES iam_resource(id),
    ADD CONSTRAINT ck_iam_menu_parent_override CHECK (parent_overridden IN (0,1)),
    ADD CONSTRAINT ck_iam_menu_single_parent CHECK (parent_resource_id IS NULL OR parent_group_id IS NULL);

ALTER TABLE iam_tenant_menu_group
    ADD COLUMN parent_resource_id BINARY(16) NULL COMMENT '系统目录父节点，与parent_id互斥',
    ADD CONSTRAINT fk_iam_group_parent_resource FOREIGN KEY (parent_resource_id) REFERENCES iam_resource(id),
    ADD CONSTRAINT ck_iam_group_single_parent CHECK (parent_resource_id IS NULL OR parent_id IS NULL);
