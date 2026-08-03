package com.rigour.integration;

import com.rigour.platform.startup.ServiceApplicationLauncher;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * rigour-integration-migration-service 进程入口。
 *
 * <p>当前版本提供订货宝连接配置、同步任务和字段映射控制面，并通过 Flyway
 * 初始化同步批次、游标、Raw Landing、死信重放、事件出站、对账和数据主权表。
 * 第三方真实拉取仍必须以订货宝确认的 API 合同和 Secret 引用为前提；没有合同或
 * Secret 时不会伪造外部数据，也不会把员工浏览器密码当成连接凭据。</p>
 */
@SpringBootApplication
public class IntegrationMigrationServiceApplication {
    public static void main(String[] args) {
        ServiceApplicationLauncher.run(IntegrationMigrationServiceApplication.class, "集成迁移服务", args);
    }
}
