package com.rigour.integration;

import com.rigour.platform.startup.ServiceApplicationLauncher;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * rigour-integration-migration-service 进程入口。
 * 该应用当前只证明服务边界和 Spring 上下文可启动，不代表领域能力已经实现或达到生产就绪。
 */
@SpringBootApplication
public class IntegrationMigrationServiceApplication {
    public static void main(String[] args) {
        ServiceApplicationLauncher.run(IntegrationMigrationServiceApplication.class, "集成迁移服务", args);
    }
}
