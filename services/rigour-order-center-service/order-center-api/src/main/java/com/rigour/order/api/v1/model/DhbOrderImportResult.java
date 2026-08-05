package com.rigour.order.api.v1.model;

/** 订单域内部导入结果；数量只统计新增或内容发生变化的单据。 */
public record DhbOrderImportResult(
        /** 发生变化的订单数量。 */ int orders,
        /** 发生变化的独立发货单数量。 */ int shipments,
        /** 发生变化的退货单数量。 */ int returns,
        /** 发生变化的收款单和付款单总数量。 */ int financialDocuments) {
    /** 返回本批次所有发生变化的单据总数。 */
    public int totalChanged() { return orders + shipments + returns + financialDocuments; }
}
