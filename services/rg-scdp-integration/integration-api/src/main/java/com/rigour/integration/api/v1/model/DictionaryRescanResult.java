package com.rigour.integration.api.v1.model;

/** 历史原始行扫描结果；限制触及时显式报告未完整覆盖。 */
public record DictionaryRescanResult(int batches, int rows, int observedValues, long pendingValues, boolean truncated) { }
