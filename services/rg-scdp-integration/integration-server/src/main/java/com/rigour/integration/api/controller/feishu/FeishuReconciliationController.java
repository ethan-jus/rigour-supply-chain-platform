package com.rigour.integration.api.controller.feishu;

import com.rigour.integration.api.v1.FeishuReconciliationApi;
import com.rigour.integration.api.v1.model.FeishuReconciliationModels.CaptureCommand;
import com.rigour.integration.api.v1.model.FeishuReconciliationModels.CaptureSummary;
import com.rigour.integration.api.v1.model.FeishuReconciliationModels.SourceView;
import com.rigour.integration.application.service.feishu.FeishuReconciliationService;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;

/** 只消费 Gateway 签名身份；请求中没有租户、Base、表或凭据选择项。 */
@RestController
public final class FeishuReconciliationController implements FeishuReconciliationApi {
    private final FeishuReconciliationService service;
    public FeishuReconciliationController(FeishuReconciliationService service) { this.service = service; }
    @Override
    public ApiResponse<List<SourceView>> sources() {
        return ApiResponse.success(service.sources(AuthorizationContext.requireCurrent()));
    }
    @Override
    public ApiResponse<CaptureSummary> capture(CaptureCommand command) {
        return ApiResponse.success(service.capture(AuthorizationContext.requireCurrent(), command));
    }
}
