package com.rigour.sales.application.port.out;

import java.net.URI;
import java.util.UUID;

/** 服务端短时授权给语音供应商读取原音频；地址不能回传客户端或落日志。 */
public interface SalesAudioSource {
    URI temporaryUrl(UUID tenantId, String objectKey);
}
