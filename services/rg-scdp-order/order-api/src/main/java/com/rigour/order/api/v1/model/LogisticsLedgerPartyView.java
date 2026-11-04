package com.rigour.order.api.v1.model;

public record LogisticsLedgerPartyView(String id, String code, String name, String cityCode,
        String address, String contactName, String phone, String partyTypeCode) {}
