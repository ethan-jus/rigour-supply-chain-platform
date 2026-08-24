package com.rigour.settings.api.v1.model;

/** 新增或修改字典项；层级由服务端根据父级条目编码计算。 */
public record DictItemCommand(
        String dictionaryCode,
        String parentDictionaryItemCode,
        String dictionaryItemCode,
        String dictionaryItemName,
        String remark,
        int ordinal,
        int revision) {
}
