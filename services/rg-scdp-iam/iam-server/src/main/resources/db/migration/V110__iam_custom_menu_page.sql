-- 若依式自定义页面：菜单节点可直接携带路由地址、组件路径与权限标识。
-- 只放宽 PAGE，MENU/BUTTON 语义不变；已绑定资源的历史节点保持原行为。
ALTER TABLE iam_app_menu_node
    ADD COLUMN route_key VARCHAR(191) NULL COMMENT '自定义页面路由唯一标识' AFTER active_resource_id,
    ADD COLUMN route_path VARCHAR(255) NULL COMMENT '自定义页面路由地址' AFTER route_key,
    ADD COLUMN component_path VARCHAR(255) NULL COMMENT '自定义页面组件路径' AFTER route_path,
    ADD COLUMN permission_code VARCHAR(128) NULL COMMENT '自定义页面权限标识' AFTER component_path,
    DROP CHECK ck_app_menu_feature,
    ADD CONSTRAINT ck_app_menu_feature CHECK (
        node_type='MENU'
        OR resource_id IS NOT NULL
        OR (node_type='PAGE' AND route_key IS NOT NULL AND route_path IS NOT NULL AND component_path IS NOT NULL)
    );
