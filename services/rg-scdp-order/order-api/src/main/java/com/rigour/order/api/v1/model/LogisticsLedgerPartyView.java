package com.rigour.order.api.v1.model;

public record LogisticsLedgerPartyView(
        String id,
        String code,
        String name,
        String cityCode,
        String address,
        String contactName,
        String phone,
        String partyTypeCode,
        String referenceIssue) {
    public LogisticsLedgerPartyView(
            String id,
            String code,
            String name,
            String cityCode,
            String address,
            String contactName,
            String phone,
            String partyTypeCode) {
        this(id, code, name, cityCode, address, contactName, phone, partyTypeCode, null);
    }

    public LogisticsLedgerPartyView withReferenceIssue(String issue) {
        return new LogisticsLedgerPartyView(
                id, code, name, cityCode, address, contactName, phone, partyTypeCode, issue);
    }
}
