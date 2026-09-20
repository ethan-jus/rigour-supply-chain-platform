package com.rigour.erp.api.v1.model;

/** 客户类型等级价导入结果；total 为提交明细数，created/updated 为实际新增与更新数。 */
public record CustomerTypePriceImportResult(int total, int created, int updated) {
}
