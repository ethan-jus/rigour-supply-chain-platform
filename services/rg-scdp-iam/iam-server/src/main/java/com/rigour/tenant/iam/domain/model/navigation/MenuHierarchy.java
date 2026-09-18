package com.rigour.tenant.iam.domain.model.navigation;

import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

/** 租户菜单展示树的不变量；不改变平台资源的功能归属或角色授权。 */
public final class MenuHierarchy {
    private MenuHierarchy() {}

    /** 节点仅包含校验所需的应用、目录类型和有效父级，不依赖数据库实现。 */
    public record Node(UUID id, UUID applicationId, UUID parentId, boolean directory) {}

    /** 检查自指、跨应用、页面挂子项及系统目录与自定义目录混合形成的循环。 */
    public static void validateMove(
            Map<UUID, Node> nodes, UUID applicationId, UUID self, UUID parentId) {
        var seen = new HashSet<UUID>();
        UUID current = parentId;
        while (current != null) {
            if (current.equals(self) || !seen.add(current)) {
                throw new IllegalArgumentException("菜单不能放在自身或自己的子菜单下");
            }
            Node parent = nodes.get(current);
            if (parent == null || !applicationId.equals(parent.applicationId())) {
                throw new IllegalArgumentException("上级菜单不属于当前应用的可配置菜单");
            }
            if (!parent.directory()) throw new IllegalArgumentException("页面不能作为上级菜单，请选择目录");
            current = parent.parentId();
        }
    }
}
