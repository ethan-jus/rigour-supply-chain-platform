package com.rigour.integration.api.v1.model;

/** Integration 归一化入库单与采购单的来源关联。 */
public record DhbPurchaseLinkView(
        /** 订货宝采购单主键。 */ String sourcePurchaseId,
        /** 采购单号。 */ String purchaseOrderNo) { }
