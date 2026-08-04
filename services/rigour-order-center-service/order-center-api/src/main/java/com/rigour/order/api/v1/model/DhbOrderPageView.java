package com.rigour.order.api.v1.model;

import java.util.List;

/** 订单中心本地投影分页；外部同步统计由Integration同步任务返回。 */
public record DhbOrderPageView(
        /** 本地内部订单模型总数。 */
        long total,
        /** 本次外部同步返回总数；本地查询时为0。 */
        int providerTotal,
        /** 本次同步发生变化并成功落库的内部订单数。 */
        int synchronizedCount,
        /** 当前页的内部订单列表。 */
        List<DhbOrderView> items) {
}
