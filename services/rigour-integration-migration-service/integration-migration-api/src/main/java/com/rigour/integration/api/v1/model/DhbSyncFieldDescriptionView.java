package com.rigour.integration.api.v1.model;

/** 同步中心字段说明；给Portal展示，不作为可配置映射规则。 */
public record DhbSyncFieldDescriptionView(
        /** 页面分组：OVERVIEW、MAPPING、RUN、LOG、EXCEPTION、RECONCILIATION。 */ String groupCode,
        /** 字段编码，和对应View字段保持一致。 */ String fieldCode,
        /** 中文字段名。 */ String fieldName,
        /** 字段含义和使用边界。 */ String description,
        /** 示例值。 */ String example,
        /** 是否敏感字段；敏感字段不得在日志中明文输出。 */ boolean sensitive) {
}
