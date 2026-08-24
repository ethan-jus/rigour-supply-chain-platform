package com.rigour.integration.api.v1.model;

import java.util.Map;
import java.util.UUID;

/** 订货宝统一同步单个步骤结果。 */
public record DhbSyncOrchestrationStepView(
        /** ERP、CRM 或 ORDER。 */
        String domain,
        /** 领域内对象类型，例如 PRODUCT_SPU、CUSTOMER、ORDER_DOMAIN。 */
        String objectType,
        /** SUCCEEDED、SUCCEEDED_WITH_WARNINGS、SKIPPED 或 FAILED。 */
        String status,
        /** 领域服务本地同步运行 ID；失败发生在调用前时为空。 */
        UUID runId,
        /** 来源拉取并完成本地统计的记录数。 */
        long fetched,
        /** 新增或变更数量。 */
        long changed,
        /** 已识别但尚未映射到有效字典的来源值次数。 */
        long unmapped,
        /** 本步骤用到的字典版本。 */
        Map<String, Long> dictionaryRevisions,
        /** 失败或跳过原因；成功时为空。 */
        String message) {
    public DhbSyncOrchestrationStepView {
        dictionaryRevisions = dictionaryRevisions == null ? Map.of() : Map.copyOf(dictionaryRevisions);
    }
}
