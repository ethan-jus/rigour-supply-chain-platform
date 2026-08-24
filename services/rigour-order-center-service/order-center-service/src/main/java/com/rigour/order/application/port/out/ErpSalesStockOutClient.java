package com.rigour.order.application.port.out;

import com.rigour.shared.context.CallerIdentity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Order 调用 ERP 销售出库的端口。
 *
 * <p>Order 不直接写 ERP 库表，只通过 ERP 版本化接口生成销售出库单并扣减库存。</p>
 */
public interface ErpSalesStockOutClient {
    SalesStockOutResult confirmSalesStockOut(CallerIdentity caller, SalesStockOutRequest request);

    /** 销售出库请求；来源是 Order 已提交销售订单的当前快照。 */
    record SalesStockOutRequest(
            Long salesOrderId,
            String salesOrderNo,
            Long warehouseId,
            Long customerId,
            String customerNameSnapshot,
            Instant stockOutTime,
            List<SalesStockOutLine> lines,
            String remark) {
        public SalesStockOutRequest {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** 销售出库明细；保留销售订单明细 ID，便于后续从库存流水反查业务来源。 */
    record SalesStockOutLine(
            Long salesOrderLineId,
            Long productId,
            Long productVariantId,
            String productCodeSnapshot,
            String variantCodeSnapshot,
            String productNameSnapshot,
            String unitCode,
            BigDecimal quantity,
            String remark) {
    }

    /** ERP 成功生成的销售出库单摘要。 */
    record SalesStockOutResult(Long stockOutOrderId, String stockOutNo, Instant stockOutTime) {
    }
}
