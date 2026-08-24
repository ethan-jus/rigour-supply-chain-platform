package com.rigour.erp;

import com.rigour.erp.maintenance.ErpFlywayMaintenance;
import com.rigour.platform.startup.ServiceApplicationLauncher;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * rigour-erp-core-service 进程入口。
 * 该应用当前只证明服务边界和 Spring 上下文可启动，不代表领域能力已经实现或达到生产就绪。
 */
@SpringBootApplication
public class ErpCoreServiceApplication {
    public static void main(String[] args) {
        if (ErpFlywayMaintenance.runIfRequested(args)) {
            return;
        }
        ServiceApplicationLauncher.run(ErpCoreServiceApplication.class, "ERP核心服务", args);
    }
}
