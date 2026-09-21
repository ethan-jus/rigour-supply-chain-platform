package com.rigour.order.api.v1;

import com.rigour.order.api.v1.model.OrderInvoiceModels.OrderInvoiceApplyCommand;
import com.rigour.order.api.v1.model.OrderInvoiceModels.OrderInvoiceCompleteCommand;
import com.rigour.order.api.v1.model.OrderInvoiceModels.OrderInvoicePageView;
import com.rigour.order.api.v1.model.OrderInvoiceModels.OrderInvoiceProfileView;
import com.rigour.order.api.v1.model.OrderInvoiceModels.OrderInvoiceView;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

/** 订单开票登记接口；发票按内部订单登记，只读复用 order:read，写操作要求 order:invoice:write。 */
public interface OrderInvoiceApi {
    String BASE_PATH = "/api/v1/orders/invoices";

    @GetMapping(BASE_PATH)
    ApiResponse<OrderInvoiceView> invoice(@RequestParam String orderNo);

    /** 发票管理分页查询；按状态/订单号/客户/申请时间筛选，财务集中处理待开票。 */
    @GetMapping(BASE_PATH + "/page")
    ApiResponse<OrderInvoicePageView> page(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) Instant appliedFrom,
            @RequestParam(required = false) Instant appliedTo);

    /** 该订单客户已保存的开票资料；申请弹窗下拉选择与回显，最近使用优先。 */
    @GetMapping(BASE_PATH + "/profiles")
    ApiResponse<List<OrderInvoiceProfileView>> profiles(@RequestParam String orderNo);

    @PostMapping(BASE_PATH)
    ApiResponse<OrderInvoiceView> apply(@RequestBody OrderInvoiceApplyCommand command);

    @PostMapping(value = BASE_PATH + "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<OrderInvoiceView> uploadAttachments(
            @PathVariable("id") Long id, @RequestPart("files") List<MultipartFile> files);

    @PostMapping(BASE_PATH + "/{id}/complete")
    ApiResponse<OrderInvoiceView> complete(
            @PathVariable("id") Long id, @RequestBody OrderInvoiceCompleteCommand command);

    @PostMapping(BASE_PATH + "/{id}/withdraw")
    ApiResponse<OrderInvoiceView> withdraw(@PathVariable("id") Long id);
}
