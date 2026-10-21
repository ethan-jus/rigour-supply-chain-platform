package com.rigour.sales.api.v1;

import com.rigour.sales.api.v1.model.SalesAttendanceModels.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 仅向 HR 提供已关联员工的打卡日期，不包含客户、位置或录音。 */
public interface SalesAttendanceSourceApi {
    @PostMapping("/api/v1/sales/attendance-evidence")
    ApiResponse<List<Evidence>> attendanceEvidence(@RequestBody Query query);
}
