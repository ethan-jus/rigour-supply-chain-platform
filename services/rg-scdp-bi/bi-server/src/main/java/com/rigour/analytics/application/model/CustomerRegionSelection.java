package com.rigour.analytics.application.model;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.Set;

/** Optional report filter shared by the SQL read scope and its cache key. */
public final class CustomerRegionSelection {
    private CustomerRegionSelection() {}

    private static final String ROOT = "/api/v1/analytics/supply/dashboard/";
    private static final Set<String> REPORTS = Set.of("overview", "overview/ranking",
            "operating-analysis", "receipt-details", "sales-analysis", "sales-analysis/summary",
            "payment-risk", "receivables");

    public static String current() {
        var context = RequestContextHolder.getRequestAttributes();
        return context instanceof ServletRequestAttributes attributes ? from(attributes.getRequest()) : null;
    }

    public static String from(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith(ROOT) || !REPORTS.contains(path.substring(ROOT.length()))) return null;
        String code = request.getParameter("customerRegionCode");
        return code == null || code.isBlank() ? null : code.strip();
    }
}
