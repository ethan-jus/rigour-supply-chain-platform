package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ErpCostRentalDetails(
        Long warehouseId,
        BigDecimal area,
        String contractNo,
        LocalDate leaseFrom,
        LocalDate leaseTo,
        BigDecimal deposit,
        String premisesType,
        String premisesName,
        String address,
        String landlordName) {
    public ErpCostRentalDetails(
            Long warehouseId,
            BigDecimal area,
            String contractNo,
            LocalDate leaseFrom,
            LocalDate leaseTo,
            BigDecimal deposit) {
        this(warehouseId, area, contractNo, leaseFrom, leaseTo, deposit, null, null, null, null);
    }
}
