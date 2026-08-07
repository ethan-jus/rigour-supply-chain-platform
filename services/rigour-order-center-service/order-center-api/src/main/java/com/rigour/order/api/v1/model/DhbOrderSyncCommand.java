package com.rigour.order.api.v1.model;

import java.time.Instant;

/** Portal 请求 Order Center 同步订货宝订单域数据的参数。 */
public record DhbOrderSyncCommand(
        /** true 通过当前 Integration V1 补拉订单详情；省略时为 true。 */ Boolean includeDetails,
        /** 最多读取页数，范围 1..100；省略时为 100。 */ Integer maxPages,
        /** 可选的订货宝订单更新时间窗口起点；必须与updatedTo成对出现。 */ Instant updatedFrom,
        /** 可选的订货宝订单更新时间窗口终点；必须与updatedFrom成对出现。 */ Instant updatedTo,
        /** 同步对象范围；省略时为ALL，Portal按当前页面选择具体范围。 */ DhbOrderSyncScope scope) {

    public DhbOrderSyncCommand(Boolean includeDetails, Integer maxPages) {
        this(includeDetails, maxPages, null, null, DhbOrderSyncScope.ALL);
    }

    public DhbOrderSyncCommand(Boolean includeDetails, Integer maxPages,
                               Instant updatedFrom, Instant updatedTo) {
        this(includeDetails, maxPages, updatedFrom, updatedTo, DhbOrderSyncScope.ALL);
    }

    public DhbOrderSyncCommand {
        includeDetails = includeDetails == null ? Boolean.TRUE : includeDetails;
        maxPages = maxPages == null ? 100 : maxPages;
        scope = scope == null ? DhbOrderSyncScope.ALL : scope;
        if (maxPages < 1 || maxPages > 100) {
            throw new IllegalArgumentException("maxPages必须在1到100之间");
        }
        if ((updatedFrom == null) != (updatedTo == null)) {
            throw new IllegalArgumentException("updatedFrom和updatedTo必须同时提供");
        }
        if (updatedFrom != null && !updatedFrom.isBefore(updatedTo)) {
            throw new IllegalArgumentException("updatedFrom必须早于updatedTo");
        }
    }
}
