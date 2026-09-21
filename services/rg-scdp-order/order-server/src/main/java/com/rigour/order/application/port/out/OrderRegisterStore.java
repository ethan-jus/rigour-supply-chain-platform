package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.OrderRegisterModels.HistoryCoverage;
import com.rigour.order.api.v1.model.OrderRegisterModels.NumberMappingView;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderNumberMappingResult;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterLineView;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterOrderView;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPage;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPaymentView;
import com.rigour.order.api.v1.model.OrderRegisterModels.PeriodStatisticsView;
import com.rigour.order.api.v1.model.OrderRegisterModels.ReceivablesView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 订单登记读取与订单号映射仓储端口；读取复用既有业务表，不得新建平行业务表。 */
public interface OrderRegisterStore {
    OrderRegisterPage<OrderRegisterOrderView> orders(
            String tenantId, int begin, int step, OrderCriteria criteria);

    /** 按订单号精确定位订单；开票登记用它确认订单存在并取金额快照。 */
    Optional<OrderRegisterOrderView> findOrder(String tenantId, String orderNo);

    OrderRegisterPage<OrderRegisterLineView> lines(
            String tenantId, int begin, int step, LineCriteria criteria);

    OrderRegisterPage<OrderRegisterPaymentView> payments(
            String tenantId, int begin, int step, PaymentCriteria criteria);

    /** 财务核对回款：写入交易单号（用于凭证验重与对账）并标记已核对。 */
    OrderRegisterPaymentView checkPayment(
            String tenantId,
            long id,
            String transactionNo,
            int revision,
            String actorId,
            Instant checkedAt);

    PeriodStatisticsView periodStatistics(String tenantId, PeriodCriteria criteria);

    /** 创建人下拉数据源；来自订单真实创建人，去空、去重、稳定排序。 */
    List<String> creators(String tenantId);

    OrderRegisterPage<ReceivablesView> receivables(
            String tenantId, int begin, int step, ReceivablesCriteria criteria);

    OrderRegisterPage<NumberMappingView> numberMappings(
            String tenantId, int begin, int step, NumberMappingCriteria criteria);

    OrderNumberMappingResult applyNumberMapping(
            String tenantId, ApplyNumberMapping command, String actorId);

    record OrderCriteria(
            String orderNo,
            Long customerId,
            String customerName,
            String customerCode,
            String regionCode,
            String ownerEmployeeCode,
            /** 部门筛选范围：命中订单归属快照 snap.department_id，含子部门时是多值。 */
            Set<Long> departmentIds,
            Instant orderDateFrom,
            Instant orderDateTo,
            String orderStatusCode,
            String paymentStatusCode,
            Boolean hasUnpaid,
            String invoiceStatusCode) {
    }

    record LineCriteria(
            String orderNo,
            Long customerId,
            String customerName,
            String customerCode,
            String regionCode,
            String ownerEmployeeCode,
            /** 部门筛选范围：命中订单归属快照 snap.department_id，含子部门时是多值。 */
            Set<Long> departmentIds,
            Instant orderDateFrom,
            Instant orderDateTo,
            String orderStatusCode,
            String productKeyword,
            String productCode,
            /** 按商品ID集合过滤；商品分类筛选先在前端解析成商品集合。 */
            List<Long> productIds) {
    }

    record PaymentCriteria(
            String orderNo,
            Long customerId,
            String customerName,
            String customerCode,
            String regionCode,
            String ownerEmployeeCode,
            /** 部门筛选范围：命中订单归属快照 snap.department_id，含子部门时是多值。 */
            Set<Long> departmentIds,
            Instant orderDateFrom,
            Instant orderDateTo,
            String orderStatusCode,
            String paymentNo,
            String transactionNo,
            String paymentStatusCode,
            Instant paymentTimeFrom,
            Instant paymentTimeTo,
            /** 排序字段：paymentTime / createdTime / syncedAt；空按收款时间倒序。 */
            String sortBy,
            String sortDirection) {
    }

    record PeriodCriteria(
            LocalDate dateFrom,
            LocalDate dateTo,
            String groupBy,
            String regionCode,
            String ownerEmployeeCode,
            /** 部门筛选范围：命中订单归属快照 snap.department_id，含子部门时是多值。 */
            Set<Long> departmentIds,
            Long customerId,
            String customerName,
            String customerCode) {
    }

    record ReceivablesCriteria(
            LocalDate asOfDate,
            boolean hasUnpaid,
            String regionCode,
            String ownerEmployeeCode,
            /** 部门筛选范围：命中订单归属快照 snap.department_id，含子部门时是多值。 */
            Set<Long> departmentIds,
            Long customerId,
            String orderNo) {
    }

    record NumberMappingCriteria(String state, String internalOrderNo, String dhbOrderNo) {
    }

    record ApplyNumberMapping(
            String connectorId,
            String sourceObjectType,
            String sourceObjectId,
            String internalOrderNo,
            String dhbOrderNo,
            String evidence,
            Integer revision) {
    }
}
