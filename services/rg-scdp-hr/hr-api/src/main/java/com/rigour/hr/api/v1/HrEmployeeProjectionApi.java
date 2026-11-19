package com.rigour.hr.api.v1;

import com.rigour.hr.api.v1.model.ExternalEmployeeSyncCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeSyncResult;
import com.rigour.hr.api.v1.model.ExternalEmployeeResolveCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeResolvedView;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** HR 内部投影接口；Integration 通过可信上下文把外部人员资料写入 HR。 */
public interface HrEmployeeProjectionApi {
    String BASE_PATH = "/internal/v1/hr/employees";

    @PostMapping(BASE_PATH + "/external-sync")
    ApiResponse<ExternalEmployeeSyncResult> syncExternalEmployees(
            @RequestBody ExternalEmployeeSyncCommand command);

    @PostMapping(BASE_PATH + "/source-resolve")
    ApiResponse<List<ExternalEmployeeResolvedView>> resolveExternalEmployees(
            @RequestBody ExternalEmployeeResolveCommand command);

    @PostMapping(BASE_PATH + "/huolala-matches")
    ApiResponse<List<IdentityMatch>> matchHuolalaEmployees(@RequestBody List<IdentityLookup> staff);

    record IdentityLookup(
            String staffId, String mobile, String name, boolean uniqueMobile, boolean uniqueName) {
        public static String normalizedMobile(String value) {
            if (value == null) return "";
            String digits = value.replaceAll("[\\s()-]", "");
            if (digits.startsWith("+86")) digits = digits.substring(3);
            else if (digits.length() == 13 && digits.startsWith("86")) digits = digits.substring(2);
            return digits.matches("1[3-9][0-9]{9}") ? digits : "";
        }
    }

    enum IdentityMatchMethod {
        MOBILE,
        NAME
    }

    record IdentityMatch(
            String staffId, Long id, String employeeCode, String name, String departmentName,
            IdentityMatchMethod method) {}
}
