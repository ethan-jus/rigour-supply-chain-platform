package com.rigour.erp.api.v1.model;

/** 确认使用当前版本，确认后采购内容不可再改。 */
public record InternalProcurementConfirmationCommand(Integer revision) {}
