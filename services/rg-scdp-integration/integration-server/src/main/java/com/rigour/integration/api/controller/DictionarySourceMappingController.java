package com.rigour.integration.api.controller;

import com.rigour.integration.api.v1.DictionarySourceMappingApi;
import com.rigour.integration.api.v1.model.DictionarySourceMappingView;
import com.rigour.integration.api.v1.model.DictionarySourceMappingCommand;
import com.rigour.integration.application.service.DictionarySourceMappingService;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 当前租户来源映射的管理入口。 */
@RestController
@RequestMapping(DictionarySourceMappingApi.BASE_PATH)
public class DictionarySourceMappingController implements DictionarySourceMappingApi {
    private final DictionarySourceMappingService service;
    public DictionarySourceMappingController(DictionarySourceMappingService service) { this.service=service; }
    @Override @GetMapping
    public ApiResponse<List<DictionarySourceMappingView>> list(@RequestParam String dictionaryCode) { return ApiResponse.success(service.list(dictionaryCode)); }
    @Override @PutMapping("/{id}")
    public ApiResponse<DictionarySourceMappingView> update(@PathVariable Long id,@RequestBody DictionarySourceMappingCommand command) { return ApiResponse.success(service.update(id,command)); }
    @Override @PostMapping("/dhb-carrier-status")
    public ApiResponse<List<String>> dhbCarrierStatus(@RequestBody List<String> values) {
        return ApiResponse.success(service.mappedShipmentCarrierValues(values));
    }
    @Override @PostMapping("/rescan")
    public ApiResponse<com.rigour.integration.api.v1.model.DictionaryRescanResult> rescan(@RequestParam String dictionaryCode) {
        return ApiResponse.success(governance.rescan(dictionaryCode));
    }
}
