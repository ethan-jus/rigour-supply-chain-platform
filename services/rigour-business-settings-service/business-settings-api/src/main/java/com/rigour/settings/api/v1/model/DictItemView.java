package com.rigour.settings.api.v1.model;

/** 字典项；父子关系通过字典项编码表达，便于业务侧维护和导入。 */
public record DictItemView(
        Long id,
        String dictionaryCode,
        int dictionaryItemLevel,
        String parentDictionaryItemCode,
        String dictionaryItemCode,
        String dictionaryItemName,
        String remark,
        int ordinal,
        int revision) {
}
