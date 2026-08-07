package com.rigour.integration.application.port.out;

/** 飞书 JSSDK 票据端口；飞书 HTTP 协议细节只允许存在于 Integration 基础设施层。 */
@FunctionalInterface
public interface FeishuJsapiClient {

    String getJsapiTicket();
}
