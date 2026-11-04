package com.rigour.erp.application.port.out;

/** 单个采购合同附件的私有对象存储，采购主表只保留文件键。 */
public interface ProcurementContractStorage {
    String upload(String tenant, String actor, String contentType, byte[] content);

    String temporaryUrl(String key);
}
