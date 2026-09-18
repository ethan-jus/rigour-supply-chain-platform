package com.rigour.settings.client;

import com.rigour.settings.api.v1.model.*;

import java.util.*;

/** 业务消费侧区分可选择条目和历史显示条目；停用祖先、断链和循环均不可用于新业务。 */
public final class DictionaryItems {
    private DictionaryItems() {}

    public static Set<String> activeCodes(EffectiveDictView view) {
        if (view == null || view.dictionary() == null) throw new IllegalArgumentException("字典响应无效");
        var items = new HashMap<String, DictItemView>();
        for (var item : view.items())
            if (view.dictionary().dictionaryCode().equals(item.dictionaryCode())) {
                if (item.dictionaryItemCode() == null
                        || items.putIfAbsent(item.dictionaryItemCode(), item) != null)
                    throw new IllegalArgumentException("字典编码重复或缺失");
            }
        var result = new LinkedHashSet<String>();
        for (var item : items.values()) {
            var seen = new HashSet<String>();
            var current = item;
            boolean enabled = true;
            while (current != null) {
                if (!Boolean.TRUE.equals(current.enabled())
                        || current.alias()
                        || !seen.add(current.dictionaryItemCode())) {
                    enabled = false;
                    break;
                }
                var parent = current.parentDictionaryItemCode();
                if (parent == null || parent.isBlank()) break;
                current = items.get(parent);
                if (current == null) {
                    enabled = false;
                    break;
                }
            }
            if (enabled) result.add(item.dictionaryItemCode());
        }
        return Set.copyOf(result);
    }
}
