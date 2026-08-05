package com.rigour.order.api.controller;

import com.rigour.order.api.v1.model.DhbDocumentPageView;
import com.rigour.order.api.v1.model.DhbFinancialDocumentView;
import com.rigour.order.api.v1.model.DhbReturnDetailView;
import com.rigour.order.api.v1.model.DhbReturnDocumentView;
import com.rigour.order.api.v1.model.DhbShipmentDetailView;
import com.rigour.order.api.v1.model.DhbShipmentDocumentView;
import com.rigour.order.application.service.dhb.DhbOrderDocumentService;
import com.rigour.shared.context.TenantContext;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 订货宝发货、退货、收款和付款的本地只读查询接口。 */
@RestController
@RequestMapping("/api/v1/orders/dhb")
public class DhbOrderDocumentController {
    private final DhbOrderDocumentService service;

    public DhbOrderDocumentController(DhbOrderDocumentService service) { this.service = service; }

    /**
     * 分页查询发货单。
     * @param begin 零基偏移，必须大于等于0
     * @param step 每页数量，范围1..1000
     * @param status shipped待发货、receivedin待收货、received已收货、cancelled已取消
     * @param orderNo 可选关联订单号
     * @param from 可选发货开始时间
     * @param to 可选发货结束时间
     */
    @GetMapping("/shipments")
    public ApiResponse<DhbDocumentPageView<DhbShipmentDocumentView>> shipments(
            @RequestParam(defaultValue = "0") int begin, @RequestParam(defaultValue = "100") int step,
            @RequestParam(required = false) String status, @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return ApiResponse.success(service.shipments(tenantId(), query(begin, step, status, orderNo, from, to)));
    }

    /**
     * 按发货单号查询本地主信息和明细，不调用订货宝。
     * @param shipmentNo 订货宝发货单号ships_num
     */
    @GetMapping("/shipments/{shipmentNo}")
    public ApiResponse<DhbShipmentDetailView> shipment(@PathVariable String shipmentNo) {
        return ApiResponse.success(service.shipment(tenantId(), shipmentNo));
    }

    /**
     * 分页查询退货单。
     * @param begin 零基偏移，必须大于等于0
     * @param step 每页数量，范围1..1000
     * @param status return_audit待审核、shipp_cust待客户发货、shipped待收货、refunded待退款、finished已完成、cancelled已取消
     * @param orderNo 可选关联订单号，精确匹配
     * @param from 可选退货开始时间，yyyy-MM-dd或yyyy-MM-dd HH:mm:ss
     * @param to 可选退货结束时间，格式同from
     */
    @GetMapping("/returns")
    public ApiResponse<DhbDocumentPageView<DhbReturnDocumentView>> returns(
            @RequestParam(defaultValue = "0") int begin, @RequestParam(defaultValue = "100") int step,
            @RequestParam(required = false) String status, @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return ApiResponse.success(service.returns(tenantId(), query(begin, step, status, orderNo, from, to)));
    }

    /**
     * 按退货单号查询本地主信息和商品明细。
     * @param returnNo 订货宝退货单号ReturnsSN
     */
    @GetMapping("/returns/{returnNo}")
    public ApiResponse<DhbReturnDetailView> returnDetail(@PathVariable String returnNo) {
        return ApiResponse.success(service.returnDetail(tenantId(), returnNo));
    }

    /**
     * 查询收款单。
     * @param begin 零基偏移，必须大于等于0
     * @param step 每页数量，范围1..1000
     * @param status pend_receipt待确认、pend_receipted已确认、canceled已取消
     * @param orderNo 可选关联订单号，精确匹配
     * @param from 可选交易开始时间，yyyy-MM-dd或yyyy-MM-dd HH:mm:ss
     * @param to 可选交易结束时间，格式同from
     */
    @GetMapping("/receipts")
    public ApiResponse<DhbDocumentPageView<DhbFinancialDocumentView>> receipts(
            @RequestParam(defaultValue = "0") int begin, @RequestParam(defaultValue = "100") int step,
            @RequestParam(required = false) String status, @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return ApiResponse.success(service.financialDocuments(tenantId(), "RECEIPT",
                query(begin, step, status, orderNo, from, to)));
    }

    /**
     * 查询付款单。
     * @param begin 零基偏移，必须大于等于0
     * @param step 每页数量，范围1..1000
     * @param status pend_receipt待确认、pend_receipted已确认、canceled已取消
     * @param orderNo 可选关联订单号，精确匹配
     * @param from 可选交易开始时间，yyyy-MM-dd或yyyy-MM-dd HH:mm:ss
     * @param to 可选交易结束时间，格式同from
     */
    @GetMapping("/payments")
    public ApiResponse<DhbDocumentPageView<DhbFinancialDocumentView>> payments(
            @RequestParam(defaultValue = "0") int begin, @RequestParam(defaultValue = "100") int step,
            @RequestParam(required = false) String status, @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return ApiResponse.success(service.financialDocuments(tenantId(), "PAYMENT",
                query(begin, step, status, orderNo, from, to)));
    }

    private static DhbOrderDocumentService.Query query(int begin, int step, String status, String orderNo,
                                                       String from, String to) {
        return new DhbOrderDocumentService.Query(begin, step, status, orderNo, from, to);
    }

    private static String tenantId() {
        String value = TenantContext.getTenantId();
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少可信租户上下文");
        return value;
    }
}
