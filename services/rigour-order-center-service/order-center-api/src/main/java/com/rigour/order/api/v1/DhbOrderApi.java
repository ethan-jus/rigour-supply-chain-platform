package com.rigour.order.api.v1;

/**
 * 订货宝订单中心V1 HTTP契约常量。
 *
 * <p>这些是本平台的HTTP接口，不是订货宝官方接口路径。查询接口只读取订单中心的本地投影；
 * 同步接口通过Integration V1契约读取订货宝，再由订单中心负责本地幂等落库。</p>
 */
public interface DhbOrderApi {
    String BASE_PATH = "/api/v1/orders/dhb";
    String DETAIL_PATH = BASE_PATH + "/{orderSn}";
    String SYNC_PATH = BASE_PATH + "/sync/{connectorId}";
}
