package com.rigour.analytics.application.port.out;

import com.rigour.shared.context.CallerIdentity;
import java.util.Map;

/** 复核使用 Settings 只读 API 的真实 PRODUCT_UNIT 映射；不跨库查询或补写字典。 */
public interface BiReconciliationUnitDictionary {
    Map<String, String> productUnits(CallerIdentity actor);
}
