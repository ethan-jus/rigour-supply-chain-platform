package com.rigour.order.application.port.out;

import com.rigour.shared.context.CallerIdentity;
import java.util.List;
import java.util.Set;

/** Order读取HR员工展示信息的端口；业务主表只保存员工编码。 */
public interface HrEmployeeDisplayClient {
    List<EmployeeDisplay> resolve(CallerIdentity caller, Set<String> employeeCodes);

    record EmployeeDisplay(String employeeCode, String employeeName, String employmentStatus) {
    }
}
