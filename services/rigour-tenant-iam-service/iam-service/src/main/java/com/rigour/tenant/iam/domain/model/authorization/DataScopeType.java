package com.rigour.tenant.iam.domain.model.authorization;

/** 一期允许型DataScope枚举，与数据库CHECK约束保持一致。 */
public enum DataScopeType {
    SELF,
    MY_STORES,
    MY_CITY,
    MY_REGION,
    ALL
}
