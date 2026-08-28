package com.rigour.order.api.v1;

import com.rigour.order.api.v1.model.FundDocumentCommand;
import com.rigour.order.api.v1.model.FundDocumentDetailView;
import com.rigour.order.api.v1.model.FundDocumentSummaryView;
import com.rigour.order.api.v1.model.OrderPageView;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** Order 资金收付款单接口；第三方收款单/付款单只能作为来源映射写入本业务模型。 */
public interface OrderFundDocumentApi {
    String BASE_PATH = "/api/v1/orders/fund-documents";

    @GetMapping(BASE_PATH)
    ApiResponse<OrderPageView<FundDocumentSummaryView>> fundDocuments(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String directionCode,
            @RequestParam(required = false) String documentNo,
            @RequestParam(required = false) String sourceDocumentNo,
            @RequestParam(required = false) String salesOrderNo,
            @RequestParam(required = false) String sourceOrderNo,
            @RequestParam(required = false) String paymentSerialNo,
            @RequestParam(required = false) String counterpartyName,
            @RequestParam(required = false) String handlerStaffCode,
            @RequestParam(required = false) String settlementMethodCode,
            @RequestParam(required = false) String businessTypeCode,
            @RequestParam(required = false) String documentStatusCode,
            @RequestParam(required = false) Instant occurredTimeFrom,
            @RequestParam(required = false) Instant occurredTimeTo);

    @GetMapping(BASE_PATH + "/{id}")
    ApiResponse<FundDocumentDetailView> fundDocument(@PathVariable("id") Long id);

    @PostMapping(BASE_PATH)
    ApiResponse<FundDocumentDetailView> createFundDocument(@RequestBody FundDocumentCommand command);

    @PutMapping(BASE_PATH + "/{id}")
    ApiResponse<FundDocumentDetailView> updateFundDocument(
            @PathVariable("id") Long id, @RequestBody FundDocumentCommand command);

    @DeleteMapping(BASE_PATH + "/{id}")
    ApiResponse<Void> deleteFundDocument(@PathVariable("id") Long id, @RequestParam int revision);
}
