package com.rigour.order.api.controller;

import java.util.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

/** The HTTP contract separates receipt validity from financial confirmation; storage stays compatible. */
final class PaymentStatusFilter {
    private PaymentStatusFilter() {}
    static String resolve(String legacy, String receipt, String confirmation) {
        var allowed = new LinkedHashSet<>(List.of("PENDING", "RECEIVED", "CHECKED", "CANCELLED"));
        if (legacy != null && !legacy.isBlank()) allowed.retainAll("CONFIRMED".equals(legacy)
                ? Set.of("RECEIVED", "CHECKED") : Set.of(legacy));
        if (receipt != null && !receipt.isBlank()) {
            if (!Set.of("PENDING", "RECEIVED", "CANCELLED").contains(receipt)) throw invalid();
            allowed.retainAll("RECEIVED".equals(receipt) ? Set.of("RECEIVED", "CHECKED") : Set.of(receipt));
        }
        if (confirmation != null && !confirmation.isBlank()) {
            if (!Set.of("UNCONFIRMED", "CONFIRMED").contains(confirmation)) throw invalid();
            allowed.retainAll(Set.of("CONFIRMED".equals(confirmation) ? "CHECKED" : "RECEIVED"));
        }
        if (allowed.size() == 4) return null;
        if (allowed.equals(Set.of("RECEIVED", "CHECKED"))) return "CONFIRMED";
        return allowed.size() == 1 ? allowed.iterator().next() : "NO_MATCH";
    }
    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.BAD_REQUEST, "回款状态或确认状态无效", List.of());
    }
}
