package com.rigour.order.application.port.out;

import com.rigour.shared.context.CallerIdentity;
import java.util.List;
import java.util.Set;

/** Order 到 CRM 客户归属地区主档的展示名查询端口；订单自身仍只持久化归属地区编码。 */
public interface CrmCustomerAreaDisplayClient {
    List<CustomerAreaDisplay> resolve(CallerIdentity caller, Set<String> areaCodes);

    record CustomerAreaDisplay(String areaCode, String areaName) {
    }
}
