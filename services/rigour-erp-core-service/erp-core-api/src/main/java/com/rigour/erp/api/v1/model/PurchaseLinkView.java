package com.rigour.erp.api.v1.model;

/** ERP 入库单关联采购单。 */
public record PurchaseLinkView(
        /** 订货宝采购单主键。 */ String sourcePurchaseId,
        /** 采购单号。 */ String purchaseOrderNo) { }
