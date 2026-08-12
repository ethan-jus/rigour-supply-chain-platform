package com.rigour.erp.api.v1.model;

import java.time.Instant;
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
        /** 本批次交给 ERP 导入流程并完成统计的记录总数，商品同步包含 SKU。 */
        long fetched,
        /** 本批次首次创建的 ERP 记录数量，商品同步包含 SKU。 */
        long created,
        /** 来源摘要变化并成功更新的 ERP 记录数量，商品同步包含 SKU。 */
        long changed,
        /** 来源摘要未变化、按幂等规则跳过的 ERP 记录数量，商品同步包含 SKU。 */
        long duplicates,
        /** 因缺少必要来源标识或字段而拒绝落库的记录数量。 */
        long rejected,
        /** 本次从 Integration 读取的页数或库存批次数。 */
        int pages,
        /** ERP 完成本批次的时间。 */
        Instant completedAt) {
}
