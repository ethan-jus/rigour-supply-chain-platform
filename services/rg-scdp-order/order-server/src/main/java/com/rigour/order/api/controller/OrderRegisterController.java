package com.rigour.order.api.controller;

import com.rigour.order.api.v1.OrderRegisterApi;
import com.rigour.order.api.v1.model.OrderRegisterModels.HistoryCoverage;
import com.rigour.order.api.v1.model.OrderRegisterModels.NumberMappingView;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderNumberMappingCommand;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderNumberMappingResult;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterLineView;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterOrderView;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPage;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPaymentView;
import com.rigour.order.api.v1.model.OrderRegisterModels.PaymentCheckCommand;
import com.rigour.order.api.v1.model.OrderRegisterModels.PeriodStatisticsView;
import com.rigour.order.api.v1.model.OrderRegisterModels.ReceivablesView;
import com.rigour.order.application.service.sales.OrderRegisterService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 订货宝订单登记 HTTP 边界；只读查询与订单号映射入口。 */
@RestController
public class OrderRegisterController implements OrderRegisterApi {
    private static final int EXPORT_PAGE_STEP = 200;

    private final OrderRegisterService service;

    public OrderRegisterController(OrderRegisterService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<OrderRegisterPage<OrderRegisterOrderView>> orders(
            int begin,
            int step,
            String orderNo,
            Long customerId,
            String customerName,
            String customerCode,
            String regionCode,
            String ownerEmployeeCode,
            Long departmentId,
            Boolean includeSubDepartments,
            Instant orderDateFrom,
            Instant orderDateTo,
            String orderStatusCode,
            String paymentStatusCode,
            Boolean hasUnpaid,
            String invoiceStatusCode,
            Boolean dhbLinked,
            String dhbOrderNo,
            Boolean hasDiscount, String sortBy, String sortDirection, String createdBy) {
        return ApiResponse.success(
                service.orders(
                        begin,
                        step,
                        orderNo,
                        customerId,
                        customerName,
                        customerCode,
                        regionCode,
                        ownerEmployeeCode,
                        departmentId,
                        includeSubDepartments,
                        orderDateFrom,
                        orderDateTo,
                        orderStatusCode,
                        paymentStatusCode,
                        hasUnpaid,
                        invoiceStatusCode,
                        dhbLinked, dhbOrderNo, hasDiscount, sortBy, sortDirection, createdBy));
    }

    @Override
    public ApiResponse<OrderRegisterPage<OrderRegisterLineView>> lines(
            int begin,
            int step,
            String orderNo,
            Long customerId,
            String customerName,
            String customerCode,
            String regionCode,
            String ownerEmployeeCode,
            Long departmentId,
            Boolean includeSubDepartments,
            Instant orderDateFrom,
            Instant orderDateTo,
            String orderStatusCode,
            String productKeyword,
            String productCode,
            List<Long> productIds,
            String paymentStatusCode,
            Boolean hasDiscount,
            String sortBy,
            String sortDirection) {
        return ApiResponse.success(
                service.lines(
                        begin,
                        step,
                        orderNo,
                        customerId,
                        customerName,
                        customerCode,
                        regionCode,
                        ownerEmployeeCode,
                        departmentId,
                        includeSubDepartments,
                        orderDateFrom,
                        orderDateTo,
                        orderStatusCode,
                        productKeyword,
                        productCode,
                        productIds, paymentStatusCode, hasDiscount, sortBy, sortDirection));
    }

    @Override
    public ApiResponse<OrderRegisterPage<OrderRegisterPaymentView>> payments(
            int begin,
            int step,
            String orderNo,
            Long customerId,
            String customerName,
            String customerCode,
            String regionCode,
            String ownerEmployeeCode,
            Long departmentId,
            Boolean includeSubDepartments,
            Instant orderDateFrom,
            Instant orderDateTo,
            String orderStatusCode,
            String paymentNo,
            String transactionNo,
            String paymentStatusCode,
            Instant paymentTimeFrom,
            Instant paymentTimeTo,
            String sortBy,
            String sortDirection, String createdBy) {
        return ApiResponse.success(
                service.payments(
                        begin,
                        step,
                        orderNo,
                        customerId,
                        customerName,
                        customerCode,
                        regionCode,
                        ownerEmployeeCode,
                        departmentId,
                        includeSubDepartments,
                        orderDateFrom,
                        orderDateTo,
                        orderStatusCode,
                        paymentNo,
                        transactionNo,
                        paymentStatusCode,
                        paymentTimeFrom,
                        paymentTimeTo,
                        sortBy,
                        sortDirection, createdBy));
    }

    @Override
    public ApiResponse<OrderRegisterPaymentView> checkPayment(Long id, PaymentCheckCommand command) {
        return ApiResponse.success(service.checkPayment(id, command));
    }

    @Override
    public ApiResponse<PeriodStatisticsView> periodStatistics(
            LocalDate dateFrom,
            LocalDate dateTo,
            String groupBy,
            String regionCode,
            String ownerEmployeeCode,
            Long departmentId,
            Boolean includeSubDepartments,
            Long customerId,
            String customerName,
            String customerCode) {
        return ApiResponse.success(
                service.periodStatistics(
                        dateFrom,
                        dateTo,
                        groupBy,
                        regionCode,
                        ownerEmployeeCode,
                        departmentId,
                        includeSubDepartments,
                        customerId,
                        customerName,
                        customerCode));
    }

    @Override
    public ApiResponse<List<String>> creators() {
        return ApiResponse.success(service.creators());
    }

    @Override
    public ApiResponse<OrderRegisterPage<ReceivablesView>> receivables(
            int begin,
            int step,
            LocalDate asOfDate,
            boolean hasUnpaid,
            String regionCode,
            String ownerEmployeeCode,
            Long departmentId,
            Boolean includeSubDepartments,
            Long customerId,
            String orderNo) {
        return ApiResponse.success(
                service.receivables(
                        begin,
                        step,
                        asOfDate,
                        hasUnpaid,
                        regionCode,
                        ownerEmployeeCode,
                        departmentId,
                        includeSubDepartments,
                        customerId,
                        orderNo));
    }

    @Override
    public ApiResponse<OrderNumberMappingResult> mapOrderNumber(
            OrderNumberMappingCommand command) {
        return ApiResponse.success(
                service.mapOrderNumber(
                        command.connectorId(),
                        command.sourceObjectType(),
                        command.sourceObjectId(),
                        command.internalOrderNo(),
                        command.dhbOrderNo(),
                        command.evidence(),
                        command.revision()));
    }

    @Override
    public ApiResponse<OrderRegisterPage<NumberMappingView>> numberMappings(
            int begin,
            int step,
            String state,
            String internalOrderNo,
            String dhbOrderNo) {
        return ApiResponse.success(
                service.numberMappings(begin, step, state, internalOrderNo, dhbOrderNo));
    }

    @Override
    public ResponseEntity<byte[]> exportOrders(
            String orderNo,
            Long customerId,
            String customerName,
            String customerCode,
            String regionCode,
            String ownerEmployeeCode,
            Long departmentId,
            Boolean includeSubDepartments,
            Instant orderDateFrom,
            Instant orderDateTo,
            String orderStatusCode,
            String paymentStatusCode,
            Boolean hasUnpaid,
            String invoiceStatusCode,
            String dhbOrderNo,
            Boolean hasDiscount, String sortBy, String sortDirection, String createdBy) {
        List<OrderRegisterOrderView> rows = new ArrayList<>();
        for (int offset = 0; ; offset += EXPORT_PAGE_STEP) {
            var page =
                    service.orders(
                            offset,
                            EXPORT_PAGE_STEP,
                            orderNo,
                            customerId,
                            customerName,
                            customerCode,
                            regionCode,
                            ownerEmployeeCode,
                            departmentId,
                            includeSubDepartments,
                            orderDateFrom,
                            orderDateTo,
                            orderStatusCode,
                            paymentStatusCode,
                            hasUnpaid,
                            invoiceStatusCode,
                            null, dhbOrderNo, hasDiscount, sortBy, sortDirection, createdBy);
            rows.addAll(page.items());
            if (offset + EXPORT_PAGE_STEP >= page.total()) break;
        }
        return csv(
                "orders.csv",
                new String[] {
                    "订单号", "旧内部订单号", "来源系统", "来源单号", "客户编码", "客户名称",
                    "归属地区", "所属业务员", "部门", "订单状态", "收款状态", "订货金额",
                    "订单金额", "优惠额", "优惠率", "收款金额", "待收金额", "已核金额", "下单时间", "创建人",
                    "创建时间", "修改人", "修改时间", "同步人", "同步时间"
                },
                rows.stream()
                        .map(
                                r ->
                                        List.of(
                                                str(r.orderNo()),
                                                str(r.legacyOrderNo()),
                                                str(r.sourceSystemCode()),
                                                str(r.sourceOrderNo()),
                                                str(r.customerCode()),
                                                str(r.customerName()),
                                                str(r.regionName() == null ? r.regionCode() : r.regionName()),
                                                str(r.ownerEmployeeName() == null
                                                        ? r.ownerEmployeeCode()
                                                        : r.ownerEmployeeName()),
                                                str(r.departmentName()),
                                                str(r.orderStatusCode()),
                                                str(r.paymentStatusCode()),
                                                r.originalAmount() == null ? "" : r.originalAmount().toPlainString(),
                                                dec(r.payableAmount()),
                                                r.discountAmount() == null ? "" : r.discountAmount().toPlainString(),
                                                r.discountRate() == null ? "" : r.discountRate().movePointRight(2).setScale(2, java.math.RoundingMode.HALF_UP) + "%",
                                                dec(r.paidAmount()),
                                                dec(r.unpaidAmount()),
                                                dec(r.checkedAmount()),
                                                time(r.orderDate()),
                                                str(r.createdBy()),
                                                time(r.createdTime()),
                                                str(r.updatedBy()),
                                                time(r.updatedTime()),
                                                str(r.syncedBy()),
                                                time(r.syncedAt())))
                        .toList());
    }

    @Override
    public ResponseEntity<byte[]> exportLines(
            String orderNo,
            Long customerId,
            String customerName,
            String customerCode,
            String regionCode,
            String ownerEmployeeCode,
            Long departmentId,
            Boolean includeSubDepartments,
            Instant orderDateFrom,
            Instant orderDateTo,
            String orderStatusCode,
            String productKeyword,
            String productCode,
            List<Long> productIds,
            String paymentStatusCode,
            Boolean hasDiscount,
            String sortBy,
            String sortDirection) {
        List<OrderRegisterLineView> rows = new ArrayList<>();
        for (int offset = 0; ; offset += EXPORT_PAGE_STEP) {
            var page =
                    service.lines(
                            offset,
                            EXPORT_PAGE_STEP,
                            orderNo,
                            customerId,
                            customerName,
                            customerCode,
                            regionCode,
                            ownerEmployeeCode,
                            departmentId,
                            includeSubDepartments,
                            orderDateFrom,
                            orderDateTo,
                            orderStatusCode,
                            productKeyword,
                            productCode,
                            productIds, paymentStatusCode, hasDiscount, sortBy, sortDirection);
            rows.addAll(page.items());
            if (offset + EXPORT_PAGE_STEP >= page.total()) break;
        }
        return csv(
                "lines.csv",
                new String[] {
                    "订单号", "来源明细号", "客户名称", "归属地区", "所属业务员", "商品编码", "商品名称",
                    "规格", "单位", "数量", "单价", "订货金额", "订单金额（分摊后）", "优惠额（分摊后）", "优惠率", "收款状态", "下单时间"
                },
                rows.stream()
                        .map(
                                r ->
                                        List.of(
                                                str(r.orderNo()),
                                                str(r.sourceLineId()),
                                                str(r.customerName()),
                                                str(r.regionName() == null ? r.regionCode() : r.regionName()),
                                                str(r.ownerEmployeeName() == null
                                                        ? r.ownerEmployeeCode()
                                                        : r.ownerEmployeeName()),
                                                str(r.productCode()),
                                                str(r.productName()),
                                                str(r.specification()),
                                                str(r.unitCode()),
                                                dec(r.quantity()),
                                                dec(r.unitPrice()),
                                                dec(r.lineAmount()),
                                                dec(r.orderAmount()),
                                                dec(r.discountAmount()),
                                                r.discountRate() == null ? "" : r.discountRate().multiply(BigDecimal.valueOf(100)).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "%",
                                                str(r.paymentStatusCode()),
                                                time(r.orderDate())))
                        .toList());
    }

    @Override
    public ResponseEntity<byte[]> exportPayments(
            String orderNo,
            Long customerId,
            String customerName,
            String customerCode,
            String regionCode,
            String ownerEmployeeCode,
            Long departmentId,
            Boolean includeSubDepartments,
            Instant orderDateFrom,
            Instant orderDateTo,
            String orderStatusCode,
            String paymentNo,
            String transactionNo,
            String paymentStatusCode,
            Instant paymentTimeFrom,
            Instant paymentTimeTo, String createdBy) {
        List<OrderRegisterPaymentView> rows = new ArrayList<>();
        for (int offset = 0; ; offset += EXPORT_PAGE_STEP) {
            var page =
                    service.payments(
                            offset,
                            EXPORT_PAGE_STEP,
                            orderNo,
                            customerId,
                            customerName,
                            customerCode,
                            regionCode,
                            ownerEmployeeCode,
                            departmentId,
                            includeSubDepartments,
                            orderDateFrom,
                            orderDateTo,
                            orderStatusCode,
                            paymentNo,
                            transactionNo,
                            paymentStatusCode,
                            paymentTimeFrom,
                            paymentTimeTo,
                            null,
                            null, createdBy);
            rows.addAll(page.items());
            if (offset + EXPORT_PAGE_STEP >= page.total()) break;
        }
        return csv(
                "payments.csv",
                new String[] {
                    "收款编码", "来源付款编码", "订单号", "客户名称", "归属地区", "业务员", "订单金额",
                    "收款金额", "收款状态", "收款时间", "交易单号", "核对人", "核对时间"
                },
                rows.stream()
                        .map(
                                r ->
                                        List.of(
                                                str(r.paymentNo()),
                                                str(r.sourceRecordId()),
                                                str(r.orderNo()),
                                                str(r.customerName()),
                                                str(r.regionName() == null ? r.regionCode() : r.regionName()),
                                                str(r.ownerEmployeeName() == null
                                                        ? r.ownerEmployeeCode()
                                                        : r.ownerEmployeeName()),
                                                dec(r.orderAmount()),
                                                dec(r.paidAmount()),
                                                str(r.paymentStatusCode()),
                                                time(r.paymentTime()),
                                                str(r.transactionNo()),
                                                str(r.checkedBy()),
                                                time(r.checkedAt())))
                        .toList());
    }

    @Override
    public ResponseEntity<byte[]> exportReceivables(
            LocalDate asOfDate,
            boolean hasUnpaid,
            String regionCode,
            String ownerEmployeeCode,
            Long departmentId,
            Boolean includeSubDepartments,
            Long customerId,
            String orderNo) {
        List<ReceivablesView> rows = new ArrayList<>();
        for (int offset = 0; ; offset += EXPORT_PAGE_STEP) {
            var page =
                    service.receivables(
                            offset,
                            EXPORT_PAGE_STEP,
                            asOfDate,
                            hasUnpaid,
                            regionCode,
                            ownerEmployeeCode,
                            departmentId,
                            includeSubDepartments,
                            customerId,
                            orderNo);
            rows.addAll(page.items());
            if (offset + EXPORT_PAGE_STEP >= page.total()) break;
        }
        return csv(
                "receivables.csv",
                new String[] {
                    "订单号", "客户名称", "归属地区", "业务员", "下单时间", "截至日应收",
                    "截至日净回款", "截至日未回款", "溢收金额"
                },
                rows.stream()
                        .map(
                                r ->
                                        List.of(
                                                str(r.orderNo()),
                                                str(r.customerName()),
                                                str(r.regionName() == null ? r.regionCode() : r.regionName()),
                                                str(r.ownerEmployeeName() == null
                                                        ? r.ownerEmployeeCode()
                                                        : r.ownerEmployeeName()),
                                                time(r.orderDate()),
                                                dec(r.receivableAmount()),
                                                dec(r.netReceivedAmount()),
                                                dec(r.unpaidAmount()),
                                                dec(r.overpaidAmount())))
                        .toList());
    }

    @Override
    public ResponseEntity<byte[]> exportPeriod(
            LocalDate dateFrom,
            LocalDate dateTo,
            String groupBy,
            String regionCode,
            String ownerEmployeeCode,
            Long departmentId,
            Boolean includeSubDepartments,
            Long customerId,
            String customerName,
            String customerCode) {
        var view =
                service.periodStatistics(
                        dateFrom,
                        dateTo,
                        groupBy,
                        regionCode,
                        ownerEmployeeCode,
                        departmentId,
                        includeSubDepartments,
                        customerId,
                        customerName,
                        customerCode);
        return csv(
                "period.csv",
                new String[] {
                    "分组", "期间订单额", "期间实收", "期间退款冲销", "期间净回款", "期末未回款"
                },
                view.rows().stream()
                        .map(
                                r ->
                                        List.of(
                                                str(r.label()),
                                                dec(r.periodOrderAmount()),
                                                dec(r.periodReceivedAmount()),
                                                dec(r.periodRefundAmount()),
                                                dec(r.periodNetReceivedAmount()),
                                                dec(r.endingUnpaidAmount())))
                        .toList());
    }

    private static ResponseEntity<byte[]> csv(
            String filename, String[] headers, List<List<String>> rows) {
        StringBuilder builder = new StringBuilder();
        builder.append('\uFEFF');
        appendCsvLine(builder, List.of(headers));
        for (List<String> row : rows) appendCsvLine(builder, row);
        byte[] bytes = builder.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .contentLength(bytes.length)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename, StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(bytes);
    }

    private static void appendCsvLine(StringBuilder builder, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) builder.append(',');
            String value = values.get(i);
            if (value == null || value.isEmpty()) continue;
            if (value.indexOf(',') >= 0
                    || value.indexOf('"') >= 0
                    || value.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0) {
                builder.append('"').append(value.replace("\"", "\"\"")).append('"');
            } else {
                builder.append(value);
            }
        }
        builder.append('\n');
    }

    private static String str(String value) {
        return value == null ? "" : value;
    }

    private static String dec(java.math.BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private static String time(Instant value) {
        return value == null ? "" : value.toString();
    }
}
