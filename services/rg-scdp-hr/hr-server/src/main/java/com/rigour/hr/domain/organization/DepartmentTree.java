package com.rigour.hr.domain.organization;

import java.util.*;

/** 组织树循环/孤儿检查及祖先关系推导；不依赖框架和数据库。 */
public final class DepartmentTree {
    private DepartmentTree() {}

    public record Node(long id, Long parentId) {}

    public record Path(long ancestorId, long descendantId, int depth) {}

    public static List<Path> closure(List<Node> nodes) {
        Map<Long, Node> index = new HashMap<>();
        for (Node node : nodes)
            if (index.put(node.id(), node) != null) throw new IllegalArgumentException("部门重复");
        List<Path> result = new ArrayList<>();
        for (Node node : nodes) {
            Set<Long> seen = new HashSet<>();
            Node cursor = node;
            int depth = 0;
            while (cursor != null) {
                if (!seen.add(cursor.id())) throw new IllegalArgumentException("部门不能移动到自身或下级部门");
                if (depth > 100) throw new IllegalArgumentException("部门层级过深");
                result.add(new Path(cursor.id(), node.id(), depth++));
                if (cursor.parentId() == null) break;
                cursor = index.get(cursor.parentId());
                if (cursor == null) throw new IllegalArgumentException("上级部门不存在或不属于当前租户");
            }
        }
        return List.copyOf(result);
    }
}
