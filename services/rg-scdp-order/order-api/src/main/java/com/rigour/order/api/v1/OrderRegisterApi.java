package com.rigour.order.api.v1;

import com.rigour.order.api.v1.model.OrderRegisterModels;
import com.rigour.order.api.v1.model.OrderRegisterModels.HistoryCoverage;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderNumberMappingCommand;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderNumberMappingResult;
import com.rigour.order.api.v1.model.OrderRegisterModels.PaymentCheckCommand;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterLineView;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterOrderView;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPage;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPaymentView;
import com.rigour.order.api.v1.model.OrderRegisterModels.PeriodStatisticsView;
import com.rigour.order.api.v1.model.OrderRegisterModels.ReceivablesView;
import com.rigour.shared.core.api.ApiResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** 订货宝订单登记读取接口；只读复用现有业务表，不新建平行业务模型。 */
public interface OrderRegisterApi {
    String BASE_PATH = "/api/v1/orders/register";

    @GetMapping(BASE_PATH + "/orders")
    ApiResponse<OrderRegisterPage<OrderRegisterOrderView>> orders(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String customerCode,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerEmployeeCode,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Boolean includeSubDepartments,
            @RequestParam(required = false) Instant orderDateFrom,
            @RequestParam(required = false) Instant orderDateTo,
            @RequestParam(required = false) String orderStatusCode,
            @RequestParam(required = false) String paymentStatusCode,
            @RequestParam(required = false) Boolean hasUnpaid,
            @RequestParam(required = false) String invoiceStatusCode,
            @RequestParam(required = false) Boolean dhbLinked,
            @RequestParam(required = false) String dhbOrderNo,
            @RequestParam(required = false) Boolean hasDiscount,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection,
            @RequestParam(required = false) String createdBy);

    @GetMapping(BASE_PATH + "/lines")
    ApiResponse<OrderRegisterPage<OrderRegisterLineView>> lines(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String customerCode,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerEmployeeCode,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Boolean includeSubDepartments,
            @RequestParam(required = false) Instant orderDateFrom,
            @RequestParam(required = false) Instant orderDateTo,
            @RequestParam(required = false) String orderStatusCode,
            @RequestParam(required = false) String productKeyword,
            @RequestParam(required = false) String productCode,
            @RequestParam(required = false) List<Long> productIds,
            @RequestParam(required = false) String paymentStatusCode,
            @RequestParam(required = false) Boolean hasDiscount,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection);

    @GetMapping(BASE_PATH + "/payments")
    ApiResponse<OrderRegisterPage<OrderRegisterPaymentView>> payments(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String customerCode,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerEmployeeCode,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Boolean includeSubDepartments,
            @RequestParam(required = false) Instant orderDateFrom,
            @RequestParam(required = false) Instant orderDateTo,
            @RequestParam(required = false) String orderStatusCode,
            @RequestParam(required = false) String paymentNo,
            @RequestParam(required = false) String transactionNo,
            @RequestParam(required = false) String paymentStatusCode,
            @RequestParam(required = false) Instant paymentTimeFrom,
            @RequestParam(required = false) Instant paymentTimeTo,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection,
            @RequestParam(required = false) String createdBy,
            @RequestParam(required = false) List<Long> productIds);

    /** 财务核对回款：与银行流水核对后写入交易单号，用于凭证验重与对账。 */
    @PostMapping(BASE_PATH + "/payments/{id}/check")
    ApiResponse<OrderRegisterPaymentView> checkPayment(
            @PathVariable("id") Long id, @RequestBody PaymentCheckCommand command);

    @GetMapping(BASE_PATH + "/statistics/period")
    ApiResponse<PeriodStatisticsView> periodStatistics(
            @RequestParam LocalDate dateFrom,
            @RequestParam LocalDate dateTo,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerEmployeeCode,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Boolean includeSubDepartments,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String customerCode);

