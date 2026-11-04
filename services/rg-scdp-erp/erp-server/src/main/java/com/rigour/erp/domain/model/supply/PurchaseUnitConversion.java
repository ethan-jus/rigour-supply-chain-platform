package com.rigour.erp.domain.model.supply;

import java.math.BigDecimal;
import java.util.Optional;

/** 采购单位换算只使用明确配置，缺少换算率时不猜测包装数量。 */
public final class PurchaseUnitConversion {
    private PurchaseUnitConversion() {}

    public static Optional<BigDecimal> factor(
            String unit,
            String base,
            String middle,
            BigDecimal middleRate,
            String big,
            BigDecimal bigRate) {
        if (unit == null || base == null) return Optional.empty();
        if (unit.equals(base)) return Optional.of(BigDecimal.ONE);
        if (unit.equals(middle) && middleRate != null && middleRate.signum() > 0)
            return Optional.of(middleRate);
        if (unit.equals(big) && bigRate != null && bigRate.signum() > 0)
            return Optional.of(bigRate);
        return Optional.empty();
    }
}
