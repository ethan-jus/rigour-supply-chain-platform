package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 已确认采购的约定运费来源；不是实际运输费用。 */
public record ProcurementCostSource(String procurementNo, long revision, LocalDate purchaseDate,
        String supplierCode, String supplierName, String supplierAddress, String supplierContact,
        String supplierPhone, long warehouseId, String warehouseCode, String warehouseName,
        String cityCode, String receiverName, String receiverPhone, String receivingAddress,
        String freightBearerCode, BigDecimal freightAmount) {}
