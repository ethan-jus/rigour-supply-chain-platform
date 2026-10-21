package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.*;

import java.time.LocalDate;
import java.util.*;

final class PayrollSchemeSelector {
    private PayrollSchemeSelector() {}

    static Optional<PayrollSchemeVersionView> select(
            List<PayrollSchemeVersionView> versions,
            String employee,
            Long department,
            LocalDate date) {
        var active = active(versions, date);
        var personal =
                active.stream()
                        .filter(v -> v.definition().employeeCodes().contains(employee))
                        .toList();
        var matches =
                personal.isEmpty()
                        ? active.stream()
                                .filter(
                                        v ->
                                                department != null
                                                        && v.definition()
                                                                .departmentIds()
                                                                .contains(department))
                                .toList()
                        : personal;
        if (matches.size() > 1) throw new IllegalStateException("同一员工存在多个有效薪酬方案，请核对适用范围");
        return matches.stream().findFirst();
    }

    static List<PayrollSchemeVersionView> active(
            List<PayrollSchemeVersionView> versions, LocalDate date) {
        Map<String, PayrollSchemeVersionView> latest = new HashMap<>();
        for (var v : versions)
            if ("PUBLISHED".equals(v.status()) && !v.definition().effectiveFrom().isAfter(date)) {
                var old = latest.get(v.schemeId());
                if (old == null
                        || v.definition().effectiveFrom().isAfter(old.definition().effectiveFrom()))
                    latest.put(v.schemeId(), v);
            }
        // 新版本到期后不退回更早版本，避免停用后意外恢复旧工资标准。
        return latest.values().stream()
                .filter(
                        v ->
                                !v.definition().effectiveTo().isBefore(date)
                                        && (v.stopFrom() == null || date.isBefore(v.stopFrom())))
                .toList();
    }

    static PayrollSchemeVersionView standard(
            List<PayrollSchemeVersionView> versions,
            PayrollSchemeVersionView execution,
            LocalDate date) {
        return active(versions, date).stream()
                .filter(v -> v.schemeId().equals(execution.schemeId()))
                .findFirst()
                .orElse(null);
    }
}
