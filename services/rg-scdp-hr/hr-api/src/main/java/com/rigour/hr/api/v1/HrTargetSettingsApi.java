package com.rigour.hr.api.v1;

import com.rigour.hr.api.v1.model.TargetSettingsModels.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.List;

public interface HrTargetSettingsApi {
    String ROOT = "/api/v1/hr/target-settings";

    @GetMapping(ROOT)
    ApiResponse<Settings> settings(@RequestParam String month);

    @PutMapping(ROOT)
    ApiResponse<Void> save(@RequestBody Batch command);

    @GetMapping(ROOT + "/values")
    ApiResponse<List<Target>> values(@RequestParam String from, @RequestParam String to);

    @GetMapping(ROOT + "/history")
    ApiResponse<List<History>> history(
            @RequestParam String month,
            @RequestParam String dimensionType,
            @RequestParam String code);
}
