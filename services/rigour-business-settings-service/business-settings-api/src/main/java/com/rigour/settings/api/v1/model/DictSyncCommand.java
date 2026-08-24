package com.rigour.settings.api.v1.model;

import java.util.List;

/**
 * 批量补齐字典项命令。
 *
 * @param dictionaryCode 字典编码
 * @param values 本批次白名单字段观察到的来源值
 */
public record DictSyncCommand(String dictionaryCode, List<DictSourceValue> values) {
    public DictSyncCommand {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
