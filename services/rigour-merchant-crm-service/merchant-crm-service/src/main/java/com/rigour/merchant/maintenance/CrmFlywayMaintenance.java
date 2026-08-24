package com.rigour.merchant.maintenance;

import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.RepairResult;

/**
 * CRM Flyway 显式维护模式。
 *
 * <p>正常启动不会触发本逻辑。只有启动参数包含
 * {@code --rigour.crm.maintenance=flyway-repair} 时，服务才会在 Web 容器启动前执行
 * {@link Flyway#repair()} 和 {@link Flyway#migrate()}，随后直接退出。</p>
 *
 * <p>该模式用于共享 DEV 或开发环境修复失败迁移记录。它只读取环境变量中的迁移账号密码，不输出密码。</p>
 */
public final class CrmFlywayMaintenance {

    public static final String ARGUMENT = "--rigour.crm.maintenance=flyway-repair";
    private static final String DEFAULT_JDBC_URL = "jdbc:mysql://82.157.4.176:13306/rigour_crm"
            + "?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC"
            + "&forceConnectionTimeZoneToSession=true&sslMode=PREFERRED&allowPublicKeyRetrieval=true";
    private static final String DEFAULT_USER = "rigour_crm_migrator";

    private CrmFlywayMaintenance() {
    }

    public static boolean runIfRequested(String[] args) {
        if (args == null || Arrays.stream(args).noneMatch(ARGUMENT::equals)) {
            return false;
        }
        run();
        return true;
    }

    private static void run() {
        String url = env("CRM_DB_MIGRATOR_URL", DEFAULT_JDBC_URL);
        String user = env("CRM_DB_MIGRATOR_USER", DEFAULT_USER);
        String password = requiredEnv("CRM_DB_MIGRATOR_PASSWORD");

        System.out.println("CRM Flyway 维护模式启动");
        System.out.println("目标库: " + maskUrl(url));
        System.out.println("迁移账号: " + user);
        System.out.println("迁移脚本: classpath:db/migration");

        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .load();

        RepairResult repair = flyway.repair();
        System.out.println("Flyway repair 完成，removedFailedMigrations="
                + repair.migrationsRemoved.size() + ", migrationsDeleted=" + repair.migrationsDeleted.size());

        MigrateResult migrate = flyway.migrate();
        System.out.println("Flyway migrate 完成，migrationsExecuted=" + migrate.migrationsExecuted);

        System.out.println("当前迁移状态:");
        for (MigrationInfo info : flyway.info().all()) {
            String version = info.getVersion() == null ? "-" : info.getVersion().getVersion();
            System.out.printf("  V%s %-45s %s%n", version, info.getDescription(), info.getState());
        }
        System.out.println("CRM Flyway 维护模式结束，请移除维护参数后正常启动服务");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name + "，请在 CRM 服务 Run Configuration 中配置迁移密码");
        }
        return value;
    }

    private static String maskUrl(String url) {
        int queryIndex = url.indexOf('?');
        return queryIndex < 0 ? url : url.substring(0, queryIndex) + "?...";
    }
}
