package com.rigour.erp.api.v1.model;

public record ErpCostOptionView(
        String value,
        String label,
        String name,
        String warehouseTypeCode,
        String regionCode,
        String address) {
    public ErpCostOptionView(String value, String label) {
        this(value, label, null, null, null, null);
    }

    public ErpCostOptionView(String value, String label, String name) {
        this(value, label, name, null, null, null);
    }
}
