package com.rigour.order.application.port.out;

import com.rigour.shared.context.CallerIdentity;
import java.util.List;
import java.util.Set;

/** Order读取IAM人员展示信息的端口；业务主表只保存员工编码。 */
public interface IamStaffDisplayClient {
    List<StaffDisplay> resolve(CallerIdentity caller, Set<String> staffCodes);

    record StaffDisplay(String staffCode, String staffName, String employmentStatus) {
    }
}
