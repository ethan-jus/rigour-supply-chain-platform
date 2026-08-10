package com.rigour.sales.application.port.out;

/** 高德附近门店上游失败；不携带第三方回执详情。 */
public final class AmapPoiException extends RuntimeException {

    public AmapPoiException(String message) {
        super(message);
    }
}
