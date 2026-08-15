package com.rigour.settings.api;

import com.rigour.settings.api.v1.BusinessDictionaryInternalApi;
import com.rigour.settings.api.v1.model.DictSyncCommand;
import com.rigour.settings.api.v1.model.DictSyncResult;
import com.rigour.settings.application.service.BusinessDictionaryService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅供领域服务批量补齐已定义字典项的内部 HTTP 入口。 */
@RestController
@RequestMapping(BusinessDictionaryInternalApi.BASE_PATH)
public final class BusinessDictionaryInternalController implements BusinessDictionaryInternalApi {
    private final BusinessDictionaryService service;

    public BusinessDictionaryInternalController(BusinessDictionaryService service) {
        this.service = service;
    }

    @Override
    @PostMapping("/items/sync")
    public ApiResponse<DictSyncResult> syncItems(@RequestBody DictSyncCommand command) {
        return ApiResponse.success(service.syncItems(command));
    }
}
