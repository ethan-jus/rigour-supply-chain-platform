package com.rigour.erp.domain.model.supply;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** ERP 供应商导入模型；地址、联系方式、税号和银行账号按当前内部使用要求保留完整值。 */
public record Supplier(String sourceId, String sourceGuid, String code, String name,
                       String areaName, String address, String contactName,
                       String mobile, String phone, String email,
                       String accountName, String bankName, String bankAccount,
                       String invoiceTitle, String taxpayerNumber,
                       String remark,
                       Instant sourceUpdatedAt, Map<String, Object> sourceFields,
                       String payloadHash) {
    public Supplier {
        sourceFields = sourceFields == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(sourceFields));
    }
}
