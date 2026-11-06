package com.rigour.erp.domain.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 采购货款只读校验；订货宝整单应付为折后货款，明细折前合计不能直接用于金额一致性判断。 */
public final class ProcurementGoodsSourceRules {
    private ProcurementGoodsSourceRules() {}

    public static boolean local(String sourceSystem) {
        return Set.of("SYSTEM", "MANUAL", "LOCAL", "ERP")
                .contains(Objects.toString(sourceSystem, "SYSTEM").toUpperCase(Locale.ROOT));
    }

    public static String issue(
            String sourceSystem,
            long lineCount,
            long unknownPrices,
            BigDecimal orderAmount,
            BigDecimal linesAmount) {
        if (lineCount == 0 || linesAmount == null) return "MISSING_LINES";
        if (orderAmount == null || orderAmount.signum() < 0) return "MISSING_AMOUNT";
        if (unknownPrices > 0 && !local(sourceSystem) && orderAmount.signum() == 0)
            return "MISSING_PRICE";
        if ("DINGHUOBAO".equalsIgnoreCase(sourceSystem)) return null;
        if (orderAmount == null
                || orderAmount
                                .setScale(2, RoundingMode.HALF_UP)
                                .compareTo(linesAmount.setScale(2, RoundingMode.HALF_UP))
                        != 0) return "AMOUNT_MISMATCH";
        return null;
    }
}
