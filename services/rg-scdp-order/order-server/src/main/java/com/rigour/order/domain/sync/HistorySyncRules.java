package com.rigour.order.domain.sync;

import java.math.*;
import java.time.*;
import java.util.*;

/** 只含确定性业务判断：历史订单不新建、组金额守恒、不重复核销。 */
public final class HistorySyncRules {
    public static final Instant CUTOVER =
            OffsetDateTime.parse("2026-09-04T00:00:00+08:00").toInstant();

    private HistorySyncRules() {}

    public static BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException("金额不能为空或为负数");
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    public static void equal(BigDecimal a, BigDecimal b, String field) {
        if (money(a).compareTo(money(b)) != 0)
            throw new IllegalArgumentException(field + "金额不一致，不能激活关联");
    }

    public static void evidence(String value) {
        if (value == null || value.strip().length() < 5 || value.length() > 1000)
            throw new IllegalArgumentException("请填写5至1000字的原始核对依据");
    }

    public static String classify(Instant date, boolean hasUnboundHistory) {
        if (date == null) return "DATE_REVIEW";
        if (date.isBefore(CUTOVER)) return "HISTORY_PENDING";
        return hasUnboundHistory ? "NEW_OR_HISTORY_REVIEW" : "NEW";
    }
}
