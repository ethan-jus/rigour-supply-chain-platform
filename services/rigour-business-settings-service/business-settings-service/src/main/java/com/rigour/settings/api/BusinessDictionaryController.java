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
            @RequestParam(required = false) String dictionaryType,
            @RequestParam(required = false) String dictionaryCode) {
        return ApiResponse.success(service.list(dictionaryType, dictionaryCode));
    }

    @Override
    @GetMapping("/{dictionaryId}/items")
    public ApiResponse<List<DictItemView>> items(@PathVariable("dictionaryId") Long dictionaryId) {
        return ApiResponse.success(service.items(dictionaryId));
    }

    @Override
    @GetMapping("/effective")
    public ApiResponse<EffectiveDictView> effective(@RequestParam String dictionaryCode) {
        return ApiResponse.success(service.effective(dictionaryCode));
    }

    @Override
    @GetMapping("/resolve")
    public ApiResponse<EffectiveDictView> resolve(@RequestParam String dictionaryCode) {
        return ApiResponse.success(service.resolve(dictionaryCode));
    }

    @Override
    @PostMapping
    public ApiResponse<DictView> create(@RequestBody DictCommand command) {
        return ApiResponse.success(service.create(command));
    }

    @Override
    @PutMapping("/{dictionaryId}")
    public ApiResponse<DictView> update(
            @PathVariable("dictionaryId") Long dictionaryId,
            @RequestBody DictCommand command) {
        return ApiResponse.success(service.update(dictionaryId, command));
    }

    @Override
    @PostMapping("/{dictionaryId}/items")
    public ApiResponse<DictItemView> createItem(
            @PathVariable("dictionaryId") Long dictionaryId,
            @RequestBody DictItemCommand command) {
        return ApiResponse.success(service.createItem(dictionaryId, command));
    }

    @Override
    @PutMapping("/items/{itemId}")
    public ApiResponse<DictItemView> updateItem(
            @PathVariable("itemId") Long itemId,
            @RequestBody DictItemCommand command) {
        return ApiResponse.success(service.updateItem(itemId, command));
    }
}
