package com.rigour.merchant.domain.model;

import java.util.List;

/** CRM 从外部系统接收的主数据类型；顺序体现依赖关系。 */
public enum CrmMasterDataObjectType {
    CUSTOMER_TYPE,
    CUSTOMER_AREA,
    STAFF,
    CUSTOMER,
    ADDRESS;

    public static final List<CrmMasterDataObjectType> SYNC_ORDER = List.of(values());

    public static CrmMasterDataObjectType parse(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) return null;
        try {
            return valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "objectType仅支持ALL、CUSTOMER_TYPE、CUSTOMER_AREA、STAFF、CUSTOMER、ADDRESS");
        }
    }
}
