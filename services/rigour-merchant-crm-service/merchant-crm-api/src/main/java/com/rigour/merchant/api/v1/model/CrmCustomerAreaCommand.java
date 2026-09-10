package com.rigour.merchant.api.v1.model;

/** CRM 客户区域/城市维护命令。 */
public record CrmCustomerAreaCommand(
        String areaName,
        String parentAreaCode,
        String status,
        Integer revision) {
}
