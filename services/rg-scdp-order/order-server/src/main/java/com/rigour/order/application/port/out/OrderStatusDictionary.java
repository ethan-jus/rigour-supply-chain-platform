package com.rigour.order.application.port.out;
import java.util.Set;
/** 只读取有效字典，不自动补建状态或核查原因。 */
public interface OrderStatusDictionary {
    java.util.Map<String, String> labels(String tenantId, String dictionaryCode);
    Set<String> activeCodes(String tenantId, String dictionaryCode);
}
