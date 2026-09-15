package com.rigour.settings.api.v1.model;

/** 原值解析到的标准字典及编码；跨字典结果用于纠正历史维度混用。 */
public record DictValueResolution(String dictionaryCode, String itemCode) { }
