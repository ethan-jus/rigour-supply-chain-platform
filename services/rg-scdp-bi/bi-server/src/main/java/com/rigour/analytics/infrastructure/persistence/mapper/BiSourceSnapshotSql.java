package com.rigour.analytics.infrastructure.persistence.mapper;

import com.rigour.analytics.infrastructure.persistence.scope.BiSourceDatasets;

import java.util.*;
import java.util.stream.Collectors;

/** 固定数据集白名单决定标识符；每个列值经 MyBatis 参数绑定，不接收外部 SQL。 */
public final class BiSourceSnapshotSql {
    private static BiSourceDatasets.Dataset dataset(Map<String, Object> parameters) {
        return BiSourceDatasets.ALL.stream()
                .filter(d -> d.code().equals(parameters.get("dataset")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知 BI 源数据集"));
    }

    public static String clear(Map<String, Object> parameters) {
        var d = dataset(parameters);
        return "DELETE FROM "
                + d.table()
                + " WHERE tenant_id="
                + (d.binaryTenant() ? "UUID_TO_BIN(#{tenant})" : "#{tenant}");
    }

    public static String insert(Map<String, Object> parameters) {
        var d = dataset(parameters);
        var rows = (List<?>) parameters.get("rows");
        if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("源投影批次不能为空");
        String columns =
                d.columns().stream()
                        .map(BiSourceDatasets.Column::name)
                        .collect(Collectors.joining(","));
        String values =
                d.columns().stream()
                        .map(c -> "#{row." + c.name() + "}")
                        .collect(Collectors.joining(","));
        return "<script>INSERT INTO "
                + d.table()
                + " ("
                + columns
                + ") VALUES "
                + "<foreach collection='rows' item='row' separator=','>("
                + values
                + ")</foreach></script>";
    }
}
