package com.rigour.settings.api;

import com.rigour.settings.api.v1.BusinessDictionaryApi;
import com.rigour.settings.api.v1.model.DictCommand;
import com.rigour.settings.api.v1.model.DictItemCommand;
import com.rigour.settings.api.v1.model.DictItemView;
import com.rigour.settings.api.v1.model.DictView;
import com.rigour.settings.api.v1.model.EffectiveDictView;
import com.rigour.settings.application.service.BusinessDictionaryService;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 公共业务字典 HTTP 入口。 */
@RestController
@RequestMapping(BusinessDictionaryApi.BASE_PATH)
public class BusinessDictionaryController implements BusinessDictionaryApi {
    private final BusinessDictionaryService service;

    public BusinessDictionaryController(BusinessDictionaryService service) {
        this.service = service;
    }

    @Override
    @GetMapping
    public ApiResponse<List<DictView>> list(
            @RequestParam(required = false) String moduleCode,
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(service.list(moduleCode, scopeType, tenantId, status));
    }

    @Override
    @GetMapping("/{dictId}/items")
    public ApiResponse<List<DictItemView>> items(@PathVariable("dictId") UUID dictId) {
        return ApiResponse.success(service.items(dictId));
    }

    @Override
    @GetMapping("/effective")
    public ApiResponse<EffectiveDictView> effective(
            @RequestParam String moduleCode,
            @RequestParam String code) {
        return ApiResponse.success(service.effective(moduleCode, code));
    }

    @Override
    @GetMapping("/resolve")
    public ApiResponse<EffectiveDictView> resolve(
            @RequestParam String moduleCode,
            @RequestParam String code) {
        return ApiResponse.success(service.resolve(moduleCode, code));
    }

    @Override
    @PostMapping
    public ApiResponse<DictView> create(@RequestBody DictCommand command) {
        return ApiResponse.success(service.create(command));
    }

    @Override
    @PutMapping("/{dictId}")
    public ApiResponse<DictView> update(
            @PathVariable("dictId") UUID dictId,
            @RequestBody DictCommand command) {
        return ApiResponse.success(service.update(dictId, command));
    }

    @Override
    @PostMapping("/{dictId}/items")
    public ApiResponse<DictItemView> createItem(
            @PathVariable("dictId") UUID dictId,
            @RequestBody DictItemCommand command) {
        return ApiResponse.success(service.createItem(dictId, command));
    }

    @Override
    @PutMapping("/items/{itemId}")
    public ApiResponse<DictItemView> updateItem(
            @PathVariable("itemId") UUID itemId,
            @RequestBody DictItemCommand command) {
        return ApiResponse.success(service.updateItem(itemId, command));
    }
}
