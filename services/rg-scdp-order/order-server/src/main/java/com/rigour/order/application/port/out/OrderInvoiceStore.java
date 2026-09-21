package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.OrderInvoiceModels.OrderInvoiceListItemView;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPage;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 开票登记仓储端口；一单一票，按内部订单ID唯一，不参与订货宝同步。 */
public interface OrderInvoiceStore {
    Optional<OrderInvoiceRow> findByOrderNo(String tenantId, String orderNo);

    Optional<OrderInvoiceRow> findByOrderId(String tenantId, long salesOrderId);

    Optional<OrderInvoiceRow> findById(String tenantId, long id);

    /** 客户的开票资料列表；最近使用优先，用于申请时下拉选择与回显。 */
    List<OrderInvoiceProfileRow> profilesByCustomer(String tenantId, long customerId);

    /** 保存/复用客户开票资料：按 抬头+税号+票种 去重，命中则更新资料并刷新 last_used_at。 */
    OrderInvoiceProfileRow saveProfile(String tenantId, OrderInvoiceProfileRow row, String actorId);

    /** 列表批量回填：只返回存在发票行的订单状态，调用方按未申请处理缺省。 */
    Map<Long, String> statusesByOrderIds(String tenantId, Collection<Long> orderIds);

    /** 发票管理分页；带出订单客户名，不触碰订货宝数据。 */
    OrderRegisterPage<OrderInvoiceListItemView> page(
            String tenantId, int begin, int step, InvoicePageCriteria criteria);

    /** 当前筛选（不含状态本身）下各状态数量，用于页签徽标。 */
    Map<String, Long> statusCounts(String tenantId, InvoicePageCriteria criteria);

    /** 新增或更新发票行；id 为空时插入，否则按 id 更新可变字段。 */
    OrderInvoiceRow save(String tenantId, OrderInvoiceRow row, String actorId);

    record InvoicePageCriteria(
            String status, String orderNo, String customerName, Instant appliedFrom, Instant appliedTo) {
    }

    /** 客户开票资料；只用于申请表单的选择与回显。 */
    record OrderInvoiceProfileRow(
            Long id,
            long customerId,
            String customerCode,
            String titleType,
            String title,
            String taxNo,
            String invoiceType,
            String bankName,
            String bankAccount,
            String registerAddress,
            String registerPhone,
            String email,
            String remark,
            Instant lastUsedAt) {
    }

    record OrderInvoiceRow(
            Long id,
            long salesOrderId,
            String orderNo,
            Long customerId,
            String customerCode,
            String status,
            String titleType,
            String title,
            String taxNo,
            String invoiceType,
            String bankName,
            String bankAccount,
            String registerAddress,
            String registerPhone,
            String email,
            String remark,
            BigDecimal amount,
            List<String> attachmentKeys,
            String invoiceNo,
            String appliedBy,
            Instant appliedAt,
            String invoicedBy,
            Instant invoicedAt,
            String updatedBy,
            Instant updatedAt) {
    }
}
