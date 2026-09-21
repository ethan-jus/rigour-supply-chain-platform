package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 订单开票登记契约；一单一票，状态在发票行内流转，不参与订货宝同步。 */
public final class OrderInvoiceModels {
    private OrderInvoiceModels() {
    }

    /** 发票附件视图；objectKey 为私有 COS 对象键，url 为短时访问地址。 */
    public record InvoiceAttachmentView(String objectKey, String fileName, String url) {
    }

    /** 发票登记视图；未申请或已撤回时不返回记录（接口返回空数据）。 */
    public record OrderInvoiceView(
            Long id,
            Long orderId,
            String orderNo,
            String statusCode,
            String statusName,
            String titleType,
            String titleTypeName,
            String title,
            String taxNo,
            String invoiceType,
            String invoiceTypeName,
            String bankName,
            String bankAccount,
            String registerAddress,
            String registerPhone,
            String email,
            String remark,
            BigDecimal amount,
            List<InvoiceAttachmentView> attachments,
            String invoiceNo,
            String appliedBy,
            Instant appliedAt,
            String invoicedBy,
            Instant invoicedAt,
            String updatedBy,
            Instant updatedAt) {
    }

    /** 申请/重新申请开票命令；待开票时修改资料，已撤回时重新申请。 */
    public record OrderInvoiceApplyCommand(
            String orderNo,
            String titleType,
            String title,
            String taxNo,
            String invoiceType,
            String bankName,
            String bankAccount,
            String registerAddress,
            String registerPhone,
            String email,
            String remark) {
    }

    /** 完成开票命令；发票号码与开票日期必填。 */
    public record OrderInvoiceCompleteCommand(String invoiceNo, Instant invoicedAt) {
    }

    /** 发票管理列表行；带出订单客户名，便于财务按客户核对。 */
    public record OrderInvoiceListItemView(
            Long id,
            Long orderId,
            String orderNo,
            String customerName,
            String title,
            String invoiceTypeName,
            BigDecimal amount,
            String statusCode,
            String statusName,
            String appliedBy,
            Instant appliedAt,
            String invoiceNo,
            Instant invoicedAt,
            Integer attachmentCount) {
    }

    /** 发票管理分页；statusCounts 是当前其他筛选条件下各状态数量（不含状态筛选本身）。 */
    public record OrderInvoicePageView(
            OrderRegisterModels.OrderRegisterPage<OrderInvoiceListItemView> page,
            Map<String, Long> statusCounts) {
    }

    /** 客户开票资料；一个客户可有多套抬头资料，申请时下拉选择回显。 */
    public record OrderInvoiceProfileView(
            Long id,
            String titleType,
            String titleTypeName,
            String title,
            String taxNo,
            String invoiceType,
            String invoiceTypeName,
            String bankName,
            String bankAccount,
            String registerAddress,
            String registerPhone,
            String email,
            String remark,
            Instant lastUsedAt) {
    }
}
