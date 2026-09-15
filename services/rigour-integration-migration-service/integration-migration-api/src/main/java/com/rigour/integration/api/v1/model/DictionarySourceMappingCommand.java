package com.rigour.integration.api.v1.model;

/** 设置当前租户来源字段映射；版本必须来自最新列表。 */
public record DictionarySourceMappingCommand(String targetDictionaryCode, String targetItemCode, int revision) { }
