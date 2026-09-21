package com.rigour.order.application.port.out;

/** 发票附件上传端口；实现负责把文件写入私有对象存储并返回对象键。 */
public interface InvoiceAttachmentStorage {
    String upload(String tenantId, String orderNo, String fileName, String contentType, byte[] content);
}
