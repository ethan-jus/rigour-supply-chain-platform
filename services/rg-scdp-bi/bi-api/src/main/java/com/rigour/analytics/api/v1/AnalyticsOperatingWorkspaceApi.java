package com.rigour.analytics.api.v1;

import com.rigour.analytics.api.v1.model.OperatingWorkspaceModels.*;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 经营维护接口；所有读写在应用层按当前可信租户及显式权限校验。 */
public interface AnalyticsOperatingWorkspaceApi {
    String ROOT = "/api/v1/analytics/supply/dashboard";

    @GetMapping(ROOT + "/targets")
    ApiResponse<List<TargetView>> targets(@RequestParam String month,
            @RequestParam(required = false) String dimensionType,
            @RequestParam(required = false) String dimensionCode);

    @PutMapping(ROOT + "/targets")
    ApiResponse<TargetView> saveTarget(@RequestBody TargetCommand command);

    @DeleteMapping(ROOT + "/targets/{id}")
    ApiResponse<Void> deleteTarget(@PathVariable String id, @RequestParam int revision);

    @GetMapping(ROOT + "/actions")
    ApiResponse<ActionPage> actions(@RequestParam(required = false) String kind,
            @RequestParam(required = false) String businessRef,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String employeeCode,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize);

    @PostMapping(ROOT + "/actions")
    ApiResponse<ActionView> createAction(@RequestBody ActionCommand command);

    @PutMapping(ROOT + "/actions/{id}")
    ApiResponse<ActionView> updateAction(@PathVariable String id, @RequestBody ActionUpdateCommand command);

    @GetMapping(ROOT + "/actions/{id}/events")
    ApiResponse<List<ActionEventView>> actionEvents(@PathVariable String id);
}
