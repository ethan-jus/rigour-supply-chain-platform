package com.rigour.order.infrastructure.persistence.mapper;

import java.util.List;

/** 代码内固定的 BI 投影契约，不能由请求覆盖表名或列名。 */
public record OrderAnalyticsSourceDataset(
        String table,
        List<String> columns,
        boolean binaryTenant,
        boolean binaryKey,
        String versionExpression) {}
