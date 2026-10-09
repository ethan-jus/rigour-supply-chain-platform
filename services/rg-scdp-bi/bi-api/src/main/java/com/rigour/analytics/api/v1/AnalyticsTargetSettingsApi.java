package com.rigour.analytics.api.v1;
import com.rigour.analytics.api.v1.model.TargetSettingsModels.*;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.List;
public interface AnalyticsTargetSettingsApi {
    String ROOT="/api/v1/analytics/supply/target-settings";
    @GetMapping(ROOT) ApiResponse<Settings> settings(@RequestParam String month);
    @PutMapping(ROOT) ApiResponse<Void> save(@RequestBody Batch command);
    @PutMapping(ROOT+"/defaults") ApiResponse<Void> defaults(@RequestBody DefaultsBatch command);
    @GetMapping(ROOT+"/history") ApiResponse<List<History>> history(@RequestParam String month,
            @RequestParam String dimensionType,@RequestParam String code);
}
