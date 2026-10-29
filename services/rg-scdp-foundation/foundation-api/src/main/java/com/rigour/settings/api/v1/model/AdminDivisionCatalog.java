package com.rigour.settings.api.v1.model;

import java.util.List;

/** Published global reference snapshot. Codes remain strings, including leading zeroes. */
public record AdminDivisionCatalog(String version, String sourceName, String sourceRef, List<Node> nodes) {
    public AdminDivisionCatalog { nodes = List.copyOf(nodes); }
    public record Node(String code, String name, String level, String parentCode, String regionCode) {}
}
