package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 仓储费用组合与各项目月度归属，保存在现有成本主表JSON。 */
public record WarehouseCostLedger(int schemaVersion, String warehouseCode,
        String serviceProviderCode, LocalDate paymentDate, BigDecimal depositAmount,
        String contractNo, List<Fee> feeItems) {
    public record Month(String month, BigDecimal amount) {}
    public record Fee(String feeKey, String feeTypeCode, BigDecimal quantity, String unitCode,
            BigDecimal unitPrice, BigDecimal feeAmount, String allocationMethodCode,
            LocalDate attributionDate, LocalDate serviceFrom, LocalDate serviceTo,
            List<Month> monthAllocations, String remark) {
        public Fee withAmountAndMonths(BigDecimal amount, List<Month> months) {
            return new Fee(feeKey, feeTypeCode, quantity, unitCode, unitPrice, amount,
                    allocationMethodCode, attributionDate, serviceFrom, serviceTo, months, remark);
        }
    }
    public WarehouseCostLedger withFees(List<Fee> fees) {
        return new WarehouseCostLedger(schemaVersion, warehouseCode, serviceProviderCode,
                paymentDate, depositAmount, contractNo, fees);
    }
}
