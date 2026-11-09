package com.rigour.tenant.iam.infrastructure.persistence.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.ValidateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * 仅容许共享 DEV 已核实成功执行、但尚未同步到本机的 V133、V147 缺失。
 *
 * <p>类名及开关保留以兼容现有 IDEA 运行参数。不伪造迁移文件、不修改历史记录。
 * TODO：同事同步所有原版文件后删除本策略及其配置入口。
 */
public final class KnownMissingV133MigrationStrategy implements FlywayMigrationStrategy {
    private static final Logger LOG =
            LoggerFactory.getLogger(KnownMissingV133MigrationStrategy.class);
    private static final MigrationVersion VERSION = MigrationVersion.fromVersion("133");
    private static final String SCRIPT = "V133__iam_native_app_session_client.sql";
    private static final String DESCRIPTION = "iam native app session client";
    private static final int CHECKSUM = -1799016397;
    private static final Map<MigrationVersion, KnownMigration> KNOWN_MIGRATIONS =
            Map.of(
                    VERSION, new KnownMigration(SCRIPT, DESCRIPTION, CHECKSUM),
                    MigrationVersion.fromVersion("147"),
                            new KnownMigration(
                                    "V147__hr_employee_delete_permission.sql",
                                    "hr employee delete permission",
                                    -1379744324));

    @Override
    public void migrate(Flyway flyway) {
        // 预检只忽略待执行迁移，逐项核对缺失或未来版本，保留其他校验错误。
        Flyway validation =
                Flyway.configure()
                        .configuration(flyway.getConfiguration())
                        .ignoreMigrationPatterns("*:pending")
                        .load();
        ValidateResult result = validation.validateWithResult();
        MigrationInfo[] migrations = validation.info().all();
        MigrationInfo original =
                Arrays.stream(migrations)
                        .filter(info -> VERSION.equals(info.getVersion()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new FlywayException(
                                                "本机临时启动要求数据库已有原版 V133，或本地已补齐该文件；不能用于跳过新库迁移。"));

        if (!SCRIPT.equals(original.getScript())
                || !DESCRIPTION.equals(original.getDescription())
                || !Objects.equals(CHECKSUM, original.getChecksum())) {
            throw new FlywayException("V133 与已核实的原生 App 迁移记录不一致，禁止临时启动。");
        }
        if (result.validationSuccessful && original.getState().isResolved()) {
            LOG.info("迁移文件校验通过，执行正常迁移；请移除 allow-missing-v133 临时参数。");
            flyway.migrate();
            return;
        }
        if (!isOnlyKnownMissingMigrations(result, migrations)) {
            throw new FlywayException(
                    "仅允许缺失已核实的原版 V133、V147，其他迁移问题必须修复：" + result.getAllErrorMessages());
        }

        LOG.warn(
                "本机临时允许缺失迁移版本 {}；已核对文件名、描述、校验值和成功状态，保留数据库历史，请尽快补齐原文件。",
                result.invalidMigrations.stream().map(invalid -> invalid.version).toList());
        // 最新已执行版本可能被识别为 future；两类模式均只能在精确预检通过后使用。
        Flyway.configure()
                .configuration(flyway.getConfiguration())
                .ignoreMigrationPatterns("versioned:missing", "versioned:future")
                .validateOnMigrate(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    private static boolean isOnlyKnownMissingMigrations(
            ValidateResult result, MigrationInfo[] migrations) {
        if (result.validationSuccessful || result.invalidMigrations.isEmpty()) {
            return false;
        }
        return result.invalidMigrations.stream()
                .allMatch(
                        invalid -> {
                            if (invalid.errorDetails.errorCode
                                    != CoreErrorCode.APPLIED_VERSIONED_MIGRATION_NOT_RESOLVED) {
                                return false;
                            }
                            var version = MigrationVersion.fromVersion(invalid.version);
                            var expected = KNOWN_MIGRATIONS.get(version);
                            if (expected == null) {
                                return false;
                            }
                            var matches =
                                    Arrays.stream(migrations)
                                            .filter(info -> version.equals(info.getVersion()))
                                            .toList();
                            if (matches.size() != 1) {
                                return false;
                            }
                            var info = matches.getFirst();
                            return (info.getState() == MigrationState.MISSING_SUCCESS
                                            || info.getState() == MigrationState.FUTURE_SUCCESS)
                                    && "SQL".equals(info.getType().name())
                                    && expected.script().equals(info.getScript())
                                    && expected.description().equals(info.getDescription())
                                    && Objects.equals(expected.checksum(), info.getChecksum());
                        });
    }

    private record KnownMigration(String script, String description, int checksum) {}
}
