package com.rigour.integration.api.controller.dhb;

import com.rigour.integration.api.v1.DhbIntegrationInternalApi;
import com.rigour.integration.api.v1.model.DhbApiModels.ExternalObjectMappingBatchCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ExternalObjectMappingBatchResult;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.integration.application.service.dhb.DhbIntegrationService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 仅供内部服务调度使用的订货宝目标发现接口，不经过Gateway浏览器路由。 */
@RestController
@RequestMapping(DhbIntegrationInternalApi.BASE_PATH)
public final class DhbIntegrationInternalController implements DhbIntegrationInternalApi {

    private final DhbIntegrationService service;

    public DhbIntegrationInternalController(DhbIntegrationService service) {
        this.service = service;
    }

    @GetMapping("/sync-targets")
    @Override
    public List<SyncTargetView> syncTargets(
            @RequestParam(name = "objectType", defaultValue = "ORDER") String objectType) {
        return service.syncTargets(objectType);
    }

    @PostMapping("/object-mappings")
    @Override
    public ExternalObjectMappingBatchResult upsertObjectMappings(
            @RequestBody ExternalObjectMappingBatchCommand command) {
        return service.upsertObjectMappings(command);
    }
}
