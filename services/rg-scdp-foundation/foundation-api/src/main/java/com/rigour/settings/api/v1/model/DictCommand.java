package com.rigour.settings.api.v1.model;

/** 新增或修改字典；编码创建后不可修改。 */
public record DictCommand(
        String dictionaryCode,
        String dictionaryName,
        String dictionaryType,
        String remark,
        int revision) {
}
