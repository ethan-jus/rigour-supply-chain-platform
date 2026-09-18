package com.rigour.erp.application.port.out;

import java.util.Set;

/** 新商品与新库存业务输入的有效单位；来源历史事实保留原始编码。 */
public interface ErpUnitDictionary {
    Set<String> validUnits(String tenant);
}
