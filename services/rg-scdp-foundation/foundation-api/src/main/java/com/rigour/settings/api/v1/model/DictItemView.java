package com.rigour.settings.api.v1.model;

/** 租户有效字典项；启停控制新选择，标准项引用仅用于保留历史编码的解释。 */
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
        Boolean enabled,
        String canonicalDictionaryCode,
        String canonicalItemCode) {
    public DictItemView {
        enabled = enabled == null ? true : enabled;
    }

    public DictItemView(
            Long id,
            String dictionaryCode,
            int dictionaryItemLevel,
            String parentDictionaryItemCode,
            String dictionaryItemCode,
            String dictionaryItemName,
            String remark,
            int ordinal,
            int revision) {
        this(
                id,
                dictionaryCode,
                dictionaryItemLevel,
                parentDictionaryItemCode,
                dictionaryItemCode,
                dictionaryItemName,
                remark,
                ordinal,
                revision,
                true,
                null,
                null);
    }

    public DictItemView(
            Long id,
            String dictionaryCode,
            int dictionaryItemLevel,
            String parentDictionaryItemCode,
            String dictionaryItemCode,
            String dictionaryItemName,
            String remark,
            int ordinal,
            int revision,
            Boolean enabled) {
        this(
                id,
                dictionaryCode,
                dictionaryItemLevel,
                parentDictionaryItemCode,
                dictionaryItemCode,
                dictionaryItemName,
                remark,
                ordinal,
                revision,
                enabled,
                null,
                null);
    }

    public DictItemView(
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
        this(
                id,
                dictionaryCode,
                dictionaryItemLevel,
                parentDictionaryItemCode,
                dictionaryItemCode,
                dictionaryItemName,
                remark,
                ordinal,
                revision,
                true,
                canonicalDictionaryCode,
                canonicalItemCode);
    }

    public boolean alias() {
        return canonicalItemCode != null;
    }
}
