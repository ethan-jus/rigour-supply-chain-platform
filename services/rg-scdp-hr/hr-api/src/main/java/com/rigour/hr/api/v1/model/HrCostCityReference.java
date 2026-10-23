package com.rigour.hr.api.v1.model;

import java.util.List;

/** 成本归属所需的最小经营城市部门引用，不包含部门联系人及员工资料。 */
public record HrCostCityReference(
        String code, String name, String departmentId, List<String> departmentPath) {
    public static final String HEADQUARTERS_CODE = "COST_HEADQUARTERS";
    public static final String HEADQUARTERS_NAME = "总部";

    public HrCostCityReference {
        departmentPath = List.copyOf(departmentPath);
    }
}
