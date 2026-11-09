package com.rigour.merchant.api.v1.model;

public record CostCustomerView(
        String id, String code, String name, String contactName, String phone, String address) {
    public CostCustomerView(String id, String code, String name) {
        this(id, code, name, null, null, null);
    }
}
