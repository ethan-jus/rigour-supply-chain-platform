package com.rigour.erp.api.v1.model;

import java.util.List;

/** 外部来源商品引用到 ERP 商品规格的批量解析请求。 */
public record ExternalProductResolveCommand(
        String preferredSourceSystem,
        List<ExternalProductResolveRowCommand> rows) {
    public ExternalProductResolveCommand {
        preferredSourceSystem = clean(preferredSourceSystem);
        rows = rows == null ? List.of() : rows.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.strip().toUpperCase(java.util.Locale.ROOT);
        return text.length() > 32 ? text.substring(0, 32) : text;
    }
}
