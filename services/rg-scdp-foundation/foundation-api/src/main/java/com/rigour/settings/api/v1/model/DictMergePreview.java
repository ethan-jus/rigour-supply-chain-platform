package com.rigour.settings.api.v1.model;

import java.util.List;

/** 合并预览；引用数仅来自 Settings，业务旧编码通过兼容关系继续可读。 */
public record DictMergePreview(DictItemView source, DictItemView target, long childReferences,
                               long aliasReferences, List<String> blockers) { }
