package com.rigour.order.domain.cost;

import java.math.BigDecimal;
import java.util.List;

/** 金额和状态规则不依赖框架。 */
public final class LogisticsCostRules {
    private LogisticsCostRules() {}

    public static void money(BigDecimal amount, boolean required) {
        if (amount == null) {
            if (required) throw new IllegalArgumentException("请填写金额，空金额不等于零");
            return;
        }
        if (amount.signum() < 0 || amount.scale() > 2 || amount.precision() - amount.scale() > 14)
            throw new IllegalArgumentException("金额须为非负数，最多两位小数和十四位整数");
    }

    public static void transition(String state, String action, BigDecimal amount, String reason) {
        switch (action) {
            case "UPDATE", "VOID" -> {
                if (!"ACTIVE".equals(state)) throw new IllegalArgumentException("仅有效运输记录可修改或作废");
            }
            case "DELETE" -> {
                if (!"VOID".equals(state)) throw new IllegalArgumentException("仅已作废运输记录可删除");
            }
            case "CONFIRM", "REVOKE" -> throw new IllegalArgumentException("运输记录保存即生效，无需确认或撤销确认");
            default -> throw new IllegalArgumentException("费用操作无效");
        }
        money(amount, false);
        if (List.of("VOID", "DELETE").contains(action) && blank(reason))
            throw new IllegalArgumentException("请填写作废或删除原因");
    }

    public static void sameTrip(List<String> keys) {
        if (keys.isEmpty() || keys.stream().distinct().count() != 1)
            throw new IllegalArgumentException("合运单据须属于同一城市、相同发货方和收货仓");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
