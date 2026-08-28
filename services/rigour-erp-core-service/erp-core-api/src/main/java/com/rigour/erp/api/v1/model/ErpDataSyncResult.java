package com.rigour.erp.api.v1.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** ERP 完成 Integration 查询和本地幂等落库后的统一同步结果。 */
public record ErpDataSyncResult(
        /** ERP 本地同步批次 UUID。 */
        UUID runId,
        /** 本次同步的对象类型。 */
        String objectType,
        /** 批次状态，完整成功时为 SUCCEEDED。 */
        String status,
        /** 本次使用的 Integration 订货宝连接器 UUID。 */
        UUID connectorId,
        /** 本批次来源根对象数量；商品同步为 SPU 数，不把 SKU 计为商品。 */
        long fetched,
        /** 本批次首次创建的 ERP 记录数量，商品同步包含 SKU。 */
        long created,
        /** 来源摘要变化并成功更新的 ERP 记录数量，商品同步包含 SKU。 */
        long changed,
        /** 来源摘要未变化、按幂等规则跳过的 ERP 记录数量，商品同步包含 SKU。 */
        long duplicates,
        /** 因缺少必要来源标识或字段而拒绝落库的记录数量。 */
        long rejected,
        /** 已完整落库但尚未找到唯一有效业务字典映射的来源枚举出现次数。 */
        long unmapped,
        /** 本批次使用的字典内容版本；-1表示对应字典不可用或未配置。 */
        Map<String, Long> dictionaryRevisions,
        /** 来源对象明细数量，例如 PRODUCT_SPU、PRODUCT_SKU、SPECIFICATION_VALUE。 */
        Map<String, Long> sourceDetails,
        /** 本次从 Integration 读取的页数或库存批次数。 */
        int pages,
        /** ERP 完成本批次的时间。 */
        Instant completedAt) {
    public ErpDataSyncResult {
        dictionaryRevisions = dictionaryRevisions == null ? Map.of() : Map.copyOf(dictionaryRevisions);
        sourceDetails = sourceDetails == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(sourceDetails));
    }
}
