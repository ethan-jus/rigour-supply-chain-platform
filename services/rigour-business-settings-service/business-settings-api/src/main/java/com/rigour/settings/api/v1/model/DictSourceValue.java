package com.rigour.settings.api.v1.model;

/**
 * 外部来源字典值。
 *
 * @param value 来源系统原始值，按大小写精确匹配
 * @param name 来源系统明确返回的显示名称；未返回时为空
 */
public record DictSourceValue(String value, String name) {
}
