package com.rigour.settings;

import com.rigour.settings.maintenance.BusinessSettingsFlywayMaintenance;
import com.rigour.platform.startup.ServiceApplicationLauncher;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 公共业务设置服务入口；仅持有跨领域共享的业务字典，不持有领域业务事实。 */
@SpringBootApplication
public class BusinessSettingsServiceApplication {
    public static void main(String[] args) {
        if (BusinessSettingsFlywayMaintenance.runIfRequested(args)) {
            return;
        }
        ServiceApplicationLauncher.run(BusinessSettingsServiceApplication.class, "公共业务设置服务", args);
    }
}
