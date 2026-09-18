package com.rigour.integration.api.v1.model;

import java.time.Instant;
import java.util.UUID;

/** 单页同步范围；没有全量默认值，必须由页面明确指定一种对象。 */
public record DhbPageSyncCommand(
        Scope scope, UUID connectorId, Instant from, Instant to, Integer maxPages, Boolean incremental) {
    public DhbPageSyncCommand(Scope scope, UUID connectorId, Instant from, Instant to, Integer maxPages) {
        this(scope, connectorId, from, to, maxPages, false);
    }
    public enum Scope {
        /** 订单页组合同步：按 SALES_ORDER（含明细）→ RECEIPT → PAYMENT 顺序执行。 */
        ORDER_SALES_PACKAGE,
        SALES_ORDER,
        RECEIPT,
        PAYMENT,
        SHIPMENT,
        TRANSFER,
        CUSTOMER,
        ADDRESS,
        AREA,
        CLIENT_TYPE,
        CATEGORY,
        BRAND,
        SPECIFICATION,
        TAG,
        PRODUCT_SPU,
        SUPPLIER,
        WAREHOUSE,
        PURCHASE_ORDER,
        PURCHASE_RETURN,
        WAREHOUSING_RECEIPT,
        INVENTORY
    }

    public DhbPageSyncCommand {
        if (scope == null || connectorId == null)
            throw new IllegalArgumentException("请选择同步对象和连接器");
        if (Boolean.TRUE.equals(incremental)) {
            if (scope != Scope.CUSTOMER && scope != Scope.ORDER_SALES_PACKAGE)
                throw new IllegalArgumentException("该对象不支持服务端增量同步");
            if (from != null || to != null)
                throw new IllegalArgumentException("增量同步由服务器确定时间范围，请勿显式指定窗口");
        } else if (from == null || to == null || !from.isBefore(to))
            throw new IllegalArgumentException("请选择有效时间范围");
        if (maxPages != null && (maxPages < 1 || maxPages > 500))
            throw new IllegalArgumentException("最多页数范围为1至500");
    }
}
