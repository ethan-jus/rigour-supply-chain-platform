package com.rigour.hr.api.v1;

import com.rigour.hr.api.v1.model.HrPageView;
import com.rigour.hr.api.v1.model.HrPositionCommand;
import com.rigour.hr.api.v1.model.HrPositionView;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** HR 岗位职位接口；岗位和职位归 HR 主数据管理。 */
public interface HrPositionApi {
    String BASE_PATH = "/api/v1/hr/positions";

    @GetMapping(BASE_PATH)
    ApiResponse<HrPageView<HrPositionView>> positions(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String positionCode,
            @RequestParam(required = false) String positionName,
            @RequestParam(required = false) String positionType,
            @RequestParam(required = false) String statusCode,
            @RequestParam(required = false) String sourceSystem);

    @GetMapping(BASE_PATH + "/{id}")
    ApiResponse<HrPositionView> position(@PathVariable("id") Long id);

    @PostMapping(BASE_PATH)
    ApiResponse<HrPositionView> create(@RequestBody HrPositionCommand command);

    @PutMapping(BASE_PATH + "/{id}")
    ApiResponse<HrPositionView> update(
            @PathVariable("id") Long id,
            @RequestBody HrPositionCommand command);

    @DeleteMapping(BASE_PATH + "/{id}")
    ApiResponse<Void> delete(
            @PathVariable("id") Long id,
            @RequestParam int revision);
}
