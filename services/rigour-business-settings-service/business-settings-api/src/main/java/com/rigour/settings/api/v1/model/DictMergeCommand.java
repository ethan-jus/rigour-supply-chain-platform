package com.rigour.settings.api.v1.model;

/** 用户确认的合并命令；双版本防止预览后数据变化。 */
public record DictMergeCommand(Long targetItemId, int sourceRevision, int targetRevision, String reason) { }
