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
        int revision,
        String canonicalDictionaryCode,
        String canonicalItemCode) {
    /** 保持既有调用方的标准项构造方式。 */
    public DictItemView(Long id, String dictionaryCode, int dictionaryItemLevel,
                        String parentDictionaryItemCode, String dictionaryItemCode, String dictionaryItemName,
                        String remark, int ordinal, int revision) {
        this(id, dictionaryCode, dictionaryItemLevel, parentDictionaryItemCode, dictionaryItemCode,
                dictionaryItemName, remark, ordinal, revision, null, null);
    }
    /** 兼容项只解析历史值，不再作为新业务选项。 */
    public boolean alias() { return canonicalItemCode != null; }
}
