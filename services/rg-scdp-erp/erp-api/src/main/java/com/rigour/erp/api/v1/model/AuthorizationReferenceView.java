package com.rigour.erp.api.v1.model;

/** 授权配置专用的最小主数据标识，不包含客户或仓库联系人资料。 */
public record AuthorizationReferenceView(
        String key, String name, String parentKey, String status, long revision) {}
