package com.rigour.analytics.application.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 从ERP分类父子关系建立自包含闭包；环和断链阻止刷新，避免静默扩大或丢失筛选范围。 */
public final class ProductCategoryHierarchy {
    private ProductCategoryHierarchy() { }
    public record Node(Long id, Long parentId) { }
    public record Edge(Long ancestorId, Long descendantId, int depth) { }

    public static List<Edge> closure(List<Node> nodes) {
        Map<Long, Node> indexed = new HashMap<>();
        for (Node node : nodes) {
            if (node.id() == null || indexed.put(node.id(), node) != null)
                throw new IllegalStateException("ERP分类主键缺失或重复");
        }
        List<Edge> result = new ArrayList<>();
        for (Node leaf : nodes) {
            var visited = new HashSet<Long>();
            Node cursor = leaf;
            int depth = 0;
            while (cursor != null) {
                if (!visited.add(cursor.id())) throw new IllegalStateException("ERP分类父子关系存在环");
                result.add(new Edge(cursor.id(), leaf.id(), depth++));
                Long parent = cursor.parentId();
                if (parent == null || parent == 0) break;
                cursor = indexed.get(parent);
                if (cursor == null) throw new IllegalStateException("ERP分类父级不存在或已删除");
            }
        }
        return List.copyOf(result);
    }
}
