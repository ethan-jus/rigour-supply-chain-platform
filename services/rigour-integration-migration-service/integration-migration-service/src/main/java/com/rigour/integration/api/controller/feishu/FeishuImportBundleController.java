package com.rigour.integration.api.controller.feishu;

import com.rigour.integration.api.v1.FeishuImportBundleApi;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportBatchSummary;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportPreflightResult;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportRunCommand;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportRunResult;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportTemplateView;
import com.rigour.integration.application.service.feishu.FeishuImportAsyncRunService;
import com.rigour.integration.application.service.feishu.FeishuImportBundleService;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 飞书导入中心 HTTP 边界；身份与权限只来自 Gateway 签名上下文。 */
@RestController
public final class FeishuImportBundleController implements FeishuImportBundleApi {
    private final FeishuImportBundleService service;
    private final FeishuImportAsyncRunService asyncRunService;

    public FeishuImportBundleController(FeishuImportBundleService service,
                                        FeishuImportAsyncRunService asyncRunService) {
        this.service = service;
        this.asyncRunService = asyncRunService;
    }

    @Override
    public ApiResponse<List<FeishuImportBatchSummary>> batches(Integer limit) {
        requireImportPermission();
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        return ApiResponse.success(service.batches(caller, limit));
    }

    @Override
    public ApiResponse<FeishuImportPreflightResult> preflight(MultipartFile file, String sourceUrl) {
        requireImportPermission();
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        return ApiResponse.success(service.preflight(caller, file, sourceUrl));
    }

    @Override
    public ApiResponse<FeishuImportPreflightResult> batchPreflight(List<MultipartFile> files, String sourceUrl) {
        requireImportPermission();
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        return ApiResponse.success(service.preflight(caller, files, sourceUrl));
    }

    @Override
    public ApiResponse<List<FeishuImportTemplateView>> templates() {
        requireImportPermission();
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        return ApiResponse.success(service.templates(caller));
    }

    @Override
    public ApiResponse<FeishuImportRunResult> run(UUID batchId, FeishuImportRunCommand command) {
        AuthorizationContext.requirePermission("integration:feishu:write");
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        FeishuImportRunCommand normalized = command == null
                ? new FeishuImportRunCommand(null, false, false, false)
                : command;
        if (Boolean.TRUE.equals(normalized.async()) && !Boolean.TRUE.equals(normalized.dryRun())) {
            return ApiResponse.success(asyncRunService.start(caller, batchId, normalized));
        }
        return ApiResponse.success(service.run(caller, batchId, normalized));
    }

    @Override
    public ApiResponse<FeishuImportRunResult> runStatus(UUID batchId, Integer limit) {
        AuthorizationContext.requirePermission("integration:feishu:write");
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        return ApiResponse.success(service.runStatus(caller, batchId, limit));
    }

    private static void requireImportPermission() {
        if (AuthorizationContext.hasPermission("integration:feishu:import")
                || AuthorizationContext.hasPermission("integration:feishu:write")) {
            return;
        }
        AuthorizationContext.requirePermission("integration:feishu:import");
    }
}
