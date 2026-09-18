package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.SalesOrderLineView;
import com.rigour.order.api.v1.model.SalesOrderProductRepair.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 修复持久化端口；应用必须原子校验全聚合指纹、预览所有人、有效期和逐行版本。 */
public interface OrderProductRepairStore {
    record Line(SalesOrderLineView value, Integer revision) { }
    record Snapshot(Long id, String orderNo, String sourceOrderNo, String sourceSystemCode,
                    String orderStatusCode, String ownerSalesUserId, Integer revision,
                    BigDecimal totalQuantity, String fingerprint, List<Line> lines) { }
    record Saved(Preview preview, String fingerprint, Applied applied) { }

    Optional<Snapshot> snapshot(String tenantId, Long orderId);
    void save(String tenantId, Saved saved);
    Optional<Saved> preview(String tenantId, Long orderId, String previewId);
    List<Applied> recentRepairs(String tenantId, Long orderId);
    Optional<Applied> currentRepair(String tenantId, Long orderId, Long lineId, Integer lineRevision);
    List<Evidence> evidence(String tenantId, String ownerUserId, long afterLineId, int limit);
    Applied apply(String tenantId, Long orderId, String previewId, String actorId, Instant now);
}
