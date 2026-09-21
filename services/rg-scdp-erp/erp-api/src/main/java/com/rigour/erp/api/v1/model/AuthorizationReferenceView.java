package com.rigour.erp.api.v1.model;

/** 授权配置专用的最小主数据标识，不包含客户或仓库联系人资料。 */
public record AuthorizationReferenceView(
        String key,
        String name,
        String parentKey,
        String status,
        long revision,
        /** 仓库归属地区编码；出库按客户归属地区匹配仓库时使用，其他维度为空。 */
        String regionCode) {}
