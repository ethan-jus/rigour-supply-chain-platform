package com.rigour.order.api.v1.model;

/** 订货宝同步的数据范围策略。 */
public enum DhbOrderSyncMode {
    /** 忽略增量时间窗口，按供应商接口支持的范围做一次完整对账。 */
    FULL,
    /** 对支持更新时间条件的对象使用窗口；付款单没有可靠更新时间条件。 */
    INCREMENTAL
}
