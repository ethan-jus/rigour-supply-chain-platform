package com.rigour.integration.application.port.out;

/** 高德附近门店上游失败；不携带第三方回执详情。 */
public final class AmapPoiException extends RuntimeException {
    private final String reason;

    public AmapPoiException(String message) {
        this("AMAP_UNAVAILABLE",message);
    }

    public AmapPoiException(String reason,String message) {
        super(message);
        this.reason=reason;
    }

    public String reason(){return reason;}
}
