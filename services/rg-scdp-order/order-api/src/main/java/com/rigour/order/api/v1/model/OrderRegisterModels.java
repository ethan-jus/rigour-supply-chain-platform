package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 订单登记读取契约：订单、明细、收款三个列表共用统一的归属筛选和金额口径。
 * 时间字段为业务时间；同步审计与来源审计分离，缺少历史事件时不把未知余额填成 0。
 */
public final class OrderRegisterModels {
    private OrderRegisterModels() {
    }

    /** 登记列表统一分页包装；totals 来自当前筛选命中的全部记录，不是当前页。 */
    public record OrderRegisterPage<T>(
            long total,
            int begin,
            int step,
            List<T> items,
            Map<String, BigDecimal> totals,
            HistoryCoverage coverage) {
        public OrderRegisterPage {
            items = items == null ? List.of() : List.copyOf(items);
            totals = totals == null ? Map.of() : Map.copyOf(totals);
        }
    }

    /** 历史覆盖说明；historyComplete=false 时不得把结果解读为“确认没有历史欠款/回款”。 */
    public record HistoryCoverage(
            boolean historyComplete,
            Instant coverageFrom,
            String message,
            long missingCount) {
        public static HistoryCoverage covered() {
            return new HistoryCoverage(true, null, null, 0);
        }

        public static HistoryCoverage incomplete(Instant coverageFrom, String message, long missingCount) {
            return new HistoryCoverage(false, coverageFrom, message, missingCount);
        }
    }

    /** 订单列表行；归属地区/业务员/部门为下单时快照或订单自身冻结值。 */
    public record OrderRegisterOrderView(
            Long id,
            String orderNo,
            String legacyOrderNo,
            String sourceSystemCode,
            String sourceOrderNo,
            /** 订货宝关联单号：来源为订货宝时取来源单号，其余来源按订单号映射解析。 */
            String dhbOrderNo,
            String orderNumberState,
            Long customerId,
            String customerCode,
            String customerName,
            String regionCode,
            String regionName,
            String ownerEmployeeCode,
            String ownerEmployeeName,
            Long departmentId,
            String departmentName,
            String orderStatusCode,
            String paymentStatusCode,
            /** 数据完善状态；草稿且 NEEDS_REVIEW 的历史来源单在列表展示为“待完善”。 */
            String dataQualityStatusCode,
            String invoiceStatusCode,
            String invoiceStatusName,
            BigDecimal originalAmount,
            BigDecimal payableAmount,
            BigDecimal paidAmount,
            BigDecimal unpaidAmount,
            BigDecimal checkedAmount,
            Instant orderDate,
            Instant shipmentTime,
            String createdBy,
            Instant createdTime,
            String updatedBy,
            Instant updatedTime,
            String syncedBy,
            Instant syncedAt,
            Integer revision) {
    }

    /** 订单明细行；整单字段来自订单头，金额按明细行聚合，不跨单位求和。 */
    public record OrderRegisterLineView(
            Long id,
            Long orderId,
            Integer lineNo,
            String sourceLineId,
            Long productId,
            Long productVariantId,
            String productCode,
            String skuCode,
            String productName,
            String specification,
            String unitCode,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineAmount,
            /** 分摊到本明细的回款金额：订单实收按「明细金额 / 订单应收」比例分摊，部分回款也按比例。 */
            BigDecimal receivedAmount,
            String orderNo,
            /** 来源系统单号；页面与订单列表口径一致，便于按来源核对。 */
            String sourceOrderNo,
            /** 订货宝关联单号（订单级）。 */
            String dhbOrderNo,
            Long customerId,
            String customerCode,
            String customerName,
            String regionCode,
            String regionName,
            String ownerEmployeeCode,
            String ownerEmployeeName,
            Long departmentId,
            String departmentName,
            String orderStatusCode,
            Instant orderDate,
            Integer revision,
            String createdBy,
            Instant createdTime,
            String updatedBy,
            Instant updatedTime,
            String syncedBy,
            Instant syncedAt) {
    }

