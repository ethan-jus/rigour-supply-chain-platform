package com.rigour.order.application.port.out;

/** 资金附件短时访问 URL 解析端口；实现不得返回永久公开地址。 */
public interface FundAttachmentUrlResolver {
    FundAttachmentUrlResolver NONE = (tenantId, objectKey) -> null;

    String temporaryUrl(String tenantId, String objectKey);
}
