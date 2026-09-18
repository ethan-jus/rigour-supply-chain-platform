package com.rigour.integration.api.controller.feishu;

import com.rigour.integration.application.service.feishu.FeishuJsapiSignService;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.context.RequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 飞书 H5 初始化签名入口；只允许 Gateway 暴露这一条公共接口。 */
@RestController
@RequestMapping("/api/v1/platform/feishu")
public final class FeishuJsapiController {

    private static final Logger log = LoggerFactory.getLogger(FeishuJsapiController.class);

    private final FeishuJsapiSignService service;

    public FeishuJsapiController(FeishuJsapiSignService service) {
        this.service = service;
    }

    @PostMapping("/jsapi-sign")
    public ApiResponse<FeishuJsapiSignService.SignResult> sign(
            @Valid @RequestBody FeishuJsapiSignCommand command) {
        log.info("飞书 JSSDK 签名请求进入 Integration requestId={} urlLength={}",
                RequestContext.getRequestId(), command.url().length());
        return ApiResponse.success(service.sign(command.url()));
    }

    public record FeishuJsapiSignCommand(@NotBlank String url) { }
}
