package com.rigour.integration.application.port.out;

import com.rigour.shared.context.CallerIdentity;

/** Integration 到 CRM 客户归属的只读查询端口；只在来源单缺少地区时做兜底。 */
public interface CrmCustomerAttributionClient {
    /** 返回客户当前归属地区编码；客户不存在、归属不完整或查询失败时返回 null。 */
    String regionCode(CallerIdentity caller, long customerId);
}
