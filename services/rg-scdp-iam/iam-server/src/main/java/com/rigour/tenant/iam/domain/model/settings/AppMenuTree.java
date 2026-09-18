package com.rigour.tenant.iam.domain.model.settings;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 供应链配置树的不变量；显示层级可编辑，实际页面和操作仍绑定稳定能力。 */
public final class AppMenuTree {
    private AppMenuTree() {}

    public record Node(
            UUID id,
            UUID parentId,
            String type,
            UUID resourceId,
            String name,
            String iconKey,
            int sortOrder,
            boolean visible,
            String status,
            boolean protectedNode,
            long version,
            String resourceCode,
            String permissionCode,
            String routeKey,
            String routePath,
            String componentPath) {}

    /** 在同租户同应用快照上验证整个树，覆盖移动、停用和保护入口的祖先变化。 */
    public static void validate(List<Node> nodes) {
        Map<UUID, Node> index =
                nodes.stream().collect(Collectors.toMap(Node::id, Function.identity()));
        var resources = new HashSet<UUID>();
        for (Node node : nodes) {
            if (!List.of("MENU", "PAGE", "BUTTON").contains(node.type()))
                throw new IllegalArgumentException("菜单类型无效");
            if (node.name() == null || node.name().isBlank() || node.name().length() > 128)
                throw new IllegalArgumentException("菜单名称应为 1 到 128 个字符");
            if (!List.of("ACTIVE", "DISABLED").contains(node.status()))
                throw new IllegalArgumentException("菜单状态无效");
            if (node.iconKey() != null && !AppMenuIcons.KEYS.contains(node.iconKey()))
                throw new IllegalArgumentException("请选择有效的内置图标");
            if ("BUTTON".equals(node.type()) && node.resourceId() == null)
                throw new IllegalArgumentException("按钮必须绑定已实现功能");
            if ("PAGE".equals(node.type())
                    && node.resourceId() == null
                    && (blank(node.routeKey())
                            || blank(node.routePath())
                            || blank(node.componentPath())))
                throw new IllegalArgumentException("自定义页面必须填写路由地址和组件路径");
            if (node.resourceId() != null && !resources.add(node.resourceId()))
                throw new IllegalArgumentException("该功能已添加到菜单");
            Node parent = node.parentId() == null ? null : index.get(node.parentId());
            if (node.parentId() != null && parent == null)
                throw new IllegalArgumentException("上级菜单不存在或不属于当前应用");
            if ("BUTTON".equals(node.type()) && (parent == null || !"PAGE".equals(parent.type())))
                throw new IllegalArgumentException("按钮必须添加到页面下");
            if (!"BUTTON".equals(node.type()) && parent != null && !"MENU".equals(parent.type()))
                throw new IllegalArgumentException("目录和页面的上级只能是目录");
            var seen = new HashSet<UUID>();
            Node cursor = node;
            while (cursor != null) {
                if (!seen.add(cursor.id())) throw new IllegalArgumentException("菜单不能移动到自身或子菜单下");
                if (node.protectedNode()
                        && (!"ACTIVE".equals(cursor.status()) || !cursor.visible()))
                    throw new IllegalArgumentException("系统管理恢复入口及其上级不能隐藏或停用");
                cursor = cursor.parentId() == null ? null : index.get(cursor.parentId());
            }
        }
    }

    public static boolean enabled(Node node, Map<UUID, Node> index) {
        var seen = new HashSet<UUID>();
        Node current = node;
        while (current != null) {
            if (!seen.add(current.id()) || !"ACTIVE".equals(current.status())) return false;
            if (current.parentId() == null) return true;
            current = index.get(current.parentId());
            if (current == null) return false;
        }
        return false;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
