package com.rigour.order.api.v1;

import java.util.Map;
import java.util.Set;

/**
 * 订货宝一期只读单据的来源状态字典。
 *
 * <p>这些值是供应商原值，只用于查询、展示和追溯，不等同于平台内部订单状态机。</p>
 */
public final class DhbSourceStatuses {
    /** 订单响应：待核价、待审核、待出库、待发货、待收货、已完成、强制完成、已取消。 */
    public static final Map<String, String> ORDER = Map.of(
            "pricing", "待核价", "pending", "待审核", "stockup", "待出库", "stock_up", "待出库（查询参数/历史兼容值）",
            "shipped", "待发货", "received", "待收货", "finished", "已完成",
            "forcedone", "强制完成", "cancelled", "已取消");

    /** 发货单：待发货、待收货、已收货、已取消。 */
    public static final Map<String, String> SHIPMENT = Map.of(
            "shipped", "待发货", "receivedin", "待收货",
            "received", "已收货", "cancelled", "已取消");

    /** 退货单：待审核、待客户发货、待收货、待退款、已完成、已取消。 */
    public static final Map<String, String> RETURN = Map.of(
            "return_audit", "待退货审核", "shipp_cust", "待客户发货",
            "shipped", "待收货", "refunded", "待退款",
            "finished", "已完成", "cancelled", "已取消");

    /** 收付款单：待确认、已确认、已取消。 */
    public static final Map<String, String> FINANCIAL = Map.of(
            "pend_receipt", "待确认", "pend_receipted", "已确认", "canceled", "已取消");

    /** 订单付款：列表状态六种，详情还可能返回unoblig待确认付款。 */
    public static final Map<String, String> ORDER_PAYMENT = Map.of(
            "oblig", "待收款", "uncollect", "部分收款", "paided", "已收款",
            "cancelled", "已取消", "wait", "待确认", "part", "部分确认", "unoblig", "待确认付款");

    /** 财务单据类型；RECEIPT为收款单，PAYMENT为付款单。 */
    public static final Set<String> FINANCIAL_DOCUMENT_TYPES = Set.of("RECEIPT", "PAYMENT");

    private DhbSourceStatuses() {
    }
}
