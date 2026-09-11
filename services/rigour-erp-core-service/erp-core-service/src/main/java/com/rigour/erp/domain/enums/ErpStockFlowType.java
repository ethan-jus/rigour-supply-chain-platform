package com.rigour.erp.domain.enums;

/** ERP 库存流水业务类型编码；用于库存流水按来源业务追溯。 */
public enum ErpStockFlowType {
    PURCHASE_IN("PURCHASE_IN", "采购入库"),
    SALES_OUT("SALES_OUT", "销售出库"),
    TRANSFER_OUT("TRANSFER_OUT", "调拨出库"),
    PURCHASE_RETURN_OUT("PURCHASE_RETURN_OUT", "采购退货出库"),
    INVENTORY_LOSS_OUT("INVENTORY_LOSS_OUT", "盘亏出库"),
    OTHER_OUT("OTHER_OUT", "其他出库"),
    JOINT_OPERATION_OUT("JOINT_OPERATION_OUT", "联营出库"),
    TRANSFER_IN("TRANSFER_IN", "调拨入库"),
    ADJUST("ADJUST", "库存调整");

    private final String code;
    private final String description;

    ErpStockFlowType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }
}
