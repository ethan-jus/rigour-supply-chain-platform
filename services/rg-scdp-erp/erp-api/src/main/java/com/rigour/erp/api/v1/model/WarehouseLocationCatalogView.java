package com.rigour.erp.api.v1.model;

import java.util.List;

/** 仓库所在地可选择的公共行政区划。 */
public record WarehouseLocationCatalogView(String version, List<Node> nodes) {
    public record Node(String code, String name, String level, String parentCode) {}
}