    /** 创建人下拉数据源；来自订单真实创建人，去空、去重、稳定排序。 */
    @GetMapping(BASE_PATH + "/creators")
    ApiResponse<List<String>> creators();

    @GetMapping(BASE_PATH + "/statistics/receivables")
    ApiResponse<OrderRegisterPage<ReceivablesView>> receivables(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam LocalDate asOfDate,
            @RequestParam(defaultValue = "true") boolean hasUnpaid,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerEmployeeCode,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Boolean includeSubDepartments,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String orderNo);

    @PostMapping(BASE_PATH + "/number-mappings")
    ApiResponse<OrderNumberMappingResult> mapOrderNumber(
            @RequestBody OrderNumberMappingCommand command);

    @GetMapping(BASE_PATH + "/number-mappings")
    ApiResponse<OrderRegisterPage<OrderRegisterModels.NumberMappingView>> numberMappings(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String internalOrderNo,
            @RequestParam(required = false) String dhbOrderNo);

    @GetMapping(value = BASE_PATH + "/orders/export", produces = "text/csv;charset=UTF-8")
    ResponseEntity<byte[]> exportOrders(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String customerCode,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerEmployeeCode,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Boolean includeSubDepartments,
            @RequestParam(required = false) Instant orderDateFrom,
            @RequestParam(required = false) Instant orderDateTo,
            @RequestParam(required = false) String orderStatusCode,
            @RequestParam(required = false) String paymentStatusCode,
            @RequestParam(required = false) Boolean hasUnpaid,
            @RequestParam(required = false) String invoiceStatusCode,
            @RequestParam(required = false) String dhbOrderNo,
            @RequestParam(required = false) Boolean hasDiscount,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection,
            @RequestParam(required = false) String createdBy);

    @GetMapping(value = BASE_PATH + "/lines/export", produces = "text/csv;charset=UTF-8")
    ResponseEntity<byte[]> exportLines(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String customerCode,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerEmployeeCode,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Boolean includeSubDepartments,
            @RequestParam(required = false) Instant orderDateFrom,
            @RequestParam(required = false) Instant orderDateTo,
            @RequestParam(required = false) String orderStatusCode,
            @RequestParam(required = false) String productKeyword,
            @RequestParam(required = false) String productCode,
            @RequestParam(required = false) List<Long> productIds,
            @RequestParam(required = false) String paymentStatusCode,
            @RequestParam(required = false) Boolean hasDiscount,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection);

    @GetMapping(value = BASE_PATH + "/payments/export", produces = "text/csv;charset=UTF-8")
    ResponseEntity<byte[]> exportPayments(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String customerCode,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerEmployeeCode,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Boolean includeSubDepartments,
            @RequestParam(required = false) Instant orderDateFrom,
            @RequestParam(required = false) Instant orderDateTo,
            @RequestParam(required = false) String orderStatusCode,
            @RequestParam(required = false) String paymentNo,
            @RequestParam(required = false) String transactionNo,
            @RequestParam(required = false) String paymentStatusCode,
            @RequestParam(required = false) Instant paymentTimeFrom,
            @RequestParam(required = false) Instant paymentTimeTo,
            @RequestParam(required = false) String createdBy,
            @RequestParam(required = false) List<Long> productIds,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection);

    @GetMapping(value = BASE_PATH + "/statistics/receivables/export", produces = "text/csv;charset=UTF-8")
    ResponseEntity<byte[]> exportReceivables(
            @RequestParam LocalDate asOfDate,
            @RequestParam(defaultValue = "true") boolean hasUnpaid,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerEmployeeCode,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Boolean includeSubDepartments,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String orderNo);

    @GetMapping(value = BASE_PATH + "/statistics/period/export", produces = "text/csv;charset=UTF-8")
    ResponseEntity<byte[]> exportPeriod(
            @RequestParam LocalDate dateFrom,
            @RequestParam LocalDate dateTo,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerEmployeeCode,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Boolean includeSubDepartments,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String customerCode);
}
