package com.rigour.order.api.controller;

import com.rigour.order.api.v1.OrderFundDocumentApi;
import com.rigour.order.api.v1.model.FundDocumentCommand;
import com.rigour.order.api.v1.model.FundDocumentDetailView;
import com.rigour.order.api.v1.model.FundDocumentSummaryView;
import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.order.application.service.sales.OrderFundDocumentService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.RestController;

/** Order 资金收付款单 HTTP 边界。 */
@RestController
public final class OrderFundDocumentController implements OrderFundDocumentApi {
    private final OrderFundDocumentService service;

    public OrderFundDocumentController(OrderFundDocumentService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<OrderPageView<FundDocumentSummaryView>> fundDocuments(
            int begin, int step, String keyword, String directionCode, String documentNo, String sourceDocumentNo,
            String salesOrderNo, String sourceOrderNo, String paymentSerialNo, String counterpartyName,
            String handlerStaffCode, String settlementMethodCode, String businessTypeCode, String documentStatusCode,
            Instant occurredTimeFrom, Instant occurredTimeTo) {
        return ApiResponse.success(service.fundDocuments(begin, step, keyword, directionCode, documentNo,
                sourceDocumentNo, salesOrderNo, sourceOrderNo, paymentSerialNo, counterpartyName, handlerStaffCode,
                settlementMethodCode, businessTypeCode, documentStatusCode, occurredTimeFrom, occurredTimeTo));
    }

    @Override
    public ApiResponse<FundDocumentDetailView> fundDocument(Long id) {
        return ApiResponse.success(service.fundDocument(id));
    }

    @Override
    public ApiResponse<FundDocumentDetailView> createFundDocument(FundDocumentCommand command) {
        return ApiResponse.success(service.create(command));
    }

    @Override
    public ApiResponse<FundDocumentDetailView> updateFundDocument(Long id, FundDocumentCommand command) {
        return ApiResponse.success(service.update(id, command));
    }

    @Override
    public ApiResponse<Void> deleteFundDocument(Long id, int revision) {
        service.delete(id, revision);
        return ApiResponse.success(null);
    }
}
