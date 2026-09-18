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
    private final com.rigour.integration.application.service.feishu.FeishuDictionaryGovernanceService governance;
    public DictionarySourceMappingController(DictionarySourceMappingService service,
            com.rigour.integration.application.service.feishu.FeishuDictionaryGovernanceService governance) { this.service=service;this.governance=governance; }
    @Override @GetMapping
    public ApiResponse<List<DictionarySourceMappingView>> list(@RequestParam String dictionaryCode) { return ApiResponse.success(service.list(dictionaryCode)); }
    @Override @PutMapping("/{id}")
    public ApiResponse<DictionarySourceMappingView> update(@PathVariable Long id,@RequestBody DictionarySourceMappingCommand command) { return ApiResponse.success(service.update(id,command)); }
    @Override @PostMapping("/rescan")
    public ApiResponse<com.rigour.integration.api.v1.model.DictionaryRescanResult> rescan(@RequestParam String dictionaryCode) {
        return ApiResponse.success(governance.rescan(dictionaryCode));
    }
}
