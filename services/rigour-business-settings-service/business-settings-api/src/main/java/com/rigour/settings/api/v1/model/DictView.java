package com.rigour.settings.api.v1.model;

/** 字典定义；调用方按 dictionaryCode 直接读取，不再关心旧作用域和模块继承。 */
public record DictView(
        Long id,
        String dictionaryCode,
        String dictionaryName,
        String dictionaryType,
        String remark,
        int revision) {
}
