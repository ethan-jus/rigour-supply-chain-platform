package com.rigour.hr.api.v1.model;

import java.util.List;

/** 外部来源人员到 HR 员工主档的解析请求。 */
public record ExternalEmployeeResolveCommand(String sourceSystem,
                                             String sourceTenantKey,
                                             List<String> sourceEmployeeIds,
                                             List<String> employeeNames) {
    public ExternalEmployeeResolveCommand {
        sourceEmployeeIds = normalized(sourceEmployeeIds);
        employeeNames = normalized(employeeNames);
    }

    private static List<String> normalized(List<String> values) {
        if (values == null) return List.of();
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .distinct()
                .limit(1_000)
                .toList();
    }
}
