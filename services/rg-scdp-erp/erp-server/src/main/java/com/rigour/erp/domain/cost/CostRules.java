package com.rigour.erp.domain.cost;

import java.math.BigDecimal;
import java.util.List;

/** 金额和状态规则不依赖框架。 */
public final class CostRules {
    private CostRules() {}

    public static void money(BigDecimal amount, boolean required) {
        if (amount == null) {
            if (required) throw new IllegalArgumentException("请填写金额，空金额不等于零");
            return;
        }
        if (amount.signum() < 0 || amount.scale() > 2 || amount.precision() - amount.scale() > 14)
            throw new IllegalArgumentException("金额须为非负数，最多两位小数和十四位整数");
    }

    public static void transition(String state, String action, BigDecimal amount, String reason) {
        if ("VOID".equals(state)) throw new IllegalArgumentException("已作废费用不能变更");
        if ("CONFIRM".equals(action)) {
            if (!"DRAFT".equals(state)) throw new IllegalArgumentException("仅待确认费用可确认");
            money(amount, true);
            if (amount.signum() == 0 && blank(reason))
                throw new IllegalArgumentException("零元费用确认需填写原因");
        }
        if (("VOID".equals(action) || "CONFIRMED".equals(state)) && blank(reason))
            throw new IllegalArgumentException("作废或修改已确认费用需填写原因");
    }

    public static void sameTrip(List<String> keys) {
        if (keys.isEmpty() || keys.stream().distinct().count() != 1)
            throw new IllegalArgumentException("合运单据须属于同一城市、相同发货方和收货仓");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
