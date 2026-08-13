package com.rigour.merchant.api.v1.model;

/** getDealersList 的来源标识与代码；规范名称/状态仍由 CRM 字段提供。 */
public record CustomerSourceView(
        String clientGuid, String typeId, String areaId, String areaGuid,
        String statusCode, String clearingFormCode) {
}
