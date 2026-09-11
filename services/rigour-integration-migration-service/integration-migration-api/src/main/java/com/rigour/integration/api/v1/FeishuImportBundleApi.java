package com.rigour.integration.api.v1;

import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportBatchSummary;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportPreflightResult;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportRunCommand;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportRunResult;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportTemplateView;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

/** 飞书导出数据导入中心契约；只暴露批次化导入入口，不把导入逻辑散到各业务页面。 */
public interface FeishuImportBundleApi {
    String BASE_PATH = "/api/v1/integration/feishu/import-bundles";
    String PREFLIGHT_PATH = BASE_PATH + "/preflight";
    String BATCH_PREFLIGHT_PATH = BASE_PATH + "/batch-preflight";
    String RUN_PATH = BASE_PATH + "/{batchId}/runs";
    String RUN_STATUS_PATH = BASE_PATH + "/{batchId}/runs/latest";
    String TEMPLATES_PATH = BASE_PATH + "/templates";

    @GetMapping(BASE_PATH)
    ApiResponse<List<FeishuImportBatchSummary>> batches(
            @RequestParam(name = "limit", required = false, defaultValue = "20") Integer limit);

    @GetMapping(TEMPLATES_PATH)
    ApiResponse<List<FeishuImportTemplateView>> templates();

    @PostMapping(path = PREFLIGHT_PATH, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<FeishuImportPreflightResult> preflight(
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "sourceUrl", required = false) String sourceUrl);

    @PostMapping(path = BATCH_PREFLIGHT_PATH, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<FeishuImportPreflightResult> batchPreflight(
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam(name = "sourceUrl", required = false) String sourceUrl);

    /** 对已落原始行的批次执行领域投影；缺少内部映射的行保留为待处理。 */
    @PostMapping(RUN_PATH)
    ApiResponse<FeishuImportRunResult> run(
            @PathVariable("batchId") UUID batchId,
            @RequestBody(required = false) FeishuImportRunCommand command);

    /** 查询已提交正式导入的最新执行状态，用于前端轮询展示后台处理进度。 */
    @GetMapping(RUN_STATUS_PATH)
    ApiResponse<FeishuImportRunResult> runStatus(
            @PathVariable("batchId") UUID batchId,
            @RequestParam(name = "limit", required = false, defaultValue = "500") Integer limit);
}
