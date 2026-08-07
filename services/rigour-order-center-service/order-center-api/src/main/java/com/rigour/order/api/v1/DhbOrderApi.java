package com.rigour.order.api.v1;

/**
 * 订货宝订单中心V1 HTTP契约常量。
 *
 * <p>这些是本平台的HTTP接口，不是订货宝官方接口路径。查询接口只读取订单中心的本地投影；
 * 同步由Order Center内部定时任务或前端立即同步入口通过Integration V1契约读取订货宝，
 * 再负责本地幂等落库；前端不直接调用Integration执行接口。</p>
 */
public interface DhbOrderApi {
    String BASE_PATH = "/api/v1/orders/dhb";
    String DETAIL_PATH = BASE_PATH + "/{orderSn}";
    String SYNC_PATH = BASE_PATH + "/sync/{connectorId}";
}
