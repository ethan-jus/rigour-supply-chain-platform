package com.rigour.order.application.port.out;

import java.util.Set;

/** 历史单位输入仍须属于当前租户合法商品单位字典；不自动补建条目。 */
public interface OrderRepairUnitDictionary {
    Set<String> validUnits(String tenantId);
}
