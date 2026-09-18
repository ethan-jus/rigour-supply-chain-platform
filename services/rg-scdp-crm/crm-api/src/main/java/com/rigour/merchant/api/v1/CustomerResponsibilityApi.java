package com.rigour.merchant.api.v1;

import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

public interface CustomerResponsibilityApi {
    String BASE_PATH = CrmInternalCustomerApi.BASE_PATH;

    record Employee(String code, String name, String departmentName) {}

    record Change(String employeeCode, String regionCode, long revision, String reason) {}

    record Resolution(String decision, long customerRevision, String reason) {}

    record History(
            long id,
            String oldEmployee,
            String oldName,
            String newEmployee,
            String newName,
            String oldRegion,
            String newRegion,
            String reason,
            String actor,
            Instant occurredAt) {}

    record Conflict(
            long id,
            String source,
            String proposedEmployee,
            String proposedRegion,
            String sourceRevision,
            Instant observedAt) {}

    record Overview(
            long customerId,
            String customerName,
            String employeeCode,
            String employeeName,
            String regionCode,
            long revision,
            List<History> history,
            long historyTotal,
            List<Conflict> conflicts,
            long pendingCount) {}

    @GetMapping(BASE_PATH + "/responsibility-employees")
    ApiResponse<List<Employee>> employees(@RequestParam(required = false) String keyword);

    @GetMapping(BASE_PATH + "/{id}/responsibility")
    ApiResponse<Overview> overview(@PathVariable long id);

    @PutMapping(BASE_PATH + "/{id}/responsibility")
    ApiResponse<Overview> transfer(@PathVariable long id, @RequestBody Change command);

    @PostMapping(BASE_PATH + "/{id}/responsibility/conflicts/{conflictId}/resolve")
    ApiResponse<Overview> resolve(
            @PathVariable long id, @PathVariable long conflictId, @RequestBody Resolution command);
}
