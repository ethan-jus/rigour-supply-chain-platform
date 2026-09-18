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

/** 订单登记读取与订单号映射仓储端口；读取复用既有业务表，不得新建平行业务表。 */
public interface OrderRegisterStore {
    OrderRegisterPage<OrderRegisterOrderView> orders(
            String tenantId, int begin, int step, OrderCriteria criteria);

    OrderRegisterPage<OrderRegisterLineView> lines(
            String tenantId, int begin, int step, LineCriteria criteria);

    OrderRegisterPage<OrderRegisterPaymentView> payments(
            String tenantId, int begin, int step, PaymentCriteria criteria);

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
            Long departmentId,
            Instant orderDateFrom,
            Instant orderDateTo,
            String orderStatusCode,
            String paymentStatusCode,
            Boolean hasUnpaid) {
    }

    record LineCriteria(
            String orderNo,
            Long customerId,
            String customerName,
            String customerCode,
            String regionCode,
            String ownerEmployeeCode,
            Long departmentId,
            Instant orderDateFrom,
            Instant orderDateTo,
            String orderStatusCode,
            String productKeyword,
            String productCode) {
    }

    record PaymentCriteria(
            String orderNo,
            Long customerId,
            String customerName,
            String customerCode,
            String regionCode,
            String ownerEmployeeCode,
            Long departmentId,
            Instant orderDateFrom,
            Instant orderDateTo,
            String orderStatusCode,
            String paymentNo,
            String transactionNo,
            String paymentStatusCode,
            Instant paymentTimeFrom,
            Instant paymentTimeTo) {
    }

    record PeriodCriteria(
            LocalDate dateFrom,
            LocalDate dateTo,
            String groupBy,
            String regionCode,
            String ownerEmployeeCode,
            Long departmentId,
            Long customerId,
            String customerName,
            String customerCode) {
    }

    record ReceivablesCriteria(
            LocalDate asOfDate,
            boolean hasUnpaid,
            String regionCode,
            String ownerEmployeeCode,
            Long departmentId,
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
