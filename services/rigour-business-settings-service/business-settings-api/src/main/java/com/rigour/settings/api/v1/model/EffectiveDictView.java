package com.rigour.settings.api.v1.model;

import java.util.List;

/**
 * 当前调用人最终生效的整本字典；服务端已经完成 TENANT、MODULE、SYSTEM 选择。
 *
 * @param dictionary 最终命中的字典定义
 * @param items 最终字典中的有效条目，包含父子关系和层级
 */
public record EffectiveDictView(DictView dictionary, List<DictItemView> items) {
    public EffectiveDictView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
