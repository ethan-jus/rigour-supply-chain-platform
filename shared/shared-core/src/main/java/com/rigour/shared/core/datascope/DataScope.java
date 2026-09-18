package com.rigour.shared.core.datascope;

/**
 * IAM 下发的统一数据范围类型。
 * 枚举只表达策略名称，具体过滤条件必须由各领域服务结合自身模型执行。
 */
public enum DataScope {
    SELF,
    MY_STORES,
    MY_CITY,
    MY_REGION,
    SUPPLIER_SELF,
    AGENT_TREE
}
