package com.rigour.integration.api.v1;

import com.rigour.integration.api.v1.model.DictionarySourceMappingView;
import com.rigour.integration.api.v1.model.DictionarySourceMappingCommand;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** Integration 持有来源原值映射，Settings 持有标准字典。 */
public interface DictionarySourceMappingApi {
    String BASE_PATH = "/api/v1/integration/dictionary-mappings";
    @GetMapping
    ApiResponse<List<DictionarySourceMappingView>> list(@RequestParam String dictionaryCode);
    @PutMapping("/{id}")
    ApiResponse<DictionarySourceMappingView> update(@PathVariable Long id, @RequestBody DictionarySourceMappingCommand command);
}
