package com.rigour.settings.api.v1.model;

import java.util.List;

/**
 * 当前租户最终生效的整本字典；服务端已合并租户覆盖与全局只读基线。
 *
 * @param dictionary 最终命中的字典定义
 * @param items 包含停用项以解析历史名称；新业务必须按 enabled 及祖先状态筛选
 */
public record EffectiveDictView(DictView dictionary, List<DictItemView> items) {
    public EffectiveDictView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
