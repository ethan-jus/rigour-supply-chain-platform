package com.rigour.order.api.v1;

/**
 * 订货宝订单中心V1 HTTP契约常量。
 *
 * <p>这些是本平台的HTTP接口，不是订货宝官方接口路径。接口只读取订单中心的本地投影；
 * 订货宝认证、同步和原始报文处理由Integration负责。</p>
 */
public interface DhbOrderApi {
    String BASE_PATH = "/api/v1/orders/dhb";
    String DETAIL_PATH = BASE_PATH + "/{orderSn}";
}
