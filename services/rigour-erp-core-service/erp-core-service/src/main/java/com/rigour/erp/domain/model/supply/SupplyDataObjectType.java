package com.rigour.erp.domain.model.supply;

/** ERP 供应链统一同步对象类型；每次请求只同步一个明确对象。 */
public enum SupplyDataObjectType {
    SUPPLIER,
    PURCHASE_ORDER,
    PURCHASE_RETURN,
    WAREHOUSING_RECEIPT,
    WAREHOUSE,
    INVENTORY
}