    /** 收款列表行；一行是一笔关联订单的收款记录，订单金额只做关联参考。 */
    public record OrderRegisterPaymentView(
            Long id,
            String paymentNo,
            String sourceRecordId,
            Long orderId,
            String orderNo,
            /** 订货宝关联单号（订单级）。 */
            String dhbOrderNo,
            Long customerId,
            String customerCode,
            String customerName,
            String regionCode,
            String regionName,
            String ownerEmployeeCode,
            String ownerEmployeeName,
            Long departmentId,
            String departmentName,
            Instant orderDate,
            BigDecimal orderAmount,
            BigDecimal paidAmount,
            String paymentStatusCode,
            Instant paymentTime,
            String transactionNo,
            List<String> attachments,
            List<FundDocumentAttachmentView> attachmentViews,
            String createdBy,
            Instant createdTime,
            String updatedBy,
            Instant updatedTime,
            String syncedBy,
            Instant syncedAt,
            String checkedBy,
            Instant checkedAt,
            Integer revision) {
        public OrderRegisterPaymentView {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
            attachmentViews = attachmentViews == null ? List.of() : List.copyOf(attachmentViews);
        }
    }

    /** 期间统计合计；五项口径与分组行一致。 */
    public record PeriodTotals(
            BigDecimal periodOrderAmount,
            BigDecimal periodReceivedAmount,
            BigDecimal periodRefundAmount,
            BigDecimal periodNetReceivedAmount,
            BigDecimal endingUnpaidAmount) {
        public PeriodTotals {
            periodOrderAmount = zeroIfNull(periodOrderAmount);
            periodReceivedAmount = zeroIfNull(periodReceivedAmount);
            periodRefundAmount = zeroIfNull(periodRefundAmount);
            periodNetReceivedAmount = zeroIfNull(periodNetReceivedAmount);
            endingUnpaidAmount = zeroIfNull(endingUnpaidAmount);
        }
    }

    /** 期间统计分组行；key 为分组标识（地区编码/客户ID/员工编码），label 为可读名称。 */
    public record PeriodRow(
            String key,
            String label,
            BigDecimal periodOrderAmount,
            BigDecimal periodReceivedAmount,
            BigDecimal periodRefundAmount,
            BigDecimal periodNetReceivedAmount,
            BigDecimal endingUnpaidAmount) {
        public PeriodRow {
            periodOrderAmount = zeroIfNull(periodOrderAmount);
            periodReceivedAmount = zeroIfNull(periodReceivedAmount);
            periodRefundAmount = zeroIfNull(periodRefundAmount);
            periodNetReceivedAmount = zeroIfNull(periodNetReceivedAmount);
            endingUnpaidAmount = zeroIfNull(endingUnpaidAmount);
        }
    }

    /** 期间经营统计；期末未回款使用结束日当天截至时点，不被期间开始日期排除。 */
    public record PeriodStatisticsView(
            LocalDate dateFrom,
            LocalDate dateTo,
            String groupBy,
            PeriodTotals totals,
            List<PeriodRow> rows,
            HistoryCoverage coverage) {
        public PeriodStatisticsView {
            rows = rows == null ? List.of() : List.copyOf(rows);
            totals =
                    totals == null
                            ? new PeriodTotals(null, null, null, null, null)
                            : totals;
        }
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 截至日未回款行；historyComplete=false 表示该订单历史事件不完整。 */
    public record ReceivablesView(
            Long orderId,
            String orderNo,
            String sourceSystemCode,
            Long customerId,
            String customerCode,
            String customerName,
            String regionCode,
            String regionName,
            String ownerEmployeeCode,
            String ownerEmployeeName,
            String departmentName,
            Instant orderDate,
            BigDecimal receivableAmount,
            BigDecimal netReceivedAmount,
            BigDecimal unpaidAmount,
            BigDecimal overpaidAmount,
            boolean historyComplete,
            Instant coverageFrom) {
    }

    /** 可靠订单号映射命令；旧内部编号必须精确命中一张订单。 */
    public record OrderNumberMappingCommand(
            String connectorId,
            String sourceObjectType,
            String sourceObjectId,
            String internalOrderNo,
            String dhbOrderNo,
            String evidence,
            Integer revision) {
    }

    /** 订单号映射结果；失败时 state 与 message 说明保留待处理的原因。 */
    public record OrderNumberMappingResult(
            Long mappingId,
            Long orderId,
            String previousOrderNo,
            String orderNo,
            String state,
            String message) {
    }

    /** 订单号映射记录，用于核对旧内部编号到订货宝单号的迁移过程。 */
    public record NumberMappingView(
            Long id,
            String connectorId,
            String sourceSystemCode,
            String sourceObjectType,
            String sourceObjectId,
            String internalOrderNo,
            String dhbOrderNo,
            String state,
            String evidence,
            String createdBy,
            Instant createdTime,
            String updatedBy,
            Instant updatedTime) {
    }

    /** 财务核对命令；交易单号用于凭证验重与对账。 */
    /** 回款核对：交易单号 + 页面版本，版本用于状态流转的乐观锁。 */
    public record PaymentCheckCommand(String transactionNo, Integer revision) {
    }
}
