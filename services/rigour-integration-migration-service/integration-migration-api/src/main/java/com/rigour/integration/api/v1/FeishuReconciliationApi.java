package com.rigour.integration.api.v1;

import com.rigour.integration.api.v1.model.FeishuReconciliationModels.CaptureCommand;
import com.rigour.integration.api.v1.model.FeishuReconciliationModels.CaptureSummary;
import com.rigour.integration.api.v1.model.FeishuReconciliationModels.SourceView;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** 受控来源的在线采集入口；不提供任意 Base 查询或正式导入能力。 */
public interface FeishuReconciliationApi {
    String SOURCES_PATH = "/api/v1/integration/feishu/reconciliation-sources";
    String CAPTURES_PATH = "/api/v1/integration/feishu/reconciliation-captures";

    @GetMapping(SOURCES_PATH)
    ApiResponse<List<SourceView>> sources();

    @PostMapping(CAPTURES_PATH)
    ApiResponse<CaptureSummary> capture(@RequestBody CaptureCommand command);
}
