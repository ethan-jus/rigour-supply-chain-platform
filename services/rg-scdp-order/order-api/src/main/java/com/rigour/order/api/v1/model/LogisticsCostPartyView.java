package com.rigour.order.api.v1.model;

public record LogisticsCostPartyView(
        String id, String code, String name, String contactName, String phone, String address) {
    public LogisticsCostPartyView(String id, String code, String name) {
        this(id, code, name, null, null, null);
    }
}
