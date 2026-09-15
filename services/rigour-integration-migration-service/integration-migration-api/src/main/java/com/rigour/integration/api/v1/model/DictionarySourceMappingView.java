package com.rigour.integration.api.v1.model;

import java.time.LocalDateTime;

/** 当前租户外部枚举观察值及其标准映射，原值始终保留。 */
public record DictionarySourceMappingView(Long id, String sourceSystem, String sourceScope, String dictionaryCode,
        String sourceField, String sourceValue, String targetDictionaryCode, String targetItemCode,
        String mappingStatus, boolean manualOverride, int revision, LocalDateTime firstSeen, LocalDateTime lastSeen) { }
