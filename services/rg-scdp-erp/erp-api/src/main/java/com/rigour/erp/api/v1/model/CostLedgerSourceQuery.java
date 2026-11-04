package com.rigour.erp.api.v1.model;

import java.time.LocalDate;

public record CostLedgerSourceQuery(String orderTypeCode, String documentNo, String partyName,
        String sourceWarehouseCode, String targetWarehouseCode, String cityCode,
        LocalDate dateFrom, LocalDate dateTo, Integer begin, Boolean exact, Boolean historical) {
    public CostLedgerSourceQuery {
        begin = begin == null ? 0 : begin;
        exact = Boolean.TRUE.equals(exact);
        historical = Boolean.TRUE.equals(historical);
    }
    public CostLedgerSourceQuery(String orderTypeCode,String documentNo,String partyName,String sourceWarehouseCode,String targetWarehouseCode,String cityCode,LocalDate dateFrom,LocalDate dateTo,int begin,boolean exact){
        this(orderTypeCode,documentNo,partyName,sourceWarehouseCode,targetWarehouseCode,cityCode,dateFrom,dateTo,begin,exact,false);
    }
}
