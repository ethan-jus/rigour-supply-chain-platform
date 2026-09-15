package com.rigour.hr.domain.model;

import java.util.Locale;

/** 员工任职状态；离职与账号停用分别表达，未知来源不得推断为在职。 */
public final class EmploymentStatus {
    private EmploymentStatus() { }

    public static String fromSource(String value) {
        if (value == null || value.isBlank()) return "PENDING";
        String text = value.strip().toUpperCase(Locale.ROOT);
        if (text.equals("LEFT") || text.contains("离职")) return "LEFT";
        if (text.equals("INACTIVE") || text.contains("停") || text.contains("禁") || text.contains("冻结")) {
            return "INACTIVE";
        }
        if (text.equals("ACTIVE") || text.equals("在职")) return "ACTIVE";
        return "PENDING";
    }
}
