package com.rigour.settings.api.v1.model;

import java.util.List;

/**
 * 批量补齐字典项命令。
 *
 * @param moduleCode 业务模块编码
 * @param dictCode 字典编码
 * @param values 本批次白名单字段观察到的来源值
 */
public record DictSyncCommand(String moduleCode, String dictCode, List<DictSourceValue> values) {
    public DictSyncCommand {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
