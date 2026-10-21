package com.rigour.erp.api.v1.model;

import java.time.LocalDate;

/** 营销费用的补充事实，不参与自动摊销。 */
public record ErpCostMarketingDetails(
        String channelName, LocalDate serviceFrom, LocalDate serviceTo) {}
