package com.rigour.hr;

import com.rigour.platform.startup.ServiceApplicationLauncher;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** rigour-hr-payroll-service 进程入口；员工主档、岗位职位和后续人事绩效能力归该服务。 */
@SpringBootApplication
public class HrPayrollServiceApplication {
    public static void main(String[] args) {
        ServiceApplicationLauncher.run(HrPayrollServiceApplication.class, "人力薪资服务", args);
    }
}
