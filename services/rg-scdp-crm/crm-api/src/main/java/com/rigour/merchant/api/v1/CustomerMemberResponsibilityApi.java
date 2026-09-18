package com.rigour.merchant.api.v1;

import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 用户设置中的客户主责入口；所有关系仍由 CRM 写入。 */
public interface CustomerMemberResponsibilityApi {
    String BASE_PATH = CrmInternalCustomerApi.BASE_PATH + "/responsibility-members/{userId}";

    record Target(UUID userId, String employeeCode, String employeeName) {}

    record Customer(
            long customerId,
            String customerName,
            String customerCode,
            String customerType,
            String customerTypeName,
            String status,
            String regionCode,
            String regionName,
            String employeeCode,
            String employeeName,
            long revision) {}

    record Option(String code, String name) {}

    record Filters(List<Option> customerTypes, List<Option> regions, List<Option> statuses) {}

    record Page(
            List<Customer> items, long total, int page, int size, Target target, Filters filters) {}

    record Selection(long customerId, long revision) {}

    record PreviewCommand(String operation, List<Selection> customers, String reason) {}

    record PreviewItem(
            long customerId,
            String customerName,
            String regionCode,
            String regionName,
            String oldEmployeeCode,
            String oldEmployeeName,
            String newEmployeeCode,
            String newEmployeeName,
            long revision) {}

    record Preview(
            String previewToken,
            Instant expiresAt,
            Target target,
            String operation,
            List<PreviewItem> items,
            int count) {}

    record ApplyCommand(String previewToken) {}

    record Applied(int affectedCount) {}

    @GetMapping(BASE_PATH + "/customers")
    ApiResponse<Page> customers(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "OWNED") String mode,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String customerType,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size);

    @PostMapping(BASE_PATH + "/preview")
    ApiResponse<Preview> preview(@PathVariable UUID userId, @RequestBody PreviewCommand command);

    @PostMapping(BASE_PATH + "/apply")
    ApiResponse<Applied> apply(@PathVariable UUID userId, @RequestBody ApplyCommand command);
}
