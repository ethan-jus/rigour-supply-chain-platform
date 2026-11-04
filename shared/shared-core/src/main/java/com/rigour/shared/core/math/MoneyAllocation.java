package com.rigour.shared.core.math;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** 以分为单位的最大余数分配；并列余数按调用方稳定顺序分配。 */
public final class MoneyAllocation {
    private MoneyAllocation() {}
    public static List<BigDecimal> split(BigDecimal total, List<BigDecimal> weights) {
        if (total == null || total.signum() < 0 || weights == null || weights.isEmpty()
                || weights.stream().anyMatch(w -> w == null || w.signum() <= 0))
            throw new IllegalArgumentException("分摊金额及基数无效");
        BigDecimal cents = total.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2);
        BigDecimal sum = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<BigDecimal> amounts = new ArrayList<>(), remainders = new ArrayList<>();
        BigDecimal used = BigDecimal.ZERO;
        for (var weight : weights) {
            BigDecimal numerator = cents.multiply(weight);
            BigDecimal floor = numerator.divide(sum, 0, RoundingMode.DOWN);
            amounts.add(floor);
            remainders.add(numerator.subtract(floor.multiply(sum)));
            used = used.add(floor);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < weights.size(); i++) order.add(i);
        order.sort(Comparator.<Integer, BigDecimal>comparing(remainders::get).reversed().thenComparingInt(i -> i));
        int remaining = cents.subtract(used).intValueExact();
        for (int i = 0; i < remaining; i++) {
            int index = order.get(i);
            amounts.set(index, amounts.get(index).add(BigDecimal.ONE));
        }
        return amounts.stream().map(v -> v.movePointLeft(2).setScale(2)).toList();
    }
}
