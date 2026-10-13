package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ErpCostRentalDetails(
        Long warehouseId,
        BigDecimal area,
        String contractNo,
        LocalDate leaseFrom,
        LocalDate leaseTo,
        BigDecimal deposit) {}
