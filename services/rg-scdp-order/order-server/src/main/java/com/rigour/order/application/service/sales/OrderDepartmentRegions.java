package com.rigour.order.application.service.sales;

import com.rigour.order.application.port.out.HrEmployeeDisplayClient.DepartmentDisplay;
import com.rigour.order.application.port.out.CrmCustomerAreaDisplayClient.CustomerAreaDisplay;
import java.util.*;

final class OrderDepartmentRegions {
    private OrderDepartmentRegions() {}

    static Map<String, DepartmentDisplay> byName(List<DepartmentDisplay> departments) {
        Set<Long> sales = new HashSet<>();
        departments.stream().filter(d -> "销售部".equals(d.name()) && "ACTIVE".equals(d.status()))
                .forEach(d -> sales.add(d.id()));
        Set<Long> descendants = new HashSet<>(sales);
        boolean changed;
        do {
            changed = false;
            for (var d : departments) {
                if (d.parentId() != null && descendants.contains(d.parentId())) changed |= descendants.add(d.id());
            }
        } while (changed);
        Map<String, DepartmentDisplay> result = new LinkedHashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (var d : departments) {
            if (!descendants.contains(d.id()) || sales.contains(d.id()) || !"ACTIVE".equals(d.status())) continue;
            String name = normalize(d.name());
            if (result.putIfAbsent(name, d) != null) ambiguous.add(name);
        }
        ambiguous.forEach(result::remove);
        return result;
    }

    static Map<String, Long> ids(List<DepartmentDisplay> departments, List<CustomerAreaDisplay> areas) {
        var names = byName(departments);
        Map<String, Long> result = new LinkedHashMap<>();
        for (var area : areas) {
            var department = names.get(normalize(area.areaName()));
            if (department != null) result.put(area.areaCode(), department.id());
        }
        return Map.copyOf(result);
    }

    static String normalize(String name) {
        return name == null ? "" : name.strip().replaceFirst("市$", "");
    }
}
